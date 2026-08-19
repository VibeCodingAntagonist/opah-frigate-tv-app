package app.opah.tv.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun `redacts structured credentials and preserves useful context`() {
        val input = """POST /login {"user":"viewer","password":"correct horse battery staple"}"""
        val output = SecretRedactor.redact(input)

        assertFalse(output.contains("correct horse battery staple"))
        assertFalse(output.contains("battery"))
        assertTrue(output.contains("password"))
        assertTrue(output.contains("<redacted>"))
    }

    @Test
    fun `redacts bearer token query secret and URL user info`() {
        val input = "https://viewer:sample-passphrase@example.test/api?token=query-secret Authorization=Bearer abc.def.ghi"
        val output = SecretRedactor.redact(input)

        listOf("sample-passphrase", "query-secret", "abc.def.ghi").forEach { secret ->
            assertFalse("Leaked $secret in $output", output.contains(secret))
        }
        assertTrue(output.contains("example.test"))
    }

    @Test
    fun `redacts jwt even without a field label`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature"

        assertFalse(SecretRedactor.redact("failure $jwt").contains(jwt))
    }

    @Test
    fun `throwable summary excludes secret`() {
        val result = SecretRedactor.safeThrowableSummary(
            IllegalStateException("request failed: password=hunter2"),
        )

        assertTrue(result.startsWith("IllegalStateException"))
        assertFalse(result.contains("hunter2"))
    }

    @Test
    fun `cookie header redacts every cookie value`() {
        val output = SecretRedactor.redact("Cookie: session=first; csrf=second")

        assertFalse(output.contains("first"))
        assertFalse(output.contains("second"))
        assertTrue(output.contains("Cookie:"))
    }
}
