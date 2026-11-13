package app.viberadio.dial

import app.viberadio.channels.ChannelGenerator
import app.viberadio.podcasts.PodcastChannels

/**
 * The named channels: five moods, a shuffle of every song, and a podcast-only channel.
 *
 * IDs are stable and never shown; names are the user's to change (settings → Channels).
 * The Shuffle and The Voice reuse the IDs of the channels they name ("All songs" and the
 * podcast-only channel), so podcast assignments and live-radio state carry over.
 *
 * The five mood channels are the slots [ChannelGenerator] fills: its generated channels take
 * them in order, songs unchanged, until learning (#14/#15) decides what goes where. A slot
 * with nothing on it stays off the dial.
 */
object ChannelLineup {
    const val BREEZE = "breeze"
    const val BUZZ = "buzz"
    const val ZONE = "zone"
    const val BREATH = "breath"
    const val HALL = "hall"
    const val SHUFFLE = ChannelGenerator.ALL_SONGS_ID
    const val VOICE = PodcastChannels.PODCASTS_ID

    /** The slots for generated song channels, in dial order. */
    val moods: List<String> = listOf(BREEZE, BUZZ, ZONE, BREATH, HALL)

    /** Every named channel, in settings order. */
    val ids: List<String> = listOf(BREEZE, BUZZ, ZONE, BREATH, HALL, SHUFFLE, VOICE)

    /**
     * The short tag each named channel goes by while it is the primary stream in the title a
     * Bluetooth device shows ([ChannelNames.shortOf]). A renamed channel's tag follows its new name.
     */
    val shortNames: Map<String, String> = mapOf(
        BREEZE to "V-BRZ",
        BUZZ to "V-BUZ",
        ZONE to "V-ZNE",
        BREATH to "V-BRH",
        HALL to "V-HAL",
        SHUFFLE to "V-SHF",
        VOICE to "V-OIC",
    )
}

/** Short channel tags for places with no room for a name, like a car's one-line display. */
object ChannelShortNames {
    private const val PREFIX = "V-"
    private const val LENGTH = 3

    /** "V-" and the first three letters or digits of [name] without a leading "The", uppercased. */
    fun derive(name: String): String {
        val core = name.trim().let { if (it.startsWith("the ", ignoreCase = true)) it.substring(4) else it }
        val letters = core.filter(Char::isLetterOrDigit).take(LENGTH).uppercase()
        return if (letters.isEmpty()) "V" else PREFIX + letters
    }
}

/**
 * What each named channel is called: its default, unless the user renamed it. Pure and
 * immutable; [ChannelNamesRepository] holds the current value and persists [custom].
 */
data class ChannelNames(
    /** Default name per channel ID. Only these IDs can be named. */
    val defaults: Map<String, String>,
    /** The user's names, only for channels they renamed to something other than the default. */
    val custom: Map<String, String> = emptyMap(),
) {
    /** The name to show for [id], or null if it isn't a named channel. */
    fun of(id: String): String? = custom[id] ?: defaults[id]

    fun isCustom(id: String): Boolean = id in custom

    /**
     * The short tag for [id]: the lineup's own ([ChannelLineup.shortNames]) until the channel is
     * renamed, then one made from its name. [fallbackName] names a channel that isn't a named one
     * (a generated genre channel); null if there is nothing to make a tag from.
     */
    fun shortOf(id: String, fallbackName: String? = null): String? {
        val renamed = custom[id]
        if (renamed == null) ChannelLineup.shortNames[id]?.let { return it }
        return (renamed ?: of(id) ?: fallbackName)?.let(ChannelShortNames::derive)
    }

    /**
     * [id] renamed to [name], tidied with [clean]. An empty name or the default name goes
     * back to the default. Unknown IDs are ignored.
     */
    fun renamed(id: String, name: String): ChannelNames {
        val default = defaults[id] ?: return this
        val clean = clean(name)
        return if (clean.isEmpty() || clean == default) copy(custom = custom - id) else copy(custom = custom + (id to clean))
    }

    /** [saved] names laid over these, keeping only ones that are still valid and not the default. */
    fun withSaved(saved: Map<String, String>): ChannelNames =
        saved.entries.fold(this) { names, (id, name) -> names.renamed(id, name) }

    companion object {
        /** Long enough for a real name, short enough to fit under the dial. */
        const val MAX_LENGTH = 32

        private val whitespace = Regex("\\s+")

        /** Trims, collapses runs of whitespace (including newlines) to one space, and caps at [MAX_LENGTH]. */
        fun clean(name: String): String = name.trim().replace(whitespace, " ").take(MAX_LENGTH).trimEnd()
    }
}
