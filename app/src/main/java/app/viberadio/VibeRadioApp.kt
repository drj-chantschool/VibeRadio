package app.viberadio

import android.app.Application
import app.viberadio.diagnostics.CrashExporter
import app.viberadio.diagnostics.CrashRecorder

class VibeRadioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while the container starts is recorded too.
        val crashes = CrashRecorder.forApp(this)
        crashes.install()
        // Before anything else loads, so a crash on launch still reaches the user.
        CrashExporter(this, crashes).exportAndClear()
        container = AppContainer(this)
    }
}
