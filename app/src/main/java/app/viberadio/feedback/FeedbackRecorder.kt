package app.viberadio.feedback

import java.io.InputStream
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns a thumbs press on the playing song into a [FeedbackEvent] keyed on the file's content
 * hash, and appends it to the [FeedbackLog] (#13). For now that's all it does: nothing reads the
 * log yet, and a press never skips or changes the queue.
 */
class FeedbackRecorder(
    private val log: FeedbackLog,
    /** Opens a track's audio by its URI (a content:// string on device). */
    private val open: (uri: String) -> InputStream,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val utcOffsetMinutes: (atMs: Long) -> Int = { TimeZone.getDefault().getOffset(it) / 60_000 },
) {
    /** Serialises hashing and appends so events land in press order. */
    private val mutex = Mutex()

    /**
     * URI → hash for this process, so repeated presses don't re-read the file. A moved file gets a
     * new URI, misses the cache, and hashes to the same value.
     */
    private val hashes = HashMap<String, String>()

    /**
     * Records [thumb] for the song at [trackUri] playing on [channelId], with the
     * [separationEq] module in effect at the press (null if none). The timestamp is taken at the
     * call, before hashing. Returns the logged event, or null if the file couldn't be read or the
     * log couldn't be written.
     */
    suspend fun record(trackUri: String, channelId: String, thumb: Thumb, separationEq: String? = null): FeedbackEvent? {
        val atMs = clock()
        return withContext(ioDispatcher) {
            mutex.withLock {
                val hash = hashOf(trackUri) ?: return@withLock null
                val event = FeedbackEvent(hash, thumb, atMs, utcOffsetMinutes(atMs), channelId, separationEq)
                runCatching { log.append(event) }.getOrNull()?.let { event }
            }
        }
    }

    suspend fun events(): List<FeedbackEvent> = withContext(ioDispatcher) { mutex.withLock { log.readAll() } }

    private fun hashOf(uri: String): String? =
        hashes[uri] ?: runCatching { open(uri).use(ContentHash::of) }.getOrNull()?.also { hashes[uri] = it }
}
