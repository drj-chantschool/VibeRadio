package app.viberadio.playback

import android.annotation.SuppressLint
import android.content.Context

/**
 * [PodcastGapSettings] in a small preferences file: the chosen rule, and `<rule>.<param>` for
 * every parameter, as text so a figure reads back exactly.
 */
class PrefsPodcastGapStore(context: Context) : PodcastGapStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): PodcastGapSettings? {
        val selected = prefs.getString(KEY_RULE, null) ?: return null
        val rules = PodcastGapRule.all.map { rule ->
            rule.params.fold(rule) { acc, param ->
                val key = "${rule.id}.${param.key}"
                prefs.getString(key, null)?.toDoubleOrNull()?.let { acc.with(param.key, it) } ?: acc
            }
        }
        return PodcastGapSettings(selected, rules)
    }

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun write(settings: PodcastGapSettings) {
        prefs.edit().apply {
            putString(KEY_RULE, settings.selected)
            for (rule in settings.rules) {
                for (param in rule.params) putString("${rule.id}.${param.key}", rule[param.key].toString())
            }
        }.commit()
    }

    private companion object {
        const val FILE = "podcast_gap"
        const val KEY_RULE = "rule"
    }
}
