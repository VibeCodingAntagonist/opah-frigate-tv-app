package app.opah.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraImageRepositoryTest {
    @Test
    fun `sample size bounds large camera images before final scaling`() {
        assertEquals(4, cameraImageSampleSize(sourceHeight = 2160, targetHeight = 360))
        assertEquals(2, cameraImageSampleSize(sourceHeight = 720, targetHeight = 360))
        assertEquals(1, cameraImageSampleSize(sourceHeight = 300, targetHeight = 360))
        assertEquals(1, cameraImageSampleSize(sourceHeight = -1, targetHeight = 360))
    }

    @Test
    fun `final scale preserves landscape and portrait aspect ratios`() {
        assertEquals(640, cameraImageScaledWidth(1920, 1080, 360))
        assertEquals(202, cameraImageScaledWidth(2160, 3840, 360))
        assertEquals(1, cameraImageScaledWidth(0, 0, 360))
    }
}
