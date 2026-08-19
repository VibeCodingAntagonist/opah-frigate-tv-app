package app.opah.tv.ui

import app.opah.tv.data.model.ReviewSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewUiStateTest {
    @Test
    fun `filters produce a bounded server query for permitted cameras`() {
        val query = ReviewFilters(
            severity = ReviewSeverity.DETECTION,
            camera = "Front",
            label = "person",
            zone = "driveway",
            timeRange = ReviewTimeRange.LAST_THREE_DAYS,
        ).toSearchQuery(
            allowedCameras = setOf("Front", "Back"),
            nowSeconds = 1_000_000.0,
        )

        assertEquals(setOf("Front"), query.cameras)
        assertEquals(ReviewSeverity.DETECTION, query.severity)
        assertEquals("person", query.label)
        assertEquals("driveway", query.zone)
        assertEquals(740_800.0, query.after)
        assertEquals(1_000_000.0, query.before)
        assertEquals(100, query.limit)
    }

    @Test
    fun `a stale unauthorized camera selection cannot escape the allow list`() {
        val query = ReviewFilters(camera = "Private").toSearchQuery(
            allowedCameras = setOf("Front"),
            nowSeconds = 100_000.0,
        )

        assertTrue(query.cameras.isEmpty())
    }
}
