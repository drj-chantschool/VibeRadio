package app.viberadio.diagnostics

import app.viberadio.channels.Channel
import app.viberadio.feedback.FeedbackEvent
import app.viberadio.feedback.PlayRecord
import app.viberadio.feedback.Thumb
import app.viberadio.learning.FeatureVector
import app.viberadio.learning.FeatureLayout
import app.viberadio.learning.FeatureVectors
import app.viberadio.library.FolderExclusions
import app.viberadio.library.Track
import app.viberadio.playback.ChannelAiring
import app.viberadio.playback.PodcastEvent
import app.viberadio.playback.PodcastGapSettings
import app.viberadio.playback.audio.AudioSettings
import app.viberadio.playback.audio.SeparationSettings
import app.viberadio.playback.audio.ToneBands
import app.viberadio.playback.audio.TrackLevel
import app.viberadio.podcasts.PodcastLibrary
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.log10

/** One named channel as the export records it: stable ID, default name, current name. */
data class ChannelNameRow(val id: String, val defaultName: String, val name: String)

/** Everything an export reads, gathered by [DiagnosticsExporter]. */
data class DiagnosticsSnapshot(
    val appVersion: String,
    val exportedAtMs: Long,
    /** The device's UTC offset at export, in minutes; names the bundle and shows local times. */
    val utcOffsetMinutes: Int,
    val audio: AudioSettings,
    /** Settings → Audio → Separation EQ: the chosen module and every module's parameters. */
    val separation: SeparationSettings = SeparationSettings.DEFAULT,
    /** Settings → Podcasts: the gap rule between episodes on song channels. */
    val podcastGap: PodcastGapSettings = PodcastGapSettings.DEFAULT,
    val resumeOnBluetooth: Boolean,
    /** Settings → Audio → Library folders: what's left out. */
    val libraryFolders: FolderExclusions = FolderExclusions.NONE,
    /** Settings → Podcasts → Podcast folder: the folder played as a show, not songs; null for none. */
    val podcastFolder: String? = null,
    val channelNames: List<ChannelNameRow>,
    val lastChannelId: String?,
    /** False when the library couldn't be scanned (no permission), so [channels] is empty. */
    val libraryScanned: Boolean,
    /** The channels as placed right now, The Shuffle included. */
    val channels: List<Channel>,
    /** Track URI → content hash, for the tracks whose hash is already known. */
    val trackHashes: Map<String, String>,
    val feedback: List<FeedbackEvent>,
    val playHistory: Map<String, PlayRecord>,
    val listeningClockMs: Long,
    val airings: List<ChannelAiring>,
    /** Leveling results, keyed `song:<hash>` or the episode's media ID. */
    val levels: Map<String, TrackLevel>,
    /** The podcast library's own JSON file, copied as-is; null if there is none yet. */
    val podcastsJson: String?,
    /** The podcast library as loaded, for the readable podcast tables. */
    val podcasts: PodcastLibrary = PodcastLibrary(),
    /** IDs of the episodes on the device. */
    val downloadedEpisodes: Set<String> = emptySet(),
    /** The podcast scheduling log ([PodcastEvent]), oldest first. */
    val podcastEvents: List<PodcastEvent> = emptyList(),
)

/** One file in a bundle. */
data class BundleFile(val name: String, val mimeType: String, val text: String)

/**
 * Turns a [DiagnosticsSnapshot] into the files of one export bundle (Features doc, Diagnostics).
 * Text only (CSV, JSON, Markdown), so the bundle reads without tools. Pure.
 */
object DiagnosticsBundle {

    /** The bundle's folder name: local date and time of the export, sorting oldest first. */
    fun folderName(exportedAtMs: Long, utcOffsetMinutes: Int): String =
        FOLDER_FORMAT.format(Instant.ofEpochMilli(exportedAtMs).atOffset(offset(utcOffsetMinutes)))

    fun build(s: DiagnosticsSnapshot): List<BundleFile> {
        val library = s.channels.flatMap { it.tracks }.distinctBy { it.uri }
        val features = FeatureVectors.from(s.feedback)
        val files = mutableListOf(
            BundleFile("README.md", MIME_MD, README),
            BundleFile("summary.json", MIME_JSON, summary(s, library, features.size)),
            BundleFile("settings.json", MIME_JSON, settings(s)),
            BundleFile("feedback.csv", MIME_CSV, feedback(s.feedback)),
            BundleFile("play_history.csv", MIME_CSV, playHistory(s.playHistory)),
            BundleFile("channel_airings.csv", MIME_CSV, airings(s.airings)),
            BundleFile("features.csv", MIME_CSV, features(features)),
            BundleFile("channels.csv", MIME_CSV, channels(s.channels, s.trackHashes)),
            BundleFile("library.csv", MIME_CSV, library(library, s.trackHashes)),
            BundleFile("levels.csv", MIME_CSV, levels(s.levels, s.audio.targetLufs)),
            BundleFile("podcast_shows.csv", MIME_CSV, podcastShows(s.podcasts, s.downloadedEpisodes)),
            BundleFile("podcast_queues.csv", MIME_CSV, podcastQueues(s.podcasts, s.downloadedEpisodes)),
            BundleFile("podcast_events.csv", MIME_CSV, podcastEvents(s.podcastEvents, s.podcasts, s.utcOffsetMinutes)),
        )
        s.podcastsJson?.let { files += BundleFile("podcasts.json", MIME_JSON, it) }
        return files
    }

