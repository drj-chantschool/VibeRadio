package app.viberadio.playback

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One podcast scheduling event on a channel (#2), for diagnostics: what [RadioSessionCallback]
 * decided and why, so "the next episode didn't play" can be traced from an export.
 */
data class PodcastEvent(
    /** Wall clock. */
    val atMs: Long,
    /** The [OnAirClock] reading the schedule works on (not persisted across restarts). */
    val onAirMs: Long,
    val channelId: String?,
    /** What happened; one of [PodcastEvent.Kind]'s names, lowercase. */
    val kind: String,
    val episodeId: String? = null,
    /** `key=value` pairs separated by spaces. */
    val detail: String = "",
) {
    enum class Kind {
        /** The playback service attached to its players. */
        SERVICE_START,
        SERVICE_STOP,

        /** A channel was tuned (knob, notification, Bluetooth). */
        TUNE,

        /** A song channel's queue ran out and was refilled, maybe on another channel. */
        QUEUE_END,

        /** The schedule's wait changed: after a tune, an episode end or a failure. */
        WAIT,

        /** A song ended and the next began; whether an episode was due there. */
        BOUNDARY,

        /** The wait is over, but no episode started; why. Logged once per wait. */
        NOTHING_STARTED,

        /** A music podcast's episode was queued in place of the next song. */
        QUEUED_SOLO,
        START,
        END,

        /** An episode couldn't play. */
        FAIL,

        /** The overlay episode was stopped where it was (a tune). */
        STOP,

        /** The session player reported an error. */
        PLAYER_ERROR,
        ;

        val id: String get() = name.lowercase()
    }

    companion object {
        /** One tab-separated line, without the newline; tabs and newlines in text become spaces. */
        fun encode(e: PodcastEvent): String = listOf(
            e.atMs.toString(),
            e.onAirMs.toString(),
            e.channelId.orEmpty(),
            e.kind,
            e.episodeId.orEmpty(),
            e.detail,
        ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }

        /** The event on [line], or null if it isn't one. */
        fun decode(line: String): PodcastEvent? {
            val parts = line.split('\t')
            if (parts.size != 6) return null
            return PodcastEvent(
                atMs = parts[0].toLongOrNull() ?: return null,
                onAirMs = parts[1].toLongOrNull() ?: return null,
                channelId = parts[2].ifEmpty { null },
                kind = parts[3].ifEmpty { return null },
                episodeId = parts[4].ifEmpty { null },
                detail = parts[5],
            )
        }
    }
}

/**
 * The podcast event log on disk: one [PodcastEvent.encode] line per event. Keeps the newest
 * [keep] events; the file is cut back to them once it holds twice as many.
 */
class FilePodcastEventStore(private val file: File, private val keep: Int = 5_000) {
    /** Lines in the file, once counted. */
    private var lines = -1

    @Synchronized
    fun readAll(): List<PodcastEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(PodcastEvent::decode)
    }

    @Synchronized
    fun append(event: PodcastEvent) {
        file.parentFile?.mkdirs()
        if (lines < 0) lines = if (file.exists()) file.readLines().size else 0
        file.appendText(PodcastEvent.encode(event) + "\n")
        lines++
        if (lines >= 2 * keep) {
            val kept = file.readLines().takeLast(keep)
            file.writeText(kept.joinToString("") { "$it\n" })
            lines = kept.size
        }
    }
}

/** Writes [PodcastEvent]s on [ioDispatcher], in the order they're added, without blocking the caller. */
class PodcastEventLog(
    private val store: FilePodcastEventStore,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    private val pending = Channel<PodcastEvent>(Channel.UNLIMITED)

    init {
        scope.launch(ioDispatcher) {
            for (event in pending) runCatching { store.append(event) }
        }
    }

    fun add(event: PodcastEvent) {
        pending.trySend(event)
    }

    /** Blocking; call off the main thread. Events still being written may be missing. */
    fun readAll(): List<PodcastEvent> = store.readAll()
}
