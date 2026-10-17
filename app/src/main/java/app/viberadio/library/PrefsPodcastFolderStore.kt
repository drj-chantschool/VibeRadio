package app.viberadio.library

import android.annotation.SuppressLint
import android.content.Context

/** The podcast folder in the library folders' preferences file. An empty string is "no folder". */
class PrefsPodcastFolderStore(context: Context) : PodcastFolderStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): PodcastFolder? =
        if (prefs.contains(KEY)) PodcastFolder.of(prefs.getString(KEY, null)) else null

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun write(folder: PodcastFolder) {
        prefs.edit().putString(KEY, folder.path.orEmpty()).commit()
    }

    private companion object {
        const val FILE = "library_folders"
        const val KEY = "podcast_folder"
    }
}
