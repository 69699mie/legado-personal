package io.legado.app.help.video.introoutro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 同一集被看第二遍时，窗口要合并而不是覆盖（重看从中间续播不能弄丢开头窗口）。 */
class EpisodeAudioStoreMergeTest {

    private fun profile(startMs: Long, frames: Int) =
        EpisodeFeatureProfile(200, startMs, Array(frames) { FloatArray(12) { 0.5f } })

    @Test
    fun `rewatch from middle keeps earlier head window`() {
        val full = profile(0L, 834)          // 上次从 0 看，开头 0~166.8s
        val middle = profile(252_000L, 478)  // 这次从 04:12 续播，只采到中段
        val picked = EpisodeAudioStore.pickHead(full, middle)
        assertSame(full, picked)
    }

    @Test
    fun `head from zero replaces middle-only window`() {
        val middle = profile(252_000L, 478)
        val fromZero = profile(0L, 600)
        val picked = EpisodeAudioStore.pickHead(middle, fromZero)
        assertSame(fromZero, picked)
    }

    @Test
    fun `head at same position prefers longer`() {
        val short = profile(2_000L, 300)
        val long = profile(0L, 834)
        assertSame(long, EpisodeAudioStore.pickHead(short, long))
    }

    @Test
    fun `tail prefers window closer to the end`() {
        val early = profile(1_200_000L, 500) // 结尾窗口的早期部分
        val late = profile(1_400_000L, 400)
        assertSame(late, EpisodeAudioStore.pickTail(early, late))
    }

    @Test
    fun `tail at same end prefers longer`() {
        val wide = profile(1_100_000L, 900)
        val narrow = profile(1_500_000L, 300)
        // wide 终点 1290s，narrow 终点 1560s：差得多，取更贴结尾的 narrow
        assertSame(narrow, EpisodeAudioStore.pickTail(wide, narrow))
        assertEquals(1_560_000L, narrow.endMs)
    }

    @Test
    fun `missing window keeps the old one`() {
        val old = profile(0L, 834)
        assertSame(old, EpisodeAudioStore.pickHead(old, null))
        assertSame(old, EpisodeAudioStore.pickHead(null, old))
    }
}
