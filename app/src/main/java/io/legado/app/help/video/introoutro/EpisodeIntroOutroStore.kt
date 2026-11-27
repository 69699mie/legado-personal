package io.legado.app.help.video.introoutro

import io.legado.app.data.repository.VideoSettingsRepository
import org.json.JSONObject

/** 单集的片头/片尾，统一用绝对毫秒，避免“距结尾 N 秒”随集长漂移。 */
data class EpisodeIntroOutro(
    val introEndMs: Long,
    val outroStartMs: Long,
    /** 整条记录的来源，兼容老数据；逐字段来源见下两项，未单独记录时回落成它 */
    val source: String,
    val confidence: Double,
    val introSource: String = source,
    val outroSource: String = source,
)

/**
 * 集级片头片尾的读写。老数据（按“标题_S季”的秒数）留在 introOutroData 里做回退。
 *
 * 优先级约定（播放页负责按这个顺序取值）：
 * 1) 本集手动（source=manual）
 * 2) 本集自动且 confidence 达标（source=auto）
 * 3) 旧的季级手动
 * 4) 都没有 → 不跳
 */
object EpisodeIntroOutroStore {

    const val SOURCE_MANUAL = "manual"
    const val SOURCE_AUTO = "auto"

    /** 自动结果生效的最低置信度（投票比例）。只有 ≥2 集互相对上才会写入，正常都在 0.6 以上。 */
    const val MIN_AUTO_CONFIDENCE = 0.6

    fun read(repo: VideoSettingsRepository, fid: String): EpisodeIntroOutro? {
        if (fid.isBlank()) return null
        return runCatching {
            val obj = JSONObject(repo.introOutroEpisodeData).optJSONObject(fid) ?: return null
            val introEndMs = obj.optLong("introEndMs", 0L)
            val outroStartMs = obj.optLong("outroStartMs", 0L)
            if (introEndMs <= 0L && outroStartMs <= 0L) {
                null
            } else {
                val src = obj.optString("src", SOURCE_MANUAL)
                EpisodeIntroOutro(
                    introEndMs = introEndMs,
                    outroStartMs = outroStartMs,
                    source = src,
                    confidence = obj.optDouble("conf", 1.0),
                    introSource = obj.optString("isrc", src),
                    outroSource = obj.optString("osrc", src),
                )
            }
        }.getOrNull()
    }

    fun write(
        repo: VideoSettingsRepository,
        fid: String,
        episodeKey: String,
        introEndMs: Long,
        outroStartMs: Long,
        source: String,
        confidence: Double,
        introSource: String = source,
        outroSource: String = source,
    ) {
        if (fid.isBlank()) return
        runCatching {
            val json = JSONObject(repo.introOutroEpisodeData)
            if (introEndMs <= 0L && outroStartMs <= 0L) {
                json.remove(fid)
            } else {
                json.put(fid, JSONObject().apply {
                    put("introEndMs", introEndMs)
                    put("outroStartMs", outroStartMs)
                    put("ep", episodeKey)
                    put("src", source)
                    put("isrc", introSource)
                    put("osrc", outroSource)
                    put("conf", confidence)
                    put("t", System.currentTimeMillis())
                })
            }
            repo.introOutroEpisodeData = json.toString()
        }
    }

    fun remove(repo: VideoSettingsRepository, fid: String) {
        write(repo, fid, "", 0L, 0L, SOURCE_MANUAL, 1.0)
    }

    /** 删除某剧的所有集级记录（用 ep 字段里的“标题|”前缀匹配）。 */
    fun purgeSeries(repo: VideoSettingsRepository, title: String, fids: Collection<String>) {
        runCatching {
            fids.forEach { repoFid -> remove(repo, repoFid) }
            val json = JSONObject(repo.introOutroEpisodeData)
            val removeKeys = json.keys().asSequence().filter { key ->
                json.optJSONObject(key)?.optString("ep").orEmpty().startsWith("$title|")
            }.toList()
            removeKeys.forEach { json.remove(it) }
            repo.introOutroEpisodeData = json.toString()
        }
    }
}
