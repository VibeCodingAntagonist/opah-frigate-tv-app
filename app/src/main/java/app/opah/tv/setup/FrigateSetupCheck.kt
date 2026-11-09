package app.opah.tv.setup

import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.data.network.FrigateApiClient
import app.opah.tv.data.network.SessionCookieStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Credential checks never borrow, replace, or clear the installed connection's cookies. */
internal class FrigateSetupCheck {
    suspend fun check(profile: ConnectionProfile, password: String) {
        val jar = object : CookieJar, SessionCookieStore {
            private var cookies = emptyList<Cookie>()
            @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { this.cookies = cookies.toList() }
            @Synchronized override fun loadForRequest(url: HttpUrl) = cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            @Synchronized override fun clear() { cookies = emptyList() }
            @Synchronized override fun hasUnexpiredSession() = cookies.any { it.expiresAt > System.currentTimeMillis() }
        }
        val client = FrigateApiClient.defaultClient(jar)
        val api = FrigateApiClient(client, jar)
        try { withTimeout(10_000) { api.login(profile, password); api.getVersion(profile) } }
        finally {
            withContext(NonCancellable) { withTimeoutOrNull(2_000) { runCatching { api.logout(profile) } } }
            jar.clear(); client.connectionPool.evictAll()
        }
    }
}
