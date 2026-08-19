package app.opah.tv

import app.opah.tv.playback.PlaybackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPicturePolicyTest {
    @Test
    fun `TV picture in picture requires API 34 feature live playback and first frame`() {
        assertFalse(shouldOfferLivePictureInPicture(33, true, PlaybackKind.LIVE, true))
        assertFalse(shouldOfferLivePictureInPicture(34, false, PlaybackKind.LIVE, true))
        assertFalse(shouldOfferLivePictureInPicture(34, true, PlaybackKind.RECORDED, true))
        assertFalse(shouldOfferLivePictureInPicture(34, true, PlaybackKind.LIVE, false))
        assertTrue(shouldOfferLivePictureInPicture(34, true, PlaybackKind.LIVE, true))
    }

    @Test
    fun `common landscape and portrait camera aspect ratios are preserved`() {
        assertEquals(PipAspectRatio(1920, 1080), pipAspectRatio(1920, 1080))
        assertEquals(PipAspectRatio(2160, 3840), pipAspectRatio(2160, 3840))
    }

    @Test
    fun `invalid or unsupported extreme aspect ratios are omitted`() {
        assertNull(pipAspectRatio(0, 1080))
        assertNull(pipAspectRatio(1080, 0))
        assertNull(pipAspectRatio(100, 1000))
        assertNull(pipAspectRatio(1000, 100))
    }
}
