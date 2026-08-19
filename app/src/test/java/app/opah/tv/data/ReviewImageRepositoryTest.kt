package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewImageRepositoryTest {
    @Test
    fun `review media paths map to authenticated static thumbnail urls`() {
        val url = reviewThumbnailUrl(
            PROFILE,
            "/media/frigate/clips/review/2026-08-17/front/alert.webp",
        )

        assertEquals(
            "https://frigate.example:8971/clips/review/2026-08-17/front/alert.webp",
            url.toString(),
        )
    }

    @Test
    fun `review thumbnail url rejects missing foreign and traversal paths`() {
        assertNull(reviewThumbnailUrl(PROFILE, null))
        assertNull(reviewThumbnailUrl(PROFILE, "/api/front/latest.jpg"))
        assertNull(reviewThumbnailUrl(PROFILE, "/media/frigate/clips/review/../private.webp"))
        assertNull(reviewThumbnailUrl(PROFILE, "/media/frigate/clips/review/folder\\private.webp"))
    }

    private companion object {
        val PROFILE = ConnectionProfile("https://frigate.example:8971", "viewer")
    }
}
