package io.legado.app.help.video.introoutro

import org.json.JSONObject
import splitties.init.appCtx
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 音频特征落盘：一集两个窗口（开头 4 分钟 / 结尾 5 分钟），供其它集比对。
 *
 * 不放在 SharedPreferences 里：单集 200KB 上下，塞进备份会把备份撑爆。
 * 这个目录只当“学习用的临时资料”，清缓存/重装后重新采集即可，不进备份。
 */
object EpisodeAudioStore {

    private const val MAGIC = 0x56494F31
    private const val MAGIC_TAIL = 0x56494F32
    private const val MAX_FILES = 400
    /** 两个窗口起点/终点差在这以内，视为同一个位置，改用“帧更多”的那个。 */
    private const val SAME_POSITION_MS = 5_000L

    private val dir: File by lazy {
        File(appCtx.filesDir, "introoutro").apply { if (!exists()) mkdirs() }
    }
    private val indexFile: File by lazy { File(dir, "index.json") }

    data class Meta(
        val key: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val updatedAt: Long,
        val ver: Int = 1,
    )

    /**
     * 基线版本：v2 起“开头窗口只在从头起播时采集”。
     * 老版本（v1）的窗口可能是从中间续播采到的中段音频，作为参考集会污染比对，
     * 读索引时直接不认，让它自然被重新采集覆盖——老用户不用手动清缓存。
     */
    private const val BASELINE_VERSION = 2

