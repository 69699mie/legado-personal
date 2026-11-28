package io.legado.app.ui.main.video

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 字幕样式分流：只有“当前选中的字幕轨是 ASS/SSA”时才整条放行内嵌样式，
 * 其余（普通文本字幕、无字幕轨）都必须走我们的基础样式，不能被 ASS 判定误伤。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VideoSubtitleRoutingTest {

    private fun textTrack(mime: String?, selected: Boolean): Tracks.Group {
        val format = Format.Builder().setSampleMimeType(mime).build()
        return Tracks.Group(
            TrackGroup(format),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(selected)
        )
    }

    private fun tracksOf(vararg groups: Tracks.Group) = Tracks(groups.toList())

    @Test
    fun selectedAssTrack_keepsEmbeddedStyle() {
        val tracks = tracksOf(textTrack(MimeTypes.TEXT_SSA, true))
        assertTrue(isAssSubtitleSelected(tracks))
    }

    @Test
    fun selectedPlainTextTrack_usesBaseStyle() {
        val tracks = tracksOf(textTrack(MimeTypes.TEXT_VTT, true))
        assertFalse(isAssSubtitleSelected(tracks))
    }

    @Test
    fun assTrackNotSelected_doesNotCount() {
        val tracks = tracksOf(
            textTrack(MimeTypes.TEXT_SSA, false),
            textTrack(MimeTypes.TEXT_VTT, true)
        )
        assertFalse(isAssSubtitleSelected(tracks))
    }

    @Test
    fun noTextTracks_usesBaseStyle() {
        assertFalse(isAssSubtitleSelected(Tracks(emptyList())))
    }

    @Test
    fun assTrackNotSelectedAtAll_usesBaseStyle() {
        val tracks = tracksOf(textTrack(MimeTypes.TEXT_SSA, false))
        assertFalse(isAssSubtitleSelected(tracks))
    }
}
