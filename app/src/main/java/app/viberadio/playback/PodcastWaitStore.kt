package app.viberadio.playback

import android.content.Context

/** Where [PodcastSchedule.saved] is kept between runs of the playback service. */
interface PodcastWaitStore {
    fun read(): PodcastSchedule.Saved?

    /** Null clears it: no gap is owed. */
    fun write(saved: PodcastSchedule.Saved?)
}

/** [PodcastWaitStore] in a small preferences file. */
class PrefsPodcastWaitStore(context: Context) : PodcastWaitStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): PodcastSchedule.Saved? {
        if (!prefs.contains(KEY_NOMINAL)) return null
        return PodcastSchedule.Saved(
            openInMs = prefs.getLong(KEY_OPEN, 0),
            nominalInMs = prefs.getLong(KEY_NOMINAL, 0),
            closeInMs = prefs.getLong(KEY_CLOSE, 0),
        )
    }

    // Written from the main thread every few seconds while playing; apply() doesn't block it.
    override fun write(saved: PodcastSchedule.Saved?) {
        prefs.edit().apply {
            if (saved == null) {
                clear()
            } else {
                putLong(KEY_OPEN, saved.openInMs)
                putLong(KEY_NOMINAL, saved.nominalInMs)
                putLong(KEY_CLOSE, saved.closeInMs)
            }
        }.apply()
    }

    private companion object {
        const val FILE = "podcast_wait"
        const val KEY_OPEN = "open_in_ms"
        const val KEY_NOMINAL = "nominal_in_ms"
        const val KEY_CLOSE = "close_in_ms"
    }
}
