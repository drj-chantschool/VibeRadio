package app.viberadio.channels

import app.viberadio.library.Track

/** A station on the dial. */
data class Channel(
    /** Stable across rescans as long as the genre survives: "genre:<key>", "other", "all" or "all-songs". */
    val id: String,
    val name: String,
    val type: ChannelType,
    val tracks: List<Track>,
)

/**
 * Issue #3. Every channel carries a type so routing can respect it.
 *
 * The scaffold's [ChannelGenerator] still labels every channel [MUSIC_ONLY] via its default
 * [ChannelTypeResolver]; #3 replaces that resolver and splits podcasts out of genre channels.
 */
enum class ChannelType {
    /** Podcasts and spoken word only. */
    VOCAL_ONLY,

    /** Music only. */
    MUSIC_ONLY,

    /** Podcast and music together; see #2 (auto-mixing) and #5 (ducking). */
    MIXED,
}

/** Decides a channel's [ChannelType] from its contents. Extension point for #3. */
fun interface ChannelTypeResolver {
    fun resolve(tracks: List<Track>): ChannelType

    companion object {
        /** Scaffold default: everything is music-only until #3 lands. */
        val Untyped = ChannelTypeResolver { ChannelType.MUSIC_ONLY }
    }
}
