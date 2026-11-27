package io.legado.app.help.video.introoutro

/**
 * 一集的音频特征（chroma 序列）。时间轴是“媒体时间”，由播放位置锚点推算。
 */
class EpisodeFeatureProfile(
    val stepMs: Int,
    val startMs: Long,
    val chroma: Array<FloatArray>,
) {
    val size: Int get() = chroma.size
    val endMs: Long get() = startMs + size.toLong() * stepMs
    fun timeMs(index: Int): Long = startMs + index.toLong() * stepMs
}

/**
 * 同季跨集比对：找两集共有的连续音频段（片头曲 / 片尾曲）。
 *
 * 纯 Kotlin，不依赖 Android，方便单测。判据保守：对不上就返回 null，宁可“不跳”，不瞎跳。
 */
object IntroOutroDetector {

    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val score: Float,
        val refFid: String,
    )

    /** 单帧余弦相似度阈值。低于这个值不算“同一段音频”。 */
    private const val SIM_THRESHOLD = 0.60f

    /** 片头不可能从太靠后的地方开始：起点最多到 3 分钟，再往后基本是正片了。 */
    const val INTRO_MAX_START_MS = 180_000L

    /**
     * 片头必须落在“开头那一小段”才算数：分钟级的相似 BGM 满剧都是，
     * 只靠长度过滤很容易把中段音乐当成片头。
     */
    fun isPlausibleIntro(startMs: Long, durationMs: Long): Boolean {
        if (startMs < 0L) return false
        val cap = if (durationMs <= 0L) INTRO_MAX_START_MS else minOf(INTRO_MAX_START_MS, durationMs / 4)
        return startMs <= cap
    }

    /** 粗粒度：3 帧合成 1 帧（约 0.56 秒），先把对齐找出来，够用且快。 */
    private const val COARSE = 3

    /**
     * 允许中间断几帧仍算同一段（换气、鼓点间隙、网速抖动漏采都算）。
     * 粗帧一格约 0.6 秒，6 格约 3.6 秒，对齐 Intro Skipper 的 MaximumTimeSkip=3.5s。
     */
    private const val MAX_GAP_FRAMES = 6

    private class Coarse(
        val stepMs: Int,
        val startMs: Long,
        val frames: Array<FloatArray>,
    )

    /**
     * 在 [target] 里找与 [ref] 共有的连续段，允许两端有最大 [maxShiftMs] 的时间错位。
     * 返回的段是 target 时间轴上的绝对区间。
     */
    fun findCommonSegment(
        refFid: String,
        ref: EpisodeFeatureProfile,
        target: EpisodeFeatureProfile,
        maxShiftMs: Int,
        minRunMs: Int,
        maxRunMs: Int,
    ): Segment? {
        if (ref.size < 8 || target.size < 8) return null
        val r = coarsen(ref)
        val t = coarsen(target)
        if (r.frames.isEmpty() || t.frames.isEmpty()) return null

        val step = t.stepMs
        val maxShiftFrames = (maxShiftMs / step).coerceAtLeast(0)
        val minRunFrames = (minRunMs / step).coerceAtLeast(2)
        val maxRunFrames = (maxRunMs / step).coerceAtLeast(minRunFrames)

        var best: Segment? = null
        var bestShift = 0
        var bestRun: IntArray? = null
        // 正负错位都试：片头可能被“前情提要”顶后，片尾可能被“下集预告”顶前
        for (shift in -maxShiftFrames..maxShiftFrames) {
            val aligned = alignedSimilarities(r, t, shift) ?: continue
            val run = longestRun(aligned.sims) ?: continue
            val runFrames = run[1] - run[0]
            if (runFrames < minRunFrames || runFrames > maxRunFrames) continue
            val startCoarse = aligned.base + run[0]
            val endCoarse = aligned.base + run[1]
            if (startCoarse < 0 || endCoarse > t.frames.size) continue

            var scoreSum = 0f
            for (i in run[0] until run[1]) scoreSum += aligned.sims[i]
            val score = scoreSum / (run[1] - run[0])
            if (score < SIM_THRESHOLD * 0.9f) continue

            val startMs = t.startMs + startCoarse.toLong() * step
            val endMs = t.startMs + endCoarse.toLong() * step
            val candidate = Segment(startMs.coerceAtLeast(0L), endMs, score, refFid)
            // 取“最像”的那一段；分数接近时取更长的那段
            val current = best
            if (current == null ||
                candidate.score > current.score + 0.03f ||
                (kotlin.math.abs(candidate.score - current.score) <= 0.03f &&
                    (candidate.endMs - candidate.startMs) > (current.endMs - current.startMs))
            ) {
                best = candidate
                bestShift = shift
                bestRun = intArrayOf(startCoarse, endCoarse)
            }
        }

        val coarseBest = best ?: return null
        val run = bestRun ?: return null
        // 粗粒度（3 帧合一）会把边界抹掉约 1~2 秒；再用原始帧把起止点收紧
        val refined = refineBoundaries(ref, target, bestShift * COARSE, run[0] * COARSE, run[1] * COARSE)
        if (refined == null) return coarseBest
        val startMs = target.startMs + refined.first.toLong() * target.stepMs
        val endMs = target.startMs + refined.second.toLong() * target.stepMs
        if (endMs - startMs < minRunMs || endMs - startMs > maxRunMs) return coarseBest
        return Segment(startMs.coerceAtLeast(0L), endMs, coarseBest.score, refFid)
    }

    /**
     * 在粗粒度定位出的区间附近，用原始帧（200ms 一格）重新找真正的起止点。
     * ref 的细帧 j 对齐 target 的细帧 i：j = i + shiftFine。
     */
    private fun refineBoundaries(
        ref: EpisodeFeatureProfile,
        target: EpisodeFeatureProfile,
        shiftFine: Int,
        coarseStartFine: Int,
        coarseEndFine: Int,
    ): Pair<Int, Int>? {
        val from = maxOf(0, coarseStartFine - COARSE)
        val to = minOf(target.size, coarseEndFine + COARSE)
        if (to - from < 4) return null

        fun simAt(i: Int): Float {
            val j = i + shiftFine
            if (j < 0 || j >= ref.size) return 0f
            return cosine(ref.chroma[j], target.chroma[i])
        }

        var s = from
        while (s + 2 < to && (simAt(s) < SIM_THRESHOLD || simAt(s + 1) < SIM_THRESHOLD || simAt(s + 2) < SIM_THRESHOLD)) s++
        var e = to - 1
        while (e - 2 > s && (simAt(e) < SIM_THRESHOLD || simAt(e - 1) < SIM_THRESHOLD || simAt(e - 2) < SIM_THRESHOLD)) e--
        if (e - s < 2) return null
        return s to (e + 1)
    }

    /**
     * 多集结论投票：同一区间（起点相差 ≤ [toleranceMs]）至少 2 票才算“稳”。
     * 返回 平均后的段 + 票数。
     */
    fun consensus(
        segments: List<Segment>,
        toleranceMs: Long = 3000L,
    ): Pair<Segment, Int>? {
        if (segments.size < 2) return null
        val groups = ArrayList<MutableList<Segment>>()
        for (seg in segments) {
            val hit = groups.firstOrNull { g ->
                val anchor = g.first()
                kotlin.math.abs(anchor.startMs - seg.startMs) <= toleranceMs
            }
            if (hit != null) hit.add(seg) else groups.add(mutableListOf(seg))
        }
        val best = groups.maxByOrNull { it.size } ?: return null
        if (best.size < 2) return null
        val avgStart = best.sumOf { it.startMs } / best.size
        val avgEnd = best.sumOf { it.endMs } / best.size
        val avgScore = best.sumOf { it.score.toDouble() } / best.size
        return Segment(avgStart, avgEnd, avgScore.toFloat(), best.first().refFid) to best.size
    }

    private fun coarsen(profile: EpisodeFeatureProfile): Coarse {
        val n = profile.size / COARSE
        val frames = Array(n) { FloatArray(12) }
        for (i in 0 until n) {
            val acc = frames[i]
            for (k in 0 until COARSE) {
                val src = profile.chroma[i * COARSE + k]
                for (d in 0 until 12) acc[d] += src[d]
            }
            // 归一化，保持余弦相似度的尺度
            var norm = 0f
            for (d in 0 until 12) norm += acc[d] * acc[d]
            norm = kotlin.math.sqrt(norm)
            if (norm > 1e-6f) {
                for (d in 0 until 12) acc[d] /= norm
            }
        }
        return Coarse(profile.stepMs * COARSE, profile.startMs, frames)
    }

    /**
     * 以 target 为基准、ref 相对平移 [shift] 个粗帧，逐帧算余弦相似度。
     * shift > 0 表示 ref 比 target 晚出现 shift×step。
     * base 是 sims[0] 对应的 target 粗帧下标，换算时间轴时要用它。
     */
    private class Aligned(val base: Int, val sims: FloatArray)

    private fun alignedSimilarities(ref: Coarse, target: Coarse, shift: Int): Aligned? {
        // ref 的第 j 帧对应 target 的第 (j - shift) 帧
        val startJ = maxOf(0, shift)
        val endJ = minOf(ref.frames.size, target.frames.size + shift)
        if (endJ - startJ < 2) return null
        val sims = FloatArray(endJ - startJ)
        for (j in startJ until endJ) {
            val ti = j - shift
            if (ti < 0 || ti >= target.frames.size) continue
            sims[j - startJ] = cosine(ref.frames[j], target.frames[ti])
        }
        return Aligned(startJ - shift, sims)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until 12) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 1e-6f || nb <= 1e-6f) return 0f
        return dot / kotlin.math.sqrt(na * nb)
    }

    /** 返回 [start, endExclusive]，允许中间断 [MAX_GAP_FRAMES] 帧。 */
    private fun longestRun(sims: FloatArray): IntArray? {
        var bestStart = -1
        var bestEnd = -1
        var runStart = -1
        var lastHit = -1
        for (i in sims.indices) {
            if (sims[i] >= SIM_THRESHOLD) {
                if (runStart < 0) runStart = i
                lastHit = i
                if (lastHit - runStart + 1 > bestEnd - bestStart) {
                    bestStart = runStart
                    bestEnd = lastHit + 1
                }
            } else if (runStart >= 0 && i - lastHit > MAX_GAP_FRAMES) {
                runStart = -1
            }
        }
        if (bestStart < 0 || bestEnd <= bestStart) return null
        return intArrayOf(bestStart, bestEnd)
    }
}
