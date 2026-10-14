package app.viberadio.bluetooth

import android.annotation.SuppressLint
import android.content.Context

/** The Bluetooth settings in a small preferences file. */
class PrefsBluetoothSettingsStore(context: Context) : BluetoothSettingsStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun readResumeOnConnect(): Boolean? =
        if (prefs.contains(KEY_RESUME)) prefs.getBoolean(KEY_RESUME, BluetoothSettings.DEFAULT_RESUME_ON_CONNECT) else null

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun writeResumeOnConnect(on: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME, on).commit()
    }

    private companion object {
        const val FILE = "bluetooth_settings"
        const val KEY_RESUME = "resume_on_connect"
    }
}
