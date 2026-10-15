package app.viberadio.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/** [DiagnosticsPrefs] in a small preferences file. */
class PrefsDiagnosticsStore(context: Context) : DiagnosticsStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): DiagnosticsPrefs? {
        if (!prefs.contains(KEY_DEV_MODE)) return null
        return DiagnosticsPrefs(
            developerMode = prefs.getBoolean(KEY_DEV_MODE, false),
            folderUri = prefs.getString(KEY_FOLDER_URI, null),
            folderName = prefs.getString(KEY_FOLDER_NAME, null),
            nightly = prefs.getBoolean(KEY_NIGHTLY, false),
            lastExport = if (prefs.contains(KEY_LAST_AT)) {
                ExportOutcome(
                    atMs = prefs.getLong(KEY_LAST_AT, 0),
                    bundle = prefs.getString(KEY_LAST_BUNDLE, null),
                    savedToFolder = prefs.getBoolean(KEY_LAST_SAVED, false),
                    error = prefs.getString(KEY_LAST_ERROR, null),
                )
            } else {
                null
            },
        )
    }

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun write(prefs: DiagnosticsPrefs) {
        this.prefs.edit().apply {
            putBoolean(KEY_DEV_MODE, prefs.developerMode)
            putOrRemove(KEY_FOLDER_URI, prefs.folderUri)
            putOrRemove(KEY_FOLDER_NAME, prefs.folderName)
            putBoolean(KEY_NIGHTLY, prefs.nightly)
            val last = prefs.lastExport
            if (last == null) {
                remove(KEY_LAST_AT).remove(KEY_LAST_BUNDLE).remove(KEY_LAST_SAVED).remove(KEY_LAST_ERROR)
            } else {
                putLong(KEY_LAST_AT, last.atMs)
                putOrRemove(KEY_LAST_BUNDLE, last.bundle)
                putBoolean(KEY_LAST_SAVED, last.savedToFolder)
                putOrRemove(KEY_LAST_ERROR, last.error)
            }
        }.commit()
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private companion object {
        const val FILE = "diagnostics"
        const val KEY_DEV_MODE = "developer_mode"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_NIGHTLY = "nightly"
        const val KEY_LAST_AT = "last_export_at"
        const val KEY_LAST_BUNDLE = "last_export_bundle"
        const val KEY_LAST_SAVED = "last_export_saved"
        const val KEY_LAST_ERROR = "last_export_error"
    }
}
