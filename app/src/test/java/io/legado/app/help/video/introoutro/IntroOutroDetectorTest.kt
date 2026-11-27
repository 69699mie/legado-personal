package io.legado.app.help.video.introoutro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 只测纯算法：同季两集“同一段片头曲/片尾曲”能不能对上，以及不同音频会不会被误判。
 * 真实音频样本不进单测（仓库不放二进制约片），这里用可复现的合成信号。
 */
class IntroOutroDetectorTest {

    private val stepMs = 200

    private fun unit(bins: IntArray): FloatArray {
        val v = FloatArray(12)
        bins.forEach { v[it] += 1f }
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        if (n > 0f) for (i in v.indices) v[i] /= n
        return v
    }

    /** 一段“片头曲”：10 帧循环的固定音型，循环才会被当成同一段音频。 */
    private val motif = Array(10) { i ->
        unit(intArrayOf(i % 12, (i * 3 + 4) % 12, (i * 5 + 7) % 12))
    }

    /** 背景（正片对白+配乐）：每帧随机几个音高，两集之间不相关。 */
    private fun background(rnd: Random): FloatArray {
        val bins = IntArray(1 + rnd.nextInt(3)) { rnd.nextInt(12) }
        return unit(bins)
    }

    private fun profile(
        frames: Int,
        startMs: Long,
        themeFromFrame: Int = -1,
        themeFrames: Int = 0,
        seed: Int,
    ): EpisodeFeatureProfile {
        val rnd = Random(seed)
        val out = Array(frames) { i ->
            if (themeFromFrame >= 0 && i in themeFromFrame until (themeFromFrame + themeFrames)) {
                motif[(i - themeFromFrame) % motif.size]
            } else {
                background(rnd)
            }
        }
        return EpisodeFeatureProfile(stepMs, startMs, out)
    }

    @Test
    fun `finds the shared intro even when it starts later in one episode`() {
        val ref = profile(frames = 3000, startMs = 0L, themeFromFrame = 300, themeFrames = 200, seed = 1)   // 60s~100s
        val target = profile(frames = 3000, startMs = 0L, themeFromFrame = 450, themeFrames = 200, seed = 2) // 90s~130s

        val seg = IntroOutroDetector.findCommonSegment(
            refFid = "ref",
            ref = ref,
            target = target,
            maxShiftMs = 45_000,
            minRunMs = 20_000,
            maxRunMs = 150_000,
        )

        assertNotNull(seg)
        assertEquals(90_000.0, seg!!.startMs.toDouble(), 1_200.0)
        assertEquals(130_000.0, seg.endMs.toDouble(), 1_200.0)
    }

    @Test
    fun `finds the shared outro in the tail window`() {
        // 结尾 8 分钟窗口：ref 的片尾曲在 6:00 起，target 因为集更长，片尾曲在 7:00 起
        val ref = profile(frames = 2400, startMs = 60_000L, themeFromFrame = 800, themeFrames = 250, seed = 3)
        val target = profile(frames = 2400, startMs = 90_000L, themeFromFrame = 1100, themeFrames = 250, seed = 4)

        val seg = IntroOutroDetector.findCommonSegment(
            refFid = "ref",
            ref = ref,
            target = target,
            maxShiftMs = 420_000,
            minRunMs = 30_000,
            maxRunMs = 300_000,
        )

        assertNotNull(seg)
        assertEquals(90_000.0 + 1100 * stepMs, seg!!.startMs.toDouble(), 1_500.0)
    }

    @Test
    fun `does not invent a match for unrelated audio`() {
        val ref = profile(frames = 2400, startMs = 0L, seed = 5)
        val target = profile(frames = 2400, startMs = 0L, seed = 6)

        val seg = IntroOutroDetector.findCommonSegment(
            refFid = "ref",
            ref = ref,
            target = target,
            maxShiftMs = 30_000,
            minRunMs = 20_000,
            maxRunMs = 150_000,
        )
        assertNull(seg)
    }

    @Test
    fun `consensus needs at least two agreeing episodes`() {
        val single = listOf(IntroOutroDetector.Segment(100_000, 160_000, 0.9f, "a"))
        assertNull(IntroOutroDetector.consensus(single))

        val agree = listOf(
            IntroOutroDetector.Segment(100_000, 160_000, 0.9f, "a"),
            IntroOutroDetector.Segment(101_000, 161_000, 0.85f, "b"),
        )
        val out = IntroOutroDetector.consensus(agree)
        assertNotNull(out)
        assertEquals(2, out!!.second)
        assertEquals(100_500.0, out.first.startMs.toDouble(), 1.0)
    }

    @Test
    fun `intro must start near the beginning of the episode`() {
        // 27 分钟的集：中段（5:45）出现一段相似音频，不能当片头
        assertEquals(false, IntroOutroDetector.isPlausibleIntro(345_000L, 1_635_140L))
        // 正常片头（39s 起）
        assertEquals(true, IntroOutroDetector.isPlausibleIntro(39_000L, 1_635_140L))
        // 时长未知时按固定 3 分钟上限
        assertEquals(true, IntroOutroDetector.isPlausibleIntro(150_000L, 0L))
        assertEquals(false, IntroOutroDetector.isPlausibleIntro(200_000L, 0L))
        // 很短的集（4 分钟）按 1/4 时长收紧
        assertEquals(false, IntroOutroDetector.isPlausibleIntro(80_000L, 240_000L))
    }
}
