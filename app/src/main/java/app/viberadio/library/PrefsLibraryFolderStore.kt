package app.viberadio.library

import android.annotation.SuppressLint
import android.content.Context

/** The left-out folders in a small preferences file. */
class PrefsLibraryFolderStore(context: Context) : LibraryFolderStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): FolderExclusions = FolderExclusions(
        subtrees = prefs.getStringSet(KEY_SUBTREES, null).orEmpty().toSet(),
        filesOnly = prefs.getStringSet(KEY_FILES_ONLY, null).orEmpty().toSet(),
    )

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun write(exclusions: FolderExclusions) {
        prefs.edit()
            .putStringSet(KEY_SUBTREES, exclusions.subtrees.toSet())
            .putStringSet(KEY_FILES_ONLY, exclusions.filesOnly.toSet())
            .commit()
    }

    private companion object {
        const val FILE = "library_folders"
        const val KEY_SUBTREES = "excluded_subtrees"
        const val KEY_FILES_ONLY = "excluded_files_only"
    }
}
