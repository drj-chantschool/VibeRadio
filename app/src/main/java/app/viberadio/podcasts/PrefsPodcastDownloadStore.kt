package app.viberadio.podcasts

import android.annotation.SuppressLint
import android.content.Context

/** The download count in a small preferences file. */
class PrefsPodcastDownloadStore(context: Context) : PodcastDownloadStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): Int? = if (prefs.contains(KEY)) prefs.getInt(KEY, PodcastDownloadSettings.DEFAULT) else null

    // Called off the main thread, one write at a time.
    @SuppressLint("ApplySharedPref")
    override fun write(count: Int) {
        prefs.edit().putInt(KEY, count).commit()
    }

    private companion object {
        const val FILE = "podcast_download_settings"
        const val KEY = "count"
    }
}
