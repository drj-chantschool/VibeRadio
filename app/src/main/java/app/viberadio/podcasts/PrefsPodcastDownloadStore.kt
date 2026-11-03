package app.viberadio.podcasts

import android.annotation.SuppressLint
import android.content.Context

/** The download settings in a small preferences file. */
class PrefsPodcastDownloadStore(context: Context) : PodcastDownloadStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun readCount(): Int? = if (prefs.contains(KEY_COUNT)) prefs.getInt(KEY_COUNT, PodcastDownloadSettings.DEFAULT) else null

    override fun readLimitBytes(): Long? = if (prefs.contains(KEY_LIMIT)) prefs.getLong(KEY_LIMIT, 0L).takeIf { it > 0 } else null

    override fun readPinned(): Set<String> = prefs.getStringSet(KEY_PINNED, null)?.toSet().orEmpty()

    // Called off the main thread, one write at a time, so synchronous commits are what we want.
    @SuppressLint("ApplySharedPref")
    override fun writeCount(count: Int) {
        prefs.edit().putInt(KEY_COUNT, count).commit()
    }

    @SuppressLint("ApplySharedPref")
    override fun writeLimitBytes(bytes: Long?) {
        prefs.edit().apply { if (bytes == null) remove(KEY_LIMIT) else putLong(KEY_LIMIT, bytes) }.commit()
    }

    @SuppressLint("ApplySharedPref")
    override fun writePinned(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_PINNED, ids).commit()
    }

    private companion object {
        const val FILE = "podcast_download_settings"
        const val KEY_COUNT = "count"
        const val KEY_LIMIT = "limit_bytes"
        const val KEY_PINNED = "pinned"
    }
}
