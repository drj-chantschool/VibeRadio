package app.viberadio.feedback

import app.viberadio.library.Track
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** When each song last played, keyed on its [ContentHash]. One row per song, overwritten on every play. */
interface PlayHistoryStore {
    /** Content hash → epoch milliseconds of the last play. */
    fun readAll(): Map<String, Long>

    fun put(trackHash: String, lastPlayedMs: Long)
}

/**
 * A file's content hash as of a given MediaStore size and modification time. Hashing reads the
 * whole file, so it's done once and remembered; a size or mtime change means the bytes may have
 * changed (a tag edit, say) and the file is hashed again.
 */
data class HashedFile(val uri: String, val sizeBytes: Long, val modifiedSec: Long, val trackHash: String)

/** Persisted [HashedFile]s, one per URI. */
interface TrackHashStore {
    fun readAll(): List<HashedFile>

    fun put(file: HashedFile)
}

/**
 * Song play history for the shuffle: [lastPlayedMs] tells the planner how long ago a track last
 * played, and the service calls [markPlayed] whenever a song starts.
 *
 * Both stores are read into memory once ([load], or lazily on the first lookup) so the planner,
 * which runs on the main thread, never touches the disk or reads a file. A track whose hash isn't
 * known yet reads as never played; it gets hashed the first time it plays.
 */
class PlayHistory(
    private val store: PlayHistoryStore,
    private val hashStore: TrackHashStore,
    /** Opens a track's audio by its URI (a content:// string on device). */
    private val open: (uri: String) -> InputStream,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastPlayed = ConcurrentHashMap<String, Long>()
    private val hashes = ConcurrentHashMap<String, HashedFile>()

    private val loaded by lazy {
        runCatching { lastPlayed.putAll(store.readAll()) }
        runCatching { hashStore.readAll().forEach { hashes[it.uri] = it } }
    }

    /** Serialises hashing and writes. */
    private val mutex = Mutex()

    /** Reads both stores into memory. Blocking; call off the main thread, e.g. at startup. */
    fun load() = loaded

    /** When [track] last started playing, epoch ms, or null if never (or its hash isn't known yet). */
    fun lastPlayedMs(track: Track): Long? {
        load()
        return cachedHash(track)?.let(lastPlayed::get)
    }

    /** [track]'s content hash if it's already known, without reading the file; null until it has played once. */
    fun hashOf(track: Track): String? {
        load()
        return cachedHash(track)
    }

    /**
     * Records that [track] started playing now. Hashes the file if its hash isn't cached (or the
     * file changed). Returns the hash, or null if the file couldn't be read.
     */
    suspend fun markPlayed(track: Track): String? {
        val atMs = clock()
        return withContext(ioDispatcher) {
            mutex.withLock {
                load()
                val hash = cachedHash(track) ?: hashFile(track) ?: return@withLock null
                lastPlayed[hash] = atMs
                runCatching { store.put(hash, atMs) }
                hash
            }
        }
    }

    private fun cachedHash(track: Track): String? =
        hashes[track.uri]?.takeIf { it.sizeBytes == track.sizeBytes && it.modifiedSec == track.modifiedSec }?.trackHash

    private fun hashFile(track: Track): String? {
        val hash = runCatching { open(track.uri).use(ContentHash::of) }.getOrNull() ?: return null
        val file = HashedFile(track.uri, track.sizeBytes, track.modifiedSec, hash)
        hashes[track.uri] = file
        runCatching { hashStore.put(file) }
        return hash
    }
}
