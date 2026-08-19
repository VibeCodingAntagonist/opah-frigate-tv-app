package app.opah.tv

import android.app.Application

class OpahApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        StartupTrace.begin()
    }
}