    private fun summary(s: DiagnosticsSnapshot, library: List<Track>, songsWithFeedback: Int): String {
        val times = s.feedback.map { it.atMs }
        return Json.encode(
            linkedMapOf(
                "app_version" to s.appVersion,
                "exported_at" to local(s.exportedAtMs, s.utcOffsetMinutes),
                "exported_at_ms" to s.exportedAtMs,
                "utc_offset_minutes" to s.utcOffsetMinutes,
                "feature_layout_version" to FeatureLayout.VERSION,
                "library_scanned" to s.libraryScanned,
                "last_channel" to s.lastChannelId,
                "listening_clock_ms" to s.listeningClockMs,
                "counts" to linkedMapOf(
                    "feedback_events" to s.feedback.size,
                    "songs_with_feedback" to songsWithFeedback,
                    "thumbs_up" to s.feedback.count { it.thumb == Thumb.UP },
                    "thumbs_down" to s.feedback.count { it.thumb == Thumb.DOWN },
                    "play_history" to s.playHistory.size,
                    "library_songs" to library.size,
                    "library_songs_hashed" to library.count { it.uri in s.trackHashes },
                    "channel_airings" to s.airings.size,
                    "levels" to s.levels.size,
                    "podcast_shows" to s.podcasts.podcasts.size,
                    "podcast_episodes" to s.podcasts.episodes.size,
                    "podcast_episodes_played" to s.podcasts.played.size,
                    "podcast_episodes_downloaded" to s.downloadedEpisodes.size,
                    "podcast_events" to s.podcastEvents.size,
                ),
                "songs_per_channel" to s.channels.associate { it.id to it.tracks.size },
                "feedback_range" to if (times.isEmpty()) {
                    null
                } else {
                    linkedMapOf("first" to iso(times.min()), "last" to iso(times.max()))
                },
            ),
        )
    }

    private fun settings(s: DiagnosticsSnapshot): String = Json.encode(
        linkedMapOf(
            "audio" to linkedMapOf(
                "target_lufs" to s.audio.targetLufs,
                "mixing_factor_db" to s.audio.mixingFactorDb,
                "ramp_ms" to s.audio.rampMs,
                "tone_bands_hz" to ToneBands.HZ.toList(),
                "music_tone_db" to s.audio.musicTone.bandsDb,
                "podcast_tone_db" to s.audio.podcastTone.bandsDb,
                "is_default" to (s.audio == AudioSettings.DEFAULT),
            ),
            "separation_eq" to linkedMapOf(
                "module" to s.separation.selected,
                "modules" to s.separation.modules.associate { module -> module.id to module.params.associate { it.key to module[it.key] } },
                "is_default" to (s.separation == SeparationSettings.DEFAULT),
            ),
            "podcast_gap" to linkedMapOf(
                "rule" to s.podcastGap.selected,
                "rules" to s.podcastGap.rules.associate { rule -> rule.id to rule.params.associate { it.key to rule[it.key] } },
                "is_default" to (s.podcastGap == PodcastGapSettings.DEFAULT),
            ),
            "bluetooth" to linkedMapOf("resume_on_connect" to s.resumeOnBluetooth),
            "library_folders" to linkedMapOf(
                "excluded" to s.libraryFolders.subtrees.sorted(),
                "excluded_files_only" to s.libraryFolders.filesOnly.sorted(),
                "podcast_folder" to s.podcastFolder,
            ),
            "channels" to s.channelNames.map {
                linkedMapOf("id" to it.id, "default_name" to it.defaultName, "name" to it.name)
            },
            "podcasts" to "see podcasts.json",
        ),
    )

    private fun feedback(events: List<FeedbackEvent>) = Csv.table(
        listOf("track_hash", "thumb", "at_ms", "at_local", "utc_offset_minutes", "channel_id", "separation_eq"),
        events.map {
            listOf(it.trackHash, it.thumb.name, it.atMs, local(it.atMs, it.utcOffsetMinutes), it.utcOffsetMinutes, it.channelId, it.separationEq)
        },
    )

