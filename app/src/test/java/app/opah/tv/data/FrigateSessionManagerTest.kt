package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSearchQuery
import app.opah.tv.data.network.AuthenticationExpiredException
import app.opah.tv.data.network.FrigateGateway
import app.opah.tv.data.network.OpahErrorCode
import app.opah.tv.data.network.OpahException
import app.opah.tv.data.network.OpahFailure
import app.opah.tv.data.network.RecoveryAction
import app.opah.tv.data.network.SessionCookieStore
import app.opah.tv.data.security.SavedCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrigateSessionManagerTest {
    @Test
    fun `successful sign in persists profile and encrypted credential boundary`() = runBlocking {
        val fixture = Fixture()

        val user = fixture.manager.signIn(PROFILE, "correct horse")

        assertEquals(USER, user)
        assertEquals(PROFILE, fixture.profileStore.profile)
        assertEquals("correct horse", fixture.credentialStore.password)
        assertEquals(1, fixture.gateway.loginCount)
    }

    @Test
    fun `connection test cleans up temporary session without persisting credentials`() = runBlocking {
        val fixture = Fixture()

        val result = fixture.manager.testConnection(PROFILE, "temporary password") { user ->
            assertEquals(USER, user)
            "probe complete"
        }

        assertEquals("probe complete", result)
        assertEquals(1, fixture.gateway.loginCount)
        assertEquals(1, fixture.gateway.logoutCount)
        assertEquals(1, fixture.cookieStore.clearCount)
        assertNull(fixture.profileStore.profile)
        assertNull(fixture.credentialStore.password)
    }

    @Test
    fun `connection test cleans up temporary session when probe fails`() = runBlocking {
        val fixture = Fixture()

        runCatching {
            fixture.manager.testConnection(PROFILE, "temporary password") {
                error("probe failed")
            }
        }

        assertEquals(1, fixture.gateway.logoutCount)
        assertEquals(1, fixture.cookieStore.clearCount)
        assertNull(fixture.profileStore.profile)
        assertNull(fixture.credentialStore.password)
    }

    @Test
    fun `restore prefers valid cookie without reading password`() = runBlocking {
        val fixture = Fixture(cookieValid = true, password = "saved")

        assertEquals(USER, fixture.manager.restore(PROFILE))

        assertEquals(1, fixture.gateway.refreshCount)
        assertEquals(0, fixture.gateway.loginCount)
        assertEquals(0, fixture.credentialStore.readCount)
    }

    @Test
    fun `expired server cookie falls back to saved credential once`() = runBlocking {
        val fixture = Fixture(cookieValid = true, password = "saved")
        fixture.gateway.refreshFailure = AuthenticationExpiredException()

        assertEquals(USER, fixture.manager.restore(PROFILE))

        assertEquals(1, fixture.cookieStore.clearCount)
        assertEquals(1, fixture.gateway.loginCount)
        assertEquals("saved", fixture.gateway.lastPassword)
    }

    @Test
    fun `transient refresh failure preserves cookie and does not replay saved password`() = runBlocking {
        val fixture = Fixture(cookieValid = true, password = "saved")
        fixture.gateway.refreshFailure = OpahException(
            OpahFailure(
                code = OpahErrorCode.TIMEOUT,
                userMessage = "The Frigate connection timed out.",
                recoveryAction = RecoveryAction.RETRY,
                retryable = true,
            ),
        )

        val failure = runCatching { fixture.manager.restore(PROFILE) }.exceptionOrNull()

        assertTrue(failure is OpahException)
        assertEquals(1, fixture.gateway.refreshCount)
        assertEquals(0, fixture.gateway.loginCount)
        assertEquals(0, fixture.credentialStore.readCount)
        assertEquals(0, fixture.cookieStore.clearCount)
        assertTrue(fixture.cookieStore.valid)
    }

    @Test(expected = AuthenticationExpiredException::class)
    fun `restore without cookie or credential requires sign in`() {
        runBlocking {
            Fixture().manager.restore(PROFILE)
        }
    }

    @Test
    fun `sign out clears auto sign in but retains server unless forget requested`() = runBlocking {
        val fixture = Fixture(cookieValid = true, password = "saved", profile = PROFILE)

        fixture.manager.signOut(PROFILE, forgetServer = false)

        assertNull(fixture.credentialStore.password)
        assertEquals(PROFILE, fixture.profileStore.profile)
        assertFalse(fixture.credentialStore.allCleared)
    }

    @Test
    fun `forget server clears all persisted authentication material`() = runBlocking {
        val fixture = Fixture(cookieValid = true, password = "saved", profile = PROFILE)

        fixture.manager.signOut(PROFILE, forgetServer = true)

        assertTrue(fixture.credentialStore.allCleared)
        assertNull(fixture.profileStore.profile)
    }

    private class Fixture(
        cookieValid: Boolean = false,
        password: String? = null,
        profile: ConnectionProfile? = null,
    ) {
        val gateway = FakeGateway()
        val cookieStore = FakeCookieStore(cookieValid)
        val credentialStore = FakeCredentialStore(password)
        val profileStore = FakeProfileStore(profile)
        val manager = FrigateSessionManager(
            gateway,
            cookieStore,
            credentialStore,
            profileStore,
            Dispatchers.Unconfined,
        )
    }

    private class FakeCookieStore(var valid: Boolean) : SessionCookieStore {
        var clearCount = 0
        override fun clear() {
            valid = false
            clearCount++
        }
        override fun hasUnexpiredSession() = valid
    }

    private class FakeCredentialStore(var password: String?) : SavedCredentialStore {
        var readCount = 0
        var allCleared = false
        override fun readPassword(): String? {
            readCount++
            return password
        }
        override fun writePassword(password: String) {
            this.password = password
        }
        override fun clearPassword() {
            password = null
        }
        override fun clearAll() {
            password = null
            allCleared = true
        }
    }

    private class FakeProfileStore(var profile: ConnectionProfile?) : ConnectionProfileStore {
        override suspend fun load() = profile
        override suspend fun save(profile: ConnectionProfile) {
            this.profile = profile
        }
        override suspend fun clear() {
            profile = null
        }
    }

    private class FakeGateway : FrigateGateway {
        var loginCount = 0
        var refreshCount = 0
        var logoutCount = 0
        var lastPassword: String? = null
        var refreshFailure: Throwable? = null

        override suspend fun login(profile: ConnectionProfile, password: String): FrigateUserProfile {
            loginCount++
            lastPassword = password
            return USER
        }
        override suspend fun refreshSession(profile: ConnectionProfile): FrigateUserProfile {
            refreshCount++
            refreshFailure?.let { throw it }
            return USER
        }
        override suspend fun logout(profile: ConnectionProfile) {
            logoutCount++
        }
        override suspend fun getVersion(profile: ConnectionProfile) = error("unused")
        override suspend fun getConfig(profile: ConnectionProfile) = error("unused")
        override suspend fun getStats(profile: ConnectionProfile) = error("unused")
        override suspend fun getRecordingsStorage(profile: ConnectionProfile) = error("unused")
        override suspend fun getGo2RtcStreams(profile: ConnectionProfile) = error("unused")
        override suspend fun getGo2RtcStream(profile: ConnectionProfile, streamName: String) = error("unused")
        override suspend fun getReview(profile: ConnectionProfile, query: ReviewSearchQuery) = error("unused")
        override suspend fun getRecordings(
            profile: ConnectionProfile,
            camera: String,
            after: Double,
            before: Double,
        ) = error("unused")
        override fun reviewPlaybackUrl(profile: ConnectionProfile, item: ReviewItem) = error("unused")
    }

    private companion object {
        val PROFILE = ConnectionProfile("https://example.invalid:8971", "viewer")
        val USER = FrigateUserProfile("viewer", "admin", setOf("Front"))
    }
}
