package app.viberadio.playback.audio

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where measured [TrackLevel]s survive restarts. Blocking; call off the main thread. */
interface LevelStore {
    fun readAll(): Map<String, TrackLevel>
    fun put(key: String, level: TrackLevel)
}

/**
 * Levels in a small tab-separated file, one `key, LUFS, peak` line per item, appended as they
 * are measured; on read the last line for a key wins. Pure JVM.
 */
class FileLevelStore(private val file: File) : LevelStore {
    @Synchronized
    override fun readAll(): Map<String, TrackLevel> {
        if (!file.exists()) return emptyMap()
        val out = HashMap<String, TrackLevel>()
        file.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@forEachLine
            val lufs = parts[1].toFloatOrNull() ?: return@forEachLine
            val peak = parts[2].toFloatOrNull() ?: return@forEachLine
            out[parts[0]] = TrackLevel(lufs, peak)
        }
        return out
    }

    @Synchronized
    override fun put(key: String, level: TrackLevel) {
        require('\t' !in key && '\n' !in key)
        file.parentFile?.mkdirs()
        file.appendText("$key\t${level.loudnessLufs}\t${level.peak}\n")
    }
}

/** An item that will play: the media ID it plays under and where its audio is. */
data class LevelRequest(val mediaId: String, val uri: String)

/**
 * Auto track leveling (#1): every song and episode gets one fixed gain that brings its integrated
 * loudness to the target ([AudioSettings.targetLufs]). Not compression: the gain never moves
 * within an item.
 *
 * Levels are measured once per file ([measure], a full decode) on one background worker and kept
 * under a stable key ([keyOf]: the content hash for songs, the episode ID for downloaded
 * episodes), so a file is measured once, ever. The audio processors look a level up by media ID
 * ([levelOf]) on the playback thread; that is only a map read.
 *
 * [request] the items that are about to play (the queued ones) and anything worth measuring
 * ahead (downloaded episodes). Urgent requests (what is playing now) jump the queue.
 */
class Leveling(
    private val store: LevelStore,
    /** The stable key for an item, or null if it can't be measured (e.g. a streamed episode). May read the file. */
    private val keyOf: suspend (LevelRequest) -> String?,
    /** Decodes [LevelRequest.uri] and measures it, or null if it can't be read. Blocking. */
    private val measure: (LevelRequest) -> TrackLevel?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val levels = ConcurrentHashMap<String, TrackLevel>()
    private val keys = ConcurrentHashMap<String, String>()

    /** Keys that failed to measure; not retried until restart. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private val lock = Any()
    private val pending = ArrayDeque<Pair<String, LevelRequest>>()
    private val queued = HashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val loaded by lazy { runCatching { levels.putAll(store.readAll()) } }

    /** Reads the saved levels. Blocking; call off the main thread, e.g. at startup. */
    fun load() {
        loaded
    }

    init {
        scope.launch(ioDispatcher) {
            load()
            for (signal in wake) drain()
        }
    }

    /** The measured level of the item playing under [mediaId], or null if it isn't known (yet). */
    fun levelOf(mediaId: String): TrackLevel? = keys[mediaId]?.let(levels::get)

    /** Makes sure each item will have a level; [urgent] ones are measured first. */
    fun request(items: List<LevelRequest>, urgent: Boolean = false) {
        if (items.isEmpty()) return
        scope.launch(ioDispatcher) {
            load()
            for (item in items) {
                val key = keys[item.mediaId] ?: runCatching { keyOf(item) }.getOrNull() ?: continue
                keys[item.mediaId] = key
                if (levels.containsKey(key) || key in failed) continue
                synchronized(lock) {
                    if (queued.add(key)) {
                        if (urgent) pending.addFirst(key to item) else pending.addLast(key to item)
                    } else if (urgent) {
                        // Already waiting: move it to the front.
                        pending.removeAll { it.first == key }
                        pending.addFirst(key to item)
                    }
                }
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun drain() {
        while (true) {
            val (key, item) = synchronized(lock) { pending.removeFirstOrNull() } ?: return
            val level = withContext(ioDispatcher) { runCatching { measure(item) }.getOrNull() }
            synchronized(lock) { queued.remove(key) }
            if (level == null) {
                failed.add(key)
                continue
            }
            levels[key] = level
            runCatching { store.put(key, level) }
        }
    }
}
