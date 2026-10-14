package app.viberadio.dial

import android.annotation.SuppressLint
import android.content.Context

/** The user's channel names in a small preferences file, one entry per renamed channel. The only Android code in `dial`. */
class PrefsChannelNameStore(context: Context) : ChannelNameStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): Map<String, String> =
        prefs.all.mapNotNull { (id, name) -> (name as? String)?.let { id to it } }.toMap()

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun write(custom: Map<String, String>) {
        val editor = prefs.edit().clear()
        custom.forEach { (id, name) -> editor.putString(id, name) }
        editor.commit()
    }

    private companion object {
        const val FILE = "channel_names"
    }
}