    fun save(fid: String, meta: Meta, head: EpisodeFeatureProfile?, tail: EpisodeFeatureProfile?) {
        if (head == null && tail == null) return
        runCatching {
            val file = File(dir, "$fid.feat")
            val tmp = File(dir, "$fid.tmp")
            // 同一集可能被看第二遍：从中间续播时窗口只覆盖中段，
            // 若直接覆盖，就会把上次那份“从 0 开始的完整开头窗口”弄丢。
            val old = load(fid)
            val mergedHead = pickHead(old?.first, head)
            val mergedTail = pickTail(old?.second, tail)
            if (mergedHead == null && mergedTail == null) return@runCatching
            BufferedOutputStream(tmp.outputStream()).use { bos ->
                DataOutputStream(bos).use { out ->
                    out.writeInt(MAGIC)
                    writeProfile(out, mergedHead)
                    out.writeInt(MAGIC_TAIL)
                    writeProfile(out, mergedTail)
                }
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            updateIndex(fid, meta.copy(ver = BASELINE_VERSION))
            prune()
        }
    }

    fun load(fid: String): Pair<EpisodeFeatureProfile?, EpisodeFeatureProfile?>? {
        val file = File(dir, "$fid.feat")
        if (!file.exists()) return null
        return runCatching {
            BufferedInputStream(file.inputStream()).use { bis ->
                DataInputStream(bis).use { input ->
                    if (input.readInt() != MAGIC) return null
                    val head = readProfile(input)
                    if (input.readInt() != MAGIC_TAIL) return null
                    val tail = readProfile(input)
                    head to tail
                }
            }
        }.getOrNull()
    }

    /** 同季其它集（最多 4 集），按更新时间从新到旧。 */
    fun loadSiblings(key: String, excludeFid: String, limit: Int = 4): List<Triple<String, EpisodeFeatureProfile?, EpisodeFeatureProfile?>> {
        val index = readIndex()
        val candidates = index.entries
            .filter { it.value.key == key && it.key != excludeFid && it.value.ver >= BASELINE_VERSION }
            .sortedByDescending { it.value.updatedAt }
            .take(limit)
        val out = ArrayList<Triple<String, EpisodeFeatureProfile?, EpisodeFeatureProfile?>>()
        candidates.forEach { entry ->
            val profiles = load(entry.key) ?: return@forEach
            out.add(Triple(entry.key, profiles.first, profiles.second))
        }
        return out
    }

    /**
     * 片头窗口合并：要“更靠开头、覆盖更长”。
     * 起点差 5 秒以内算同一个位置，取帧更多的；否则取起点更小的。
     */
    internal fun pickHead(old: EpisodeFeatureProfile?, new: EpisodeFeatureProfile?): EpisodeFeatureProfile? {
        if (old == null) return new
        if (new == null) return old
        return if (Math.abs(old.startMs - new.startMs) <= SAME_POSITION_MS) {
            if (new.size > old.size) new else old
        } else if (new.startMs < old.startMs) new else old
    }

    /**
     * 片尾窗口合并：要“更贴结尾、覆盖更长”。
     * 终点差 5 秒以内算同一个位置，取起点更小（即更长）的；否则取终点更大（更贴近结尾）的。
     */
    internal fun pickTail(old: EpisodeFeatureProfile?, new: EpisodeFeatureProfile?): EpisodeFeatureProfile? {
        if (old == null) return new
        if (new == null) return old
        return if (Math.abs(endMs(old) - endMs(new)) <= SAME_POSITION_MS) {
            if (new.startMs < old.startMs) new else old
        } else if (endMs(new) > endMs(old)) new else old
    }

    private fun endMs(profile: EpisodeFeatureProfile): Long =
        profile.startMs + profile.size.toLong() * profile.stepMs

    fun removeSeries(titlePrefix: String) {
        runCatching {
            val index = readIndex()
            val remove = index.entries.filter { it.value.title == titlePrefix }.map { it.key }
            remove.forEach {
                File(dir, "$it.feat").delete()
                index.remove(it)
            }
            writeIndex(index)
        }
    }

    /** 清空全部学习资料（测试钩子用；正式版不需要，靠 BASELINE_VERSION 自愈）。 */
    fun clearAll(): Int {
        var n = 0
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".feat") || f.name == "index.json") {
                    if (f.delete()) n++
                }
            }
        }
        return n
    }

    private fun writeProfile(out: DataOutputStream, profile: EpisodeFeatureProfile?) {
        if (profile == null) {
            out.writeInt(0)
            out.writeLong(0L)
            out.writeInt(0)
            return
        }
        out.writeInt(profile.stepMs)
        out.writeLong(profile.startMs)
        out.writeInt(profile.size)
        for (frame in profile.chroma) {
            for (i in 0 until 12) out.writeFloat(frame[i])
        }
    }

    private fun readProfile(input: DataInputStream): EpisodeFeatureProfile? {
        val stepMs = input.readInt()
        val startMs = input.readLong()
        val count = input.readInt()
        if (stepMs <= 0 || count <= 0) return null
        val frames = Array(count) { FloatArray(12) }
        for (i in 0 until count) {
            for (d in 0 until 12) frames[i][d] = input.readFloat()
        }
        return EpisodeFeatureProfile(stepMs, startMs, frames)
    }

    private fun readIndex(): MutableMap<String, Meta> {
        val out = HashMap<String, Meta>()
        runCatching {
            if (!indexFile.exists()) return out
            val json = JSONObject(indexFile.readText())
            json.keys().forEach { fid ->
                val obj = json.optJSONObject(fid) ?: return@forEach
                out[fid] = Meta(
                    key = obj.optString("key"),
                    title = obj.optString("title"),
                    season = obj.optInt("season", 1),
                    episode = obj.optInt("ep", 0),
                    updatedAt = obj.optLong("t", 0L),
                    ver = obj.optInt("ver", 1),
                )
            }
        }
        return out
    }

    private fun writeIndex(index: Map<String, Meta>) {
        val json = JSONObject()
        index.forEach { (fid, meta) ->
            json.put(fid, JSONObject().apply {
                put("key", meta.key)
                put("title", meta.title)
                put("season", meta.season)
                put("ep", meta.episode)
                put("t", meta.updatedAt)
                put("ver", meta.ver)
            })
        }
        indexFile.writeText(json.toString())
    }

    private fun updateIndex(fid: String, meta: Meta) {
        val index = readIndex()
        index[fid] = meta
        writeIndex(index)
    }

    /** 只保留最近 [MAX_FILES] 集，避免无限增长。 */
    private fun prune() {
        val index = readIndex()
        if (index.size <= MAX_FILES) return
        val stale = index.entries.sortedBy { it.value.updatedAt }.take(index.size - MAX_FILES)
        stale.forEach {
            File(dir, "${it.key}.feat").delete()
            index.remove(it.key)
        }
        writeIndex(index)
    }
}
