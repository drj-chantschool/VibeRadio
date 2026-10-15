package app.viberadio.dial

import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelGenerator
import app.viberadio.channels.ChannelType
import app.viberadio.podcasts.PodcastChannels

/** One position on the knob. */
data class Station(val id: String, val name: String, val type: ChannelType)

/**
 * The knob's positions: song channels from the song pipeline, then podcast-only channels from
 * the podcast pipeline. The dial and playback are the only places the two pipelines meet
 * (#17), and what they share is a channel ID.
 */
object Dial {

    /**
     * Song channels in generator order, then the podcast-only channels that have at least one
     * show assigned. A channel only takes a place on the dial once it has something on it, so
     * mood slots ([ChannelLineup]) the generator left empty stay off it.
     *
     * Named channels show the user's name for them ([names]); generated ones keep their own.
     */
    fun stations(songChannels: List<Channel>, podcastChannelIds: Set<String>, names: ChannelNames): List<Station> {
        val songs = songChannels.map { Station(it.id, names.of(it.id) ?: it.name, it.type) }
        val songIds = songs.mapTo(HashSet()) { it.id }
        val podcastOnly = podcastChannelIds
            .filter { it !in songIds && it == PodcastChannels.PODCASTS_ID }
            .map { Station(it, names.of(it) ?: it, ChannelType.VOCAL_ONLY) }
        return songs + podcastOnly
    }

    /**
     * #4: the music-only channel a podcast-only channel hands over to when it has nothing left
     * to play. "All songs" if there is one, otherwise the first song channel.
     */
    fun fallback(songChannels: List<Channel>): Channel? =
        songChannels.firstOrNull { it.id == ChannelGenerator.ALL_SONGS_ID } ?: songChannels.firstOrNull()

    /**
     * The station [delta] places from [currentId] on [stations], wrapping past either end: the
     * notification's channel buttons. Not on the dial (or nothing tuned): the first station going
     * up, the last going down. Null if there is nowhere else to go.
     */
    fun step(stations: List<Station>, currentId: String?, delta: Int): Station? {
        if (stations.isEmpty()) return null
        val index = stations.indexOfFirst { it.id == currentId }
        if (index < 0) return if (delta > 0) stations.first() else stations.last()
        return stations[Math.floorMod(index + delta, stations.size)].takeIf { it.id != currentId }
    }
}
