package app.viberadio.playback

import android.content.Context

/** Where [PodcastSchedule.owed] is kept between runs of the playback service. */
interface PodcastWaitStore {
    fun read(): PodcastSchedule.Wait.Music?

    /** Null clears it: no gap is owed. */
    fun write(owed: PodcastSchedule.Wait.Music?)
}

/** [PodcastWaitStore] in a small preferences file: the gap's wall-clock times. */
class PrefsPodcastWaitStore(context: Context) : PodcastWaitStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): PodcastSchedule.Wait.Music? {
        if (!prefs.contains(KEY_NOMINAL)) return null
        return PodcastSchedule.Wait.Music(prefs.getLong(KEY_OPEN, 0), prefs.getLong(KEY_NOMINAL, 0), prefs.getLong(KEY_CLOSE, 0))
    }

    // Written from the main thread whenever the wait changes; apply() doesn't block it.
    override fun write(owed: PodcastSchedule.Wait.Music?) {
        prefs.edit().apply {
            if (owed == null) {
                clear()
            } else {
                putLong(KEY_OPEN, owed.openAt)
                putLong(KEY_NOMINAL, owed.nominalAt)
                putLong(KEY_CLOSE, owed.closeAt)
            }
        }.apply()
    }

    private companion object {
        const val FILE = "podcast_wait"
        const val KEY_OPEN = "open_at_ms"
        const val KEY_NOMINAL = "nominal_at_ms"
        const val KEY_CLOSE = "close_at_ms"
    }
}