    private fun playHistory(history: Map<String, PlayRecord>) = Csv.table(
        listOf("track_hash", "last_played_ms", "last_played_utc", "listened_at_ms"),
        history.entries.sortedBy { it.value.wallClockMs }.map { (hash, r) ->
            listOf(hash, r.wallClockMs, iso(r.wallClockMs), r.listeningMs)
        },
    )

    private fun airings(airings: List<ChannelAiring>) = Csv.table(
        listOf("channel_id", "track_hash", "duration_ms", "started_at_ms", "started_at_utc"),
        airings.sortedBy { it.channelId }.map { listOf(it.channelId, it.trackHash, it.durationMs, it.startedAtMs, iso(it.startedAtMs)) },
    )

    private fun features(vectors: Map<String, FeatureVector>): String {
        val columns = FeatureLayout.channels.flatMap { listOf("${it}_up", "${it}_down") }
        return Csv.table(
            listOf("track_hash") + columns,
            vectors.entries.sortedBy { it.key }.map { (hash, v) -> listOf<Any?>(hash) + v.toFloatArray().toList() },
        )
    }

    private fun channels(channels: List<Channel>, hashes: Map<String, String>) = Csv.table(
        listOf("channel_id", "track_hash", "track_id"),
        channels.flatMap { channel -> channel.tracks.map { listOf(channel.id, hashes[it.uri], it.id) } },
    )

    private fun library(tracks: List<Track>, hashes: Map<String, String>) = Csv.table(
        listOf("track_id", "track_hash", "title", "artist", "album", "genre", "duration_ms", "kind", "size_bytes", "modified_sec", "folder", "uri"),
        tracks.sortedBy { it.id }.map {
            listOf(it.id, hashes[it.uri], it.title, it.artist, it.album, it.genre, it.durationMs, it.kind.name, it.sizeBytes, it.modifiedSec, it.folder, it.uri)
        },
    )

    private fun levels(levels: Map<String, TrackLevel>, targetLufs: Float) = Csv.table(
        listOf("key", "loudness_lufs", "peak", "peak_dbfs", "gain_db_at_target"),
        levels.entries.sortedBy { it.key }.map { (key, l) ->
            listOf(key, l.loudnessLufs, l.peak, if (l.peak > 0f) 20f * log10(l.peak) else null, l.gainDb(targetLufs))
        },
    )

    private fun podcastShows(library: PodcastLibrary, downloaded: Set<String>) = Csv.table(
        listOf("feed_url", "title", "channels", "pause_music", "speed", "priority", "skip_intro_s", "episodes", "unplayed", "downloaded", "last_refreshed_utc"),
        library.podcasts.map { show ->
            val episodes = library.episodes.filter { it.feedUrl == show.feedUrl }
            listOf(
                show.feedUrl,
                show.title,
                show.channels.sorted().joinToString(" "),
                show.pauseMusic,
                show.speed,
                show.priority,
                show.skipIntroSeconds,
                episodes.size,
                episodes.count { !library.isPlayed(it.id) },
                episodes.count { it.id in downloaded },
                if (show.lastRefreshedMs > 0) iso(show.lastRefreshedMs) else null,
            )
        },
    )

    /** What each channel with a show on it plays next, as the app would queue it right now. */
    private fun podcastQueues(library: PodcastLibrary, downloaded: Set<String>) = Csv.table(
        listOf("channel_id", "position", "episode_id", "show", "title", "published_utc", "duration_ms", "resume_ms", "downloaded", "pause_music"),
        library.channelIds.sorted().flatMap { channelId ->
            library.queue(channelId).take(QUEUE_ROWS).mapIndexed { i, e ->
                listOf(
                    channelId,
                    i,
                    e.id,
                    library.podcast(e.feedUrl)?.title,
                    e.title,
                    if (e.publishedMs > 0) iso(e.publishedMs) else null,
                    e.durationMs,
                    library.positions[e.id],
                    e.id in downloaded,
                    library.pausesMusic(e.id),
                )
            }
        },
    )

    private fun podcastEvents(events: List<PodcastEvent>, library: PodcastLibrary, utcOffsetMinutes: Int) = Csv.table(
        listOf("at_ms", "at_local", "on_air_ms", "channel_id", "event", "episode_id", "show", "episode_title", "detail"),
        events.map { e ->
            val episode = e.episodeId?.let(library::episode)
            listOf(
                e.atMs,
                local(e.atMs, utcOffsetMinutes),
                e.onAirMs,
                e.channelId,
                e.kind,
                e.episodeId,
                episode?.let { library.podcast(it.feedUrl)?.title },
                episode?.title,
                e.detail,
            )
        },
    )

