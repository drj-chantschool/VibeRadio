package app.viberadio.channels

import app.viberadio.library.ContentKind
import app.viberadio.library.Track

/**
 * Builds a handful of channels from a library by genre tag.
 *
 * The most common genres with at least [minTracksPerChannel] tracks each get a channel, up to
 * [maxChannels] in total. Everything left over goes into one catch-all channel. If no genre
 * qualifies (untagged or very small library), the result is a single "All" channel.
 *
 * An "All songs" channel is always added last: every [ContentKind.MUSIC] track no longer than
 * [allSongsMaxDurationMs]. Podcasts and audiobooks never go in it, including podcast episodes
 * that #6 detects as music (they stay [ContentKind.PODCAST]). It is left out when it would be
 * empty or identical to a channel already on the dial.
 *
 * With [slotIds], the generated channels (genre channels, then the catch-all, or the single
 * "All") take those IDs in order instead of their own, so they land on fixed, named slots on
 * the dial. Their contents don't change. There must be a slot for every channel that can be
 * generated, so [slotIds] needs at least [maxChannels] entries.
 *
 * Pure and deterministic: same input, same channels, same order.
 */
class ChannelGenerator(
    private val maxChannels: Int = 6,
    private val minTracksPerChannel: Int = 8,
    private val labels: Labels = Labels(),
    private val typeResolver: ChannelTypeResolver = ChannelTypeResolver.Untyped,
    private val allSongsMaxDurationMs: Long = ALL_SONGS_MAX_DURATION_MS,
    private val slotIds: List<String> = emptyList(),
) {
    data class Labels(
        val all: String = "All",
        val other: String = "Everything else",
        val allSongs: String = "All songs",
    )

    init {
        require(maxChannels >= 1) { "maxChannels must be at least 1" }
        require(minTracksPerChannel >= 1) { "minTracksPerChannel must be at least 1" }
        require(allSongsMaxDurationMs > 0) { "allSongsMaxDurationMs must be positive" }
        require(slotIds.isEmpty() || slotIds.size >= maxChannels) { "need a slot for each of up to $maxChannels channels" }
        require(ALL_SONGS_ID !in slotIds && slotIds.toSet().size == slotIds.size) { "slot IDs must be distinct and not $ALL_SONGS_ID" }
    }

    fun generate(tracks: List<Track>): List<Channel> {
        if (tracks.isEmpty()) return emptyList()
        val channels = genreChannels(tracks).let { generated ->
            if (slotIds.isEmpty()) generated else generated.mapIndexed { i, channel -> channel.copy(id = slotIds[i]) }
        }
        val songs = tracks.filter { it.kind == ContentKind.MUSIC && it.durationMs <= allSongsMaxDurationMs }
        if (songs.isEmpty() || channels.any { it.tracks == songs }) return channels
        return channels + Channel(id = ALL_SONGS_ID, name = labels.allSongs, type = ChannelType.MUSIC_ONLY, tracks = songs)
    }

    private fun genreChannels(tracks: List<Track>): List<Channel> {

        val byGenre = LinkedHashMap<String, MutableList<Track>>()
        val displayNames = HashMap<String, MutableMap<String, Int>>()
        for (track in tracks) {
            val genre = GenreNormalizer.normalize(track.genre) ?: continue
            byGenre.getOrPut(genre.key) { mutableListOf() } += track
            displayNames.getOrPut(genre.key) { HashMap() }.merge(genre.displayName, 1, Int::plus)
        }

        val ranked = byGenre.entries
            .filter { it.value.size >= minTracksPerChannel }
            .sortedWith(compareByDescending<Map.Entry<String, MutableList<Track>>> { it.value.size }.thenBy { it.key })

        if (ranked.isEmpty()) return listOf(channel(ALL_ID, labels.all, tracks))

        var chosen = ranked.take(maxChannels)
        var chosenKeys = chosen.mapTo(HashSet()) { it.key }
        var leftovers = tracks.filter { GenreNormalizer.normalize(it.genre)?.key !in chosenKeys }
        if (leftovers.isNotEmpty() && chosen.size == maxChannels) {
            if (maxChannels == 1) return listOf(channel(ALL_ID, labels.all, tracks))
            chosen = ranked.take(maxChannels - 1)
            chosenKeys = chosen.mapTo(HashSet()) { it.key }
            leftovers = tracks.filter { GenreNormalizer.normalize(it.genre)?.key !in chosenKeys }
        }

        val channels = chosen.map { (key, genreTracks) ->
            channel("$GENRE_PREFIX$key", preferredName(displayNames.getValue(key)), genreTracks)
        }
        return if (leftovers.isEmpty()) channels else channels + channel(OTHER_ID, labels.other, leftovers)
    }

    private fun channel(id: String, name: String, tracks: List<Track>) =
        Channel(id = id, name = name, type = typeResolver.resolve(tracks), tracks = tracks)

    /** Most frequent spelling wins; ties go to the alphabetically first. */
    private fun preferredName(counts: Map<String, Int>): String =
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key

    companion object {
        const val ALL_ID = "all"
        const val OTHER_ID = "other"
        const val GENRE_PREFIX = "genre:"
        const val ALL_SONGS_ID = "all-songs"

        /** Tracks longer than this (DJ mixes, live sets, full albums in one file) stay off "All songs". */
        const val ALL_SONGS_MAX_DURATION_MS = 20L * 60 * 1000
    }
}
