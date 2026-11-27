package io.legado.app.help.video.introoutro

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import io.legado.app.data.repository.VideoSettingsRepository
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 边看边采：挂在 AudioSink 上把正在播放的 PCM 顺手算成 chroma 特征。
 *
 * - 不额外下流量、不额外解码：声音本来就要解，这里只多算一点 FFT（约 11 帧/秒）。
 * - 只记“开头 8 分钟”和“结尾 8 分钟”，中间正片不存，省内存。
 * - 看完一集（或切集/退出）时落盘并跑一次同季比对；不稳就什么都不写。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object EpisodeAudioCapture : TeeAudioProcessor.AudioBufferSink, KoinComponent {

    private const val HEAD_WINDOW_MS = 480_000L
    private const val TAIL_WINDOW_MS = 480_000L
    private const val MIN_STORED_FRAMES = 500    // 约 1.5 分钟音频才值得算
    private const val STORE_EVERY = 2            // 每两帧存一帧（约 0.19 秒一帧）
    private const val MIN_PROFILE_FRAMES = 60
    /** 落盘/比对统一用 200ms 一格：不管设备是 44.1k 还是 48k、帧长 93ms 还是 85ms，比对都对齐。 */
    private const val PROFILE_STEP_MS = 200
    private const val INTRO_MIN_MS = 15_000
    private const val INTRO_MAX_MS = 120_000
    /** 只有一对集对上时的最低相似度：比“多集投票”更严，避免单条脏参考写出误判 */
    private const val SINGLE_PAIR_MIN_SCORE = 0.80f
    /** 只有一对集对上时，片头起点必须非常靠前 */
    private const val SINGLE_PAIR_MAX_START_MS = 75_000L
    /** head 窗口的起点必须落在开头这段内，才承认它是“开头窗口”（续播补采的中段不算）。 */
    private const val HEAD_MAX_START_MS = 90_000L
    /** 起播位置在这以内算“从头看”，允许把开头窗口入库 */
    private const val START_MAX_MS = 60_000L
    /** 采样空洞容忍度：网速抖动导致几秒没采到音频时，仍算同一段（用上一帧补齐） */
    private const val GAP_TOLERANCE_MS = 6_000L
    private const val OUTRO_MIN_MS = 30_000
    private const val OUTRO_MAX_MS = 300_000

    private val repo: VideoSettingsRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private class Frame(val tMs: Long, val chroma: FloatArray, val energy: Float)

    private var fid: String? = null
    private var title: String = ""
    private var season: Int = 1
    private var episode: Int = 0
    private var durationMs: Long = 0L

    private var sampleRate: Int = 0
    private var channels: Int = 1
    private var step: Int = 1
    private var frameDurationMs: Int = 93
    private var pending: FloatArray = FloatArray(AudioFingerprint.FFT_SIZE)
    private var pendingCount: Int = 0
    private var decPhase: Int = 0
    private var frameCounter: Long = 0L
    private var anchorMs: Long = 0L
    private var frames: ArrayList<Frame> = ArrayList()
    /** 本集第一次看到的播放位置；用来判断这次是不是“从头开始看” */
    private var firstPositionMs: Long = -1L

    val enabled: Boolean get() = runCatching { repo.autoIntroOutroEnabled }.getOrDefault(false)

    /** 进入某一集时调用；同一集重复调用无副作用。 */
    fun begin(fid: String, title: String, season: Int, episode: Int) {
        if (!enabled || fid.isBlank()) return
        synchronized(lock) {
            if (this.fid == fid) return
        }
        finish()
        synchronized(lock) {
            this.fid = fid
            this.title = title
            this.season = season
            this.episode = episode
            this.durationMs = 0L
            frames = ArrayList()
            pendingCount = 0
            decPhase = 0
            frameCounter = 0L
            anchorMs = 0L
            firstPositionMs = -1L
        }
        LogUtils.d("IntroOutro") { "begin fid=$fid ep=S$season E$episode title=$title" }
    }

    fun setDuration(ms: Long) {
        synchronized(lock) { if (ms > 0) durationMs = ms }
    }

    /** 播放位置锚点：每秒一次即可，用来把“第几帧”换算成媒体时间。 */
    fun onPosition(positionMs: Long) {
        synchronized(lock) {
            if (fid == null) return
            if (firstPositionMs < 0 && positionMs >= 0) firstPositionMs = positionMs
            val expected = anchorMs + frameCounter * frameDurationMs
            if (abs(expected - positionMs) > 1000L) {
                // 发生过 seek（或长时间漂移），以播放器为准重新起算
                anchorMs = positionMs
                frameCounter = 0L
            }
        }
    }

    /** 切集 / 退出播放页时调用：落盘 + 同季比对，异步执行。 */
    fun finish() {
        val snapshot = synchronized(lock) {
            if (fid == null) return
            val snap = Snapshot(
                fid = fid!!,
                title = title,
                season = season,
                episode = episode,
                durationMs = durationMs,
                startPosMs = firstPositionMs,
                frames = frames,
            )
            fid = null
            frames = ArrayList()
            pendingCount = 0
            frameCounter = 0L
            anchorMs = 0L
            firstPositionMs = -1L
            snap
        }
        if (snapshot.frames.size < MIN_STORED_FRAMES) {
            LogUtils.d("IntroOutro") { "finish fid=${snapshot.fid} 采样不足 ${snapshot.frames.size} 帧，放弃" }
            return
        }
        LogUtils.d("IntroOutro") { "finish fid=${snapshot.fid} 帧数=${snapshot.frames.size} duration=${snapshot.durationMs}" }
        scope.launch { process(snapshot) }
    }

    private class Snapshot(
        val fid: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val durationMs: Long,
        val startPosMs: Long,
        val frames: List<Frame>,
    )

    // ---------------- AudioBufferSink ----------------

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            if (sampleRateHz <= 0) return
            sampleRate = sampleRateHz
            channels = max(1, channelCount)
            step = max(1, (sampleRateHz / 11025f).roundToInt())
            val effectiveRate = sampleRateHz / step
            frameDurationMs = (AudioFingerprint.FFT_SIZE * 1000L / effectiveRate).toInt().coerceAtLeast(1)
            pendingCount = 0
            decPhase = 0
            frameCounter = 0L
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        synchronized(lock) {
            if (fid == null || sampleRate <= 0 || channels <= 0) return
            if (buffer.remaining() < 2) return
            val shortBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val total = shortBuffer.remaining()
            val ch = channels
            var i = 0
            while (i + ch <= total) {
                var sum = 0f
                for (c in 0 until ch) sum += shortBuffer.get(i + c).toFloat()
                val mono = sum / ch / 32768f
                if (decPhase == 0) {
                    pending[pendingCount++] = mono
                    if (pendingCount >= AudioFingerprint.FFT_SIZE) {
                        onFrameLocked()
                        pendingCount = 0
                    }
                }
                decPhase++
                if (decPhase >= step) decPhase = 0
                i += ch
            }
        }
    }

    /** 有锁调用：算一帧 chroma 并记录（时间轴由锚点 + 帧序号推算）。 */
    private fun onFrameLocked() {
        if (frameCounter % STORE_EVERY == 0L && frames.size < 40000) {
            val rate = sampleRate / step
            val chroma = AudioFingerprint.chromaOf(pending, rate)
            // 帧能量用于“片头结束点精修”：片头末尾通常接一段静音/黑场，
            // 靠它把结束点钉准，避免匹配段被拉长到把正片开头吞进去
            val energy = runCatching {
                AudioFingerprint.energyOf(pending, minOf(pending.size, AudioFingerprint.FFT_SIZE))
            }.getOrDefault(0f)
            val tMs = anchorMs + frameCounter * frameDurationMs
            // 宽限 5 秒：超过片长的帧说明锚点没跟上（起播在片尾、seek 后没同步等），直接丢弃
            val sane = tMs >= 0 && (durationMs <= 0L || tMs <= durationMs + 5_000L)
            if (sane) frames.add(Frame(tMs, chroma, energy))
        }
        frameCounter++
    }

    // ---------------- 汇总 / 比对 ----------------

    private fun process(snapshot: Snapshot) {
        try {
            processInner(snapshot)
        } catch (e: Throwable) {
            LogUtils.d("IntroOutro") {
                "process 异常 ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun processInner(snapshot: Snapshot) {
            val all = snapshot.frames.sortedBy { it.tMs }
            LogUtils.d("IntroOutro") {
                "profile fid=${snapshot.fid} frames=${all.size} " +
                    "span=${all.firstOrNull()?.tMs ?: -1}..${all.lastOrNull()?.tMs ?: -1} " +
                    "duration=${snapshot.durationMs}"
            }
            if (all.size < MIN_PROFILE_FRAMES) return
            val stepMs = PROFILE_STEP_MS

            var head = buildProfile(all, 0L, HEAD_WINDOW_MS, stepMs, preferEarliest = true)
            // 只在“从头开始看”时才把开头窗口当成参考集：
            // 中途续播采到的是中段声音，存进参考集会污染其它集的比对（本机历史脏数据的根源）。
            val fromBeginning = snapshot.startPosMs in 0..START_MAX_MS
            if (!fromBeginning && head != null) {
                LogUtils.d("IntroOutro") {
                    "head 起点 ${head.startMs}ms, 本次起播位置 ${snapshot.startPosMs}ms（非从头），开头窗口不入库"
                }
                head = null
            }
            // 从中间续播时，采到的只是中段声音，不能冒充“开头窗口”：
            // 这类窗口拿去跟别的集比对，会把中段一段相似 BGM 认成片头，写出离谱的跳转点。
            if (head != null && head.startMs > HEAD_MAX_START_MS) {
                LogUtils.d("IntroOutro") { "head 起点 ${head.startMs}ms 已过开头，丢弃（续播补采不算开头）" }
                head = null
            }
            val tail = if (snapshot.durationMs > 60_000L) {
                buildProfile(all, snapshot.durationMs - TAIL_WINDOW_MS, snapshot.durationMs + 5_000L, stepMs)
            } else null
            LogUtils.d("IntroOutro") {
                "profile2 fid=${snapshot.fid} head=${head?.size ?: 0}@${head?.startMs ?: -1} " +
                    "tail=${tail?.size ?: 0}@${tail?.startMs ?: -1}"
            }
            if (head == null && tail == null) return

            val key = "${snapshot.title}|S${snapshot.season}"
            EpisodeAudioStore.save(
                snapshot.fid,
                EpisodeAudioStore.Meta(
                    key = key,
                    title = snapshot.title,
                    season = snapshot.season,
                    episode = snapshot.episode,
                    updatedAt = System.currentTimeMillis(),
                ),
                head,
                tail,
            )

            val refs = EpisodeAudioStore.loadSiblings(key, snapshot.fid)

            // 比对用“合并后真正落盘的那份窗口”，而不是本次这一小段：
            // 从中间续播只采到中段时，不应该拿中段去跟别的集比对。
            val stored = EpisodeAudioStore.load(snapshot.fid)
            // 老数据里也有“中段窗口”存成了 head 的，比对前先按同一条规矩滤掉
            val headForCompare = stored?.first?.takeIf { it.startMs <= HEAD_MAX_START_MS } ?: head
            val tailForCompare = stored?.second ?: tail

            val intros = ArrayList<IntroOutroDetector.Segment>()
            if (headForCompare != null) {
                refs.forEach { (refFid, refHead, _) ->
                    if (refHead != null) {
                        val seg = IntroOutroDetector.findCommonSegment(
                            refFid = refFid,
                            ref = refHead,
                            target = headForCompare,
                            maxShiftMs = 30_000,
                            minRunMs = INTRO_MIN_MS,
                            maxRunMs = INTRO_MAX_MS,
                        )
                        if (seg != null && isPlausibleIntro(seg.startMs, snapshot.durationMs)) {
                            intros.add(seg)
                            LogUtils.d("IntroOutro") {
                                "match ref=$refFid seg=${seg.startMs}..${seg.endMs} 采纳"
                            }
                        } else if (seg != null) {
                            LogUtils.d("IntroOutro") {
                                "match ref=$refFid seg=${seg.startMs}..${seg.endMs} 起点不合理，丢弃"
                            }
                        }
                    }
                }
            }

            val outros = ArrayList<IntroOutroDetector.Segment>()
            if (tailForCompare != null && snapshot.durationMs > 0L) {
                refs.forEach { (refFid, _, refTail) ->
                    if (refTail != null) {
                        val seg = IntroOutroDetector.findCommonSegment(
                            refFid = refFid,
                            ref = refTail,
                            target = tailForCompare,
                            // 片尾曲起点在两集之间相差最多几分钟，搜索窗按整个结尾窗口放开
                            maxShiftMs = 420_000,
                            minRunMs = OUTRO_MIN_MS,
                            maxRunMs = OUTRO_MAX_MS,
                        )
                        if (seg != null && seg.startMs <= snapshot.durationMs - 20_000L) {
                            outros.add(seg)
                        }
                    }
                }
            }

            val introConsensus = IntroOutroDetector.consensus(intros)
            val outroConsensus = IntroOutroDetector.consensus(outros)
            // 两两比对补齐：IntroSkipper 就是“任意两集对上就算”，不强制凑够两票。
            // 我们保留一层质量门槛（相似度够高、起点贴近开头、长度在片头区间内），
            // 这样单对匹配也能落库，网速烂导致凑不齐票时仍然学得到。
            val singleIntro = if (introConsensus == null) {
                intros.filter {
                    val len = it.endMs - it.startMs
                    len in INTRO_MIN_MS..INTRO_MAX_MS && it.startMs <= SINGLE_PAIR_MAX_START_MS
                }.maxByOrNull { it.score }?.takeIf { it.score >= SINGLE_PAIR_MIN_SCORE }
            } else null
            val singleOutro = if (outroConsensus == null) {
                outros.filter {
                    val len = it.endMs - it.startMs
                    len >= OUTRO_MIN_MS && it.startMs <= snapshot.durationMs - 20_000L
                }.maxByOrNull { it.score }?.takeIf { it.score >= SINGLE_PAIR_MIN_SCORE }
            } else null

            LogUtils.d("IntroOutro") {
                "compare fid=${snapshot.fid} refs=${refs.size} head=${head?.size ?: 0}@${head?.startMs ?: -1} " +
                    "tail=${tail?.size ?: 0}@${tail?.startMs ?: -1} " +
                    "stored_head=${headForCompare?.size ?: 0}@${headForCompare?.startMs ?: -1} " +
                    "intro候选=${intros.size}/${introConsensus?.second ?: 0} outro候选=${outros.size}/${outroConsensus?.second ?: 0} " +
                    "单对=${singleIntro?.let { "${it.startMs}..${it.endMs}@${it.score}" } ?: "none"}"
            }
            if (introConsensus == null && outroConsensus == null && singleIntro == null && singleOutro == null) return

            val epKey = "${snapshot.title}|S${snapshot.season}|E${snapshot.episode}|${snapshot.fid}"
            val votes = maxOf(introConsensus?.second ?: 0, outroConsensus?.second ?: 0)
            val confidence = if (votes > 0) {
                (votes / maxOf(2, refs.size).toDouble())
                    .coerceIn(EpisodeIntroOutroStore.MIN_AUTO_CONFIDENCE, 1.0)
            } else {
                EpisodeIntroOutroStore.MIN_AUTO_CONFIDENCE
            }
            // 边界精修：片头末尾通常接一段静音，把"匹配段的结尾"对齐到这段静音的开头。
            // TorBox/IntroSkipper 都用这一手防"片头结束点被拉长到正片里"。
            val autoIntroEnd = (introConsensus?.first ?: singleIntro)?.let {
                refineIntroEnd(all, it.endMs, it.startMs, snapshot.durationMs)
            }
            val autoOutroStart = outroConsensus?.first?.startMs ?: singleOutro?.startMs

            // 手动优先（逐字段）：用户亲手设过的那一项永远不动，只有空着的那一项才允许自动补
            val existing = EpisodeIntroOutroStore.read(repo, snapshot.fid)
            val manualIntro = existing?.introSource == EpisodeIntroOutroStore.SOURCE_MANUAL &&
                (existing.introEndMs > 0L)
            val manualOutro = existing?.outroSource == EpisodeIntroOutroStore.SOURCE_MANUAL &&
                (existing.outroStartMs > 0L)
            val finalIntro = if (manualIntro) existing!!.introEndMs else (autoIntroEnd ?: existing?.introEndMs ?: 0L)
            val finalOutro = if (manualOutro) existing!!.outroStartMs else (autoOutroStart ?: existing?.outroStartMs ?: 0L)
            if (finalIntro <= 0L && finalOutro <= 0L) return
            val introSrc = if (manualIntro) EpisodeIntroOutroStore.SOURCE_MANUAL
            else if (autoIntroEnd != null) EpisodeIntroOutroStore.SOURCE_AUTO
            else existing?.introSource ?: EpisodeIntroOutroStore.SOURCE_AUTO
            val outroSrc = if (manualOutro) EpisodeIntroOutroStore.SOURCE_MANUAL
            else if (autoOutroStart != null) EpisodeIntroOutroStore.SOURCE_AUTO
            else existing?.outroSource ?: EpisodeIntroOutroStore.SOURCE_AUTO

            EpisodeIntroOutroStore.write(
                repo = repo,
                fid = snapshot.fid,
                episodeKey = epKey,
                introEndMs = finalIntro,
                outroStartMs = finalOutro,
                source = if (introSrc == EpisodeIntroOutroStore.SOURCE_MANUAL ||
                    outroSrc == EpisodeIntroOutroStore.SOURCE_MANUAL
                ) EpisodeIntroOutroStore.SOURCE_MANUAL else EpisodeIntroOutroStore.SOURCE_AUTO,
                confidence = confidence,
                introSource = introSrc,
                outroSource = outroSrc,
            )
            LogUtils.d("IntroOutro") {
                "write fid=${snapshot.fid} introEnd=$finalIntro(src=$introSrc) " +
                    "outroStart=$finalOutro(src=$outroSrc) " +
                    "(auto intro=${autoIntroEnd ?: 0} outro=${autoOutroStart ?: 0}) conf=$confidence"
            }
    }

    /**
     * 片头结束点精修：在候选结束点 ±4 秒内找一段“静音”（明显低于片头平均能量的连续低能量区），
     * 把结束点挪到这段静音的开头。找不到就保持原值。
     */
    private fun refineIntroEnd(frames: List<Frame>, endMs: Long, startMs: Long, durationMs: Long): Long {
        val searchFrom = maxOf(0L, endMs - 4_000L)
        val searchTo = endMs + 4_000L
        val inWindow = frames.filter { it.tMs in searchFrom..searchTo }
        val before = frames.filter { it.tMs in (endMs - 20_000L)..(endMs - 5_000L) }
        if (inWindow.size < 5 || before.size < 10) return endMs

        val baseline = median(before.map { it.energy })
        if (baseline <= 0f) return endMs
        val silenceThreshold = baseline * 0.22f

        val lowStarts = ArrayList<Long>()
        var runStart: Long = -1
        var lastLow: Long = -1
        for (f in inWindow) {
            if (f.energy < silenceThreshold) {
                if (runStart < 0) runStart = f.tMs
                lastLow = f.tMs
            } else if (runStart >= 0) {
                if (lastLow - runStart >= 600L) lowStarts.add(runStart)
                runStart = -1
            }
        }
        if (runStart >= 0 && lastLow - runStart >= 600L) lowStarts.add(runStart)
        if (lowStarts.isEmpty()) return endMs

        val snapped = lowStarts.minByOrNull { kotlin.math.abs(it - endMs) } ?: return endMs
        if (snapped < INTRO_MIN_MS) return endMs
        if (snapped - startMs < INTRO_MIN_MS) return endMs
        if (durationMs > 0 && snapped > durationMs) return endMs
        LogUtils.d("IntroOutro") {
            "边界精修 ${endMs}ms -> ${snapped}ms（静音阈值 ${silenceThreshold}，基线 $baseline）"
        }
        return snapped
    }

    /** 把 [start,last] 这一段格子收成 profile；帧数不够返回 null。 */
    private fun closeRun(
        buckets: Array<FloatArray?>,
        start: Int,
        last: Int,
        stepMs: Int,
        windowStartMs: Long,
    ): EpisodeFeatureProfile? {
        val len = last - start + 1
        if (len < MIN_PROFILE_FRAMES) return null
        val out = Array(len) { buckets[start + it] ?: return null }
        return EpisodeFeatureProfile(stepMs, windowStartMs + start.toLong() * stepMs, out)
    }

    private fun median(values: List<Float>): Float {        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * 片头必须落在“开头那一小段”才算数（细则见 [IntroOutroDetector.isPlausibleIntro]）。
     */
    private fun isPlausibleIntro(startMs: Long, durationMs: Long): Boolean =
        IntroOutroDetector.isPlausibleIntro(startMs, durationMs)

    /**
     * 把不规则帧（seek 会造成空洞）对到“固定步长”的时间格上，只保留最长的一段连续区间。
     * 这样时间轴是诚实的；空洞两侧不会被硬拼成一段假音频。
     */
    private fun buildProfile(
        frames: List<Frame>,
        windowStartMs: Long,
        windowEndMs: Long,
        stepMs: Int,
        preferEarliest: Boolean = false,
    ): EpisodeFeatureProfile? {
        if (windowEndMs <= windowStartMs || stepMs <= 0) return null
        val count = ((windowEndMs - windowStartMs) / stepMs).toInt() + 1
        if (count < MIN_PROFILE_FRAMES) return null
        val buckets = arrayOfNulls<FloatArray>(count)
        frames.forEach { f ->
            val idx = ((f.tMs - windowStartMs) / stepMs).toInt()
            if (idx in 0 until count) buckets[idx] = f.chroma
        }

        // 开头窗口要的是"最早那段"而不是"最长那段"：中间卡顿留下的空洞会把 0..30s 这段切断，
        // 若按最长选，会挑到 100s 之后的段，再被"必须靠近开头"的规则整段丢掉，导致永远学不到片头。
        // 允许容忍几秒的空洞（网速不稳时音频会断一会儿），空洞用上一帧补齐，保证时间轴连续。
        if (preferEarliest) {
            val gap = (GAP_TOLERANCE_MS / stepMs).toInt().coerceAtLeast(1)
            var runStart = -1
            var lastPresent = -1
            var prev: FloatArray? = null
            for (i in 0 until count) {
                val b = buckets[i]
                if (b != null) {
                    if (runStart >= 0 && i - lastPresent > gap) {
                        closeRun(buckets, runStart, lastPresent, stepMs, windowStartMs)?.let { return it }
                        runStart = -1
                    }
                    if (runStart < 0) runStart = i
                    lastPresent = i
                    prev = b
                } else if (prev != null && lastPresent >= 0 && i - lastPresent <= gap) {
                    // 短暂断采（网速抖动）用上一帧补齐，超过容忍值就不再补，避免拼出假音频
                    buckets[i] = prev
                }
            }
            if (runStart >= 0) {
                closeRun(buckets, runStart, minOf(count - 1, lastPresent), stepMs, windowStartMs)?.let { return it }
            }
            return null
        }

        var bestStart = -1
        var bestEnd = -1
        var runStart = -1
        for (i in 0..count) {
            val present = i < count && buckets[i] != null
            if (present) {
                if (runStart < 0) runStart = i
                if (i - runStart + 1 > bestEnd - bestStart) {
                    bestStart = runStart
                    bestEnd = i + 1
                }
            } else {
                runStart = -1
            }
        }
        if (bestStart < 0 || bestEnd - bestStart < MIN_PROFILE_FRAMES) return null
        val out = Array(bestEnd - bestStart) { buckets[bestStart + it]!! }
        return EpisodeFeatureProfile(stepMs, windowStartMs + bestStart.toLong() * stepMs, out)
    }
}
