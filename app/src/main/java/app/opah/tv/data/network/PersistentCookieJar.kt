package app.opah.tv.data.network

import app.opah.tv.data.security.PersistedCookie
import app.opah.tv.data.security.SecureSessionStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

interface SessionCookieStore {
    fun clear()
    fun hasUnexpiredSession(): Boolean
}

class PersistentCookieJar(
    private val secureStore: SecureSessionStore,
) : CookieJar, SessionCookieStore {
    @Volatile
    private var sessionCookie: Cookie? = null
    @Volatile
    private var persistedSessionLoaded = false

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val candidate = cookies.firstOrNull { cookie ->
            cookie.value.count { it == '.' } == 2 || cookie.name.contains("token", ignoreCase = true)
        } ?: return

        synchronized(this) {
            persistedSessionLoaded = true
            if (candidate.expiresAt <= System.currentTimeMillis()) {
                clear()
                return
            }
            sessionCookie = candidate
            secureStore.write(candidate.toPersisted())
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensurePersistedSessionLoaded()
        val cookie = sessionCookie ?: return emptyList()
        if (cookie.expiresAt <= System.currentTimeMillis()) {
            clear()
            return emptyList()
        }
        return if (cookie.matches(url)) listOf(cookie) else emptyList()
    }

    @Synchronized
    override fun clear() {
        persistedSessionLoaded = true
        sessionCookie = null
        secureStore.clear()
    }

    override fun hasUnexpiredSession(): Boolean {
        ensurePersistedSessionLoaded()
        return sessionCookie?.expiresAt?.let { it > System.currentTimeMillis() } == true
    }

    private fun ensurePersistedSessionLoaded() {
        if (persistedSessionLoaded) return
        synchronized(this) {
            if (persistedSessionLoaded) return
            sessionCookie = secureStore.read()?.toCookie()
            persistedSessionLoaded = true
        }
    }

    private fun Cookie.toPersisted() = PersistedCookie(
        name = name,
        value = value,
        expiresAt = expiresAt,
        domain = domain,
        path = path,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
    )

    private fun PersistedCookie.toCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .expiresAt(expiresAt)
        .apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            path(path)
            if (secure) secure()
            if (httpOnly) httpOnly()
        }
        .build()
}
