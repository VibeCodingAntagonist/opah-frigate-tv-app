package app.opah.tv.data

import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

class SanitizedFixturesTest {
    @Test
    fun `derived fixtures contain no deployment addresses or secret-bearing fields`() {
        FIXTURES.forEach { name ->
            val contents = requireNotNull(javaClass.getResource("/fixtures/$name")) {
                "Missing test fixture: $name"
            }.readText()

            FORBIDDEN_PATTERNS.forEach { pattern ->
                assertFalse(
                    "$name contains forbidden fixture content matching ${pattern.pattern}",
                    pattern.containsMatchIn(contents),
                )
            }
        }

        val allFixtures = FIXTURES.joinToString("\n") { name ->
            requireNotNull(javaClass.getResource("/fixtures/$name")).readText()
        }
        val fixtureTokenDigests = Regex("[A-Za-z][A-Za-z0-9_]*")
            .findAll(allFixtures)
            .map { sha256(it.value.lowercase()) }
            .toSet()
        DEPLOYMENT_IDENTIFIER_DIGESTS.forEach { digest ->
            assertFalse(
                "Fixtures contain a deployment-specific identifier",
                digest in fixtureTokenDigests,
            )
        }
    }

    private companion object {
        val FIXTURES = listOf(
            "frigate_config_0_17_sanitized.json",
            "go2rtc_streams_sanitized.json",
            "review_items_sanitized.json",
        )

        val FORBIDDEN_PATTERNS = listOf(
            Regex("(?i)rtsp://|https?://"),
            Regex("(?i)\\b(?:10|127|169\\.254|192\\.168)\\.\\d"),
            Regex("(?i)\\b172\\.(?:1[6-9]|2\\d|3[01])\\.\\d"),
            Regex("(?i)\\\"(?:password|token|secret|pin)\\\"\\s*:"),
            Regex("(?i)authorization|set-cookie|frigate_token"),
            Regex("@"),
        )

        val DEPLOYMENT_IDENTIFIER_DIGESTS = setOf(
            "7fa3c632a2b01565c47f4c845ad80de99ef836810bc588ac62f68690958c49f7",
            "0b532e4dc655aa53b6b465ff3f613cdb1991517bbc453e652bb3e39e5e3baf00",
            "4696e7526fdecdbc636c771e4fb897476d6d2f1252e78248d17e691ce2f8ea9c",
            "ab291b0b97ac0ab9ae23b6aab3f7ee198d78e357c2991e1896971dfe0cc5810d",
            "d97b2a20a6daca8842a9e744663aeda131aad3e78ae7a9774560b2c5d76b1578",
            "c10afad998f85108589c7f6b97b22932dbd10c80a67d9011944165bf0f4915b1",
            "4d350c091df5c7dd3066828f4a07034c2aba6d23e683adc921d782897e14723c",
        )

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
