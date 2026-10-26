package app.viberadio

import android.app.Application
import app.viberadio.diagnostics.CrashRecorder

class VibeRadioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while the container starts is recorded too.
        CrashRecorder.forApp(this).install()
        container = AppContainer(this)
    }
}
