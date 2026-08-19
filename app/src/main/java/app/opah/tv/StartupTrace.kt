package app.opah.tv

import android.os.Build
import android.os.Trace

/** Perfetto-visible process startup span used by the release-like Macrobenchmark. */
internal object StartupTrace {
    const val SECTION_NAME = "OpahStartupToUsable"
    private const val COOKIE = 1

    @Volatile
    private var active = false

    fun begin() {
        if (Build.VERSION.SDK_INT < 29 || active) return
        active = true
        Trace.beginAsyncSection(SECTION_NAME, COOKIE)
    }

    fun end() {
        if (Build.VERSION.SDK_INT < 29 || !active) return
        Trace.endAsyncSection(SECTION_NAME, COOKIE)
        active = false
    }
}
