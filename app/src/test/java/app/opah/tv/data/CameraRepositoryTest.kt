package app.opah.tv.data

import app.opah.tv.data.model.FrigateUserProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRepositoryTest {
    private val repository = CameraRepository(FrigateJsonParsers())

    @Test
    fun `full camera access permits the configured Birdseye composite probe`() {
        val catalog = repository.catalog(
            CONFIG,
            FrigateUserProfile("admin", "admin", setOf("front", "back")),
        )

        assertTrue(catalog.fullCameraAccess)
        assertTrue(catalog.birdseye.enabled)
        assertTrue(catalog.birdseye.restreamConfigured)
        assertTrue("birdseye" in catalog.permittedStreamNames)
    }

    @Test
    fun `partial camera access never permits the all-camera Birdseye probe`() {
        val catalog = repository.catalog(
            CONFIG,
            FrigateUserProfile("viewer", "viewer", setOf("front")),
        )

        assertFalse(catalog.fullCameraAccess)
        assertFalse("birdseye" in catalog.permittedStreamNames)
        assertTrue(catalog.permittedStreamNames.containsAll(setOf("front_main", "front_sub")))
        assertFalse("back_main" in catalog.permittedStreamNames)
    }

    private companion object {
        val CONFIG = """
            {
              "birdseye": {"enabled": true, "restream": true},
              "go2rtc": {
                "streams": {
                  "front_main": {}, "front_sub": {}, "back_main": {}, "birdseye": {}
                }
              },
              "cameras": {
                "front": {
                  "live": {"streams": {"Main": "front_main", "Sub": "front_sub"}}
                },
                "back": {
                  "live": {"streams": {"Main": "back_main"}}
                }
              }
            }
        """.trimIndent()
    }
}