    private fun offset(minutes: Int): ZoneOffset = ZoneOffset.ofTotalSeconds(minutes.coerceIn(-18 * 60, 18 * 60) * 60)

    private fun local(ms: Long, utcOffsetMinutes: Int): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(ms).atOffset(offset(utcOffsetMinutes)))

    private fun iso(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

    private val FOLDER_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    /** Episodes listed per channel in podcast_queues.csv. */
    private const val QUEUE_ROWS = 10

    const val MIME_CSV = "text/csv"
    const val MIME_JSON = "application/json"
    const val MIME_MD = "text/markdown"

    private val README = """
        # VibeRadio diagnostics bundle

        One export, written by Settings → Debug. Songs are keyed on `track_hash` (SHA-256 of the
        file's bytes), channels on stable IDs (`breeze`, `buzz`, `zone`, `breath`, `hall`,
        `all-songs`, `podcasts`); display names are in settings.json. Empty cells are unknown.

        - summary.json: app version, export time, feature layout version, counts
        - settings.json: audio settings (both tone curves), the separation EQ module and every module's parameters, the podcast gap rule and its parameters, Bluetooth setting, folders left out of the library and the podcast folder, channel names
        - feedback.csv: every thumbs press, oldest first (the append-only log; source of truth for learning); `separation_eq` is the separation module in effect at the press, empty when no podcast played over the music
        - play_history.csv: per song, its last play (wall clock and listening-clock reading)
        - channel_airings.csv: what each song channel last aired (live-radio resume)
        - features.csv: per song, the feature vector rebuilt from feedback.csv (layout in summary.json)
        - channels.csv: current placement, one row per (channel, song)
        - library.csv: every song on a channel; track_hash is empty until the song has played once
        - levels.csv: measured loudness per song (`song:<hash>`) and downloaded episode, with the gain at the current target
        - podcasts.json: the podcast library as the app stores it (shows, channels, speed, Pause music, played, positions)
        - podcast_shows.csv: per show, its channels, Pause music, speed, priority, skip intro (seconds), and episode counts (all, unplayed, on the device)
        - podcast_queues.csv: per channel with a show on it, the next 10 episodes as the app would queue them now
        - podcast_events.csv: the podcast scheduling log on song channels, oldest first (newest 5,000 to 10,000 kept): tunes, the wait after each tune or episode (`detail`, times relative to the event), each song boundary and whether an episode was due, episode starts (`mode=overlay` over the music or `solo` for a music podcast), ends and failures, and why a due episode didn't start. `on_air_ms` is the clock the schedule runs on; it restarts with the service (`service_start`). `at_local` uses the export's UTC offset
    """.trimIndent() + "\n"
}

/** CSV per RFC 4180: comma-separated, CRLF-free (LF), fields quoted when they need it. Null is an empty cell. */
internal object Csv {
    fun table(header: List<String>, rows: List<List<Any?>>): String = buildString {
        append(header.joinToString(",") { field(it) }).append('\n')
        rows.forEach { row -> append(row.joinToString(",") { field(it) }).append('\n') }
    }

    fun field(value: Any?): String {
        val text = when (value) {
            null -> ""
            is Float -> if (value.isFinite()) value.toString() else ""
            is Double -> if (value.isFinite()) value.toString() else ""
            else -> value.toString()
        }
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }
    }
}

/** A small JSON writer for maps, lists, strings, numbers, booleans and null; pretty-printed. Non-finite numbers are null. */
internal object Json {
    fun encode(value: Any?): String = buildString { write(value, 0) }.plus("\n")

    private fun StringBuilder.write(value: Any?, depth: Int) {
        when (value) {
            null -> append("null")
            is String -> string(value)
            is Boolean -> append(value)
            is Float -> if (value.isFinite()) append(value) else append("null")
            is Double -> if (value.isFinite()) append(value) else append("null")
            is Number -> append(value)
            is Map<*, *> -> container('{', '}', value.entries.toList(), depth) { entry ->
                string(entry.key.toString())
                append(": ")
                write(entry.value, depth + 1)
            }
            is Iterable<*> -> container('[', ']', value.toList(), depth) { write(it, depth + 1) }
            else -> string(value.toString())
        }
    }

    private fun <T> StringBuilder.container(open: Char, close: Char, items: List<T>, depth: Int, item: StringBuilder.(T) -> Unit) {
        if (items.isEmpty()) {
            append(open).append(close)
            return
        }
        append(open).append('\n')
        items.forEachIndexed { i, it ->
            append("  ".repeat(depth + 1))
            item(it)
            if (i < items.lastIndex) append(',')
            append('\n')
        }
        append("  ".repeat(depth)).append(close)
    }

    private fun StringBuilder.string(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
