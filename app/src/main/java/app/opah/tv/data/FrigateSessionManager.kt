package app.opah.tv.data

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.model.FrigateUserProfile
import app.opah.tv.data.network.AuthenticationExpiredException
import app.opah.tv.data.network.FrigateGateway
import app.opah.tv.data.network.SessionCookieStore
import app.opah.tv.data.security.SavedCredentialStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the complete authentication lifecycle and persistence boundaries. */
class FrigateSessionManager(
    private val gateway: FrigateGateway,
    private val cookieStore: SessionCookieStore,
    private val credentialStore: SavedCredentialStore,
    private val profileStore: ConnectionProfileStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun <T> testConnection(
        profile: ConnectionProfile,
        password: String,
        test: suspend (FrigateUserProfile) -> T,
    ): T {
        val user = gateway.login(profile, password)
        return try {
            test(user)
        } finally {
            runCatching { gateway.logout(profile) }
            cookieStore.clear()
        }
    }

    suspend fun signIn(
        profile: ConnectionProfile,
        password: String,
        rememberCredential: Boolean = true,
    ): FrigateUserProfile {
        val user = gateway.login(profile, password)
        profileStore.save(profile)
        withContext(ioDispatcher) {
            if (rememberCredential) credentialStore.writePassword(password)
            else credentialStore.clearPassword()
        }
        return user
    }

    suspend fun restore(profile: ConnectionProfile): FrigateUserProfile {
        if (cookieStore.hasUnexpiredSession()) {
            try {
                return gateway.refreshSession(profile)
            } catch (_: AuthenticationExpiredException) {
                cookieStore.clear()
            }
        }
        val savedPassword = withContext(ioDispatcher) { credentialStore.readPassword() }
            ?: throw AuthenticationExpiredException(
                "No saved Frigate session is available. Sign in again.",
            )
        return signIn(profile, savedPassword, rememberCredential = true)
    }

    suspend fun signOut(profile: ConnectionProfile?, forgetServer: Boolean) {
        if (profile != null) runCatching { gateway.logout(profile) }
        cookieStore.clear()
        withContext(ioDispatcher) {
            if (forgetServer) credentialStore.clearAll() else credentialStore.clearPassword()
        }
        if (forgetServer) profileStore.clear()
    }

    fun expireSession() {
        cookieStore.clear()
    }

    suspend fun hasSavedCredential(): Boolean =
        withContext(ioDispatcher) { credentialStore.readPassword() != null }

    suspend fun clearSavedCredential() {
        withContext(ioDispatcher) { credentialStore.clearPassword() }
    }
}
