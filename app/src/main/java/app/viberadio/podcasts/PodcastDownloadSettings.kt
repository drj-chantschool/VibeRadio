package app.viberadio.podcasts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the download settings survive restarts. Blocking; call off the main thread. */
interface PodcastDownloadStore {
    /** The saved count, or null if the user never changed it. */
    fun readCount(): Int?
    fun writeCount(count: Int)

    /** The saved storage limit in bytes, null for none. */
    fun readLimitBytes(): Long?
    fun writeLimitBytes(bytes: Long?)

    /** Episodes a one-off preload ([PodcastRepository.preloadAll]) is keeping on the device until they are played. */
    fun readPinned(): Set<String>
    fun writePinned(ids: Set<String>)
}

/**
 * Settings → Podcasts: how many upcoming episodes are kept on the device, an optional storage
 * limit (downloads go to the lesser of the two), and the episodes a one-off preload pinned.
 * Starts on the defaults and takes the saved choices once loaded ([awaitLoaded]).
 */
class PodcastDownloadSettings(
    private val store: PodcastDownloadStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _count = MutableStateFlow(DEFAULT)

    /** Upcoming episodes (web shows only) kept downloaded. */
    val count: StateFlow<Int> = _count.asStateFlow()

    private val _limitBytes = MutableStateFlow<Long?>(null)

    /** Stop downloading once the kept episodes take this much space; null for no limit. */
    val limitBytes: StateFlow<Long?> = _limitBytes.asStateFlow()

    @Volatile
    var pinned: Set<String> = emptySet()
        private set

    private val loaded = CompletableDeferred<Unit>()
    private val writes = Mutex()

    @Volatile
    private var countChangedBeforeLoad = false

    @Volatile
    private var limitChangedBeforeLoad = false

    init {
        scope.launch {
            val saved = withContext(ioDispatcher) {
                Triple(
                    runCatching { store.readCount() }.getOrNull(),
                    runCatching { store.readLimitBytes() }.getOrNull(),
                    runCatching { store.readPinned() }.getOrDefault(emptySet()),
                )
            }
            val (count, limit, ids) = saved
            if (count != null && !countChangedBeforeLoad && isValid(count)) _count.value = count
            if (limit != null && !limitChangedBeforeLoad && limit > 0) _limitBytes.value = limit
            pinned = ids
            loaded.complete(Unit)
        }
    }

    /** Waits for the saved settings, so nothing is decided on the defaults. */
    suspend fun awaitLoaded() = loaded.await()

    /** False, and nothing changes, when [value] is outside [RANGE]. More than [WARN_ABOVE] is allowed. */
    fun set(value: Int): Boolean {
        if (!isValid(value)) return false
        if (!loaded.isCompleted) countChangedBeforeLoad = true
        _count.value = value
        scope.launch(ioDispatcher) { writes.withLock { runCatching { store.writeCount(_count.value) } } }
        return true
    }

    /** [bytes] null (or 0) for no limit; false, nothing changed, for a negative one. */
    fun setLimitBytes(bytes: Long?): Boolean {
        if (bytes != null && bytes < 0) return false
        if (!loaded.isCompleted) limitChangedBeforeLoad = true
        _limitBytes.value = bytes?.takeIf { it > 0 }
        scope.launch(ioDispatcher) { writes.withLock { runCatching { store.writeLimitBytes(_limitBytes.value) } } }
        return true
    }

    fun setPinned(ids: Set<String>) {
        if (ids == pinned) return
        pinned = ids
        scope.launch(ioDispatcher) { writes.withLock { runCatching { store.writePinned(pinned) } } }
    }

    companion object {
        const val DEFAULT = 3

        /** More than this asks about a storage limit: a lot of audio can fill the phone. */
        const val WARN_ABOVE = 10

        /** Only a sanity bound, so a stray extra digit isn't taken at its word. */
        val RANGE = 0..1000
        fun isValid(value: Int) = value in RANGE
    }
}
