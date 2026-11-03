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

/** Where the download count survives restarts. Blocking; call off the main thread. */
interface PodcastDownloadStore {
    /** The saved count, or null if the user never changed it. */
    fun read(): Int?
    fun write(count: Int)
}

/**
 * Settings → Podcasts: how many upcoming episodes are kept on the device. Starts on the default
 * and takes the saved choice once loaded.
 */
class PodcastDownloadSettings(
    private val store: PodcastDownloadStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _count = MutableStateFlow(DEFAULT)

    /** Upcoming episodes (web shows only) kept downloaded. */
    val count: StateFlow<Int> = _count.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writes = Mutex()

    @Volatile
    private var changedBeforeLoad = false

    init {
        scope.launch {
            val saved = withContext(ioDispatcher) { runCatching { store.read() }.getOrNull() }
            if (saved != null && !changedBeforeLoad && isValid(saved)) _count.value = saved
            loaded.complete(Unit)
        }
    }

    /** False, and nothing changes, when [value] is outside [RANGE]. */
    fun set(value: Int): Boolean {
        if (!isValid(value)) return false
        if (!loaded.isCompleted) changedBeforeLoad = true
        _count.value = value
        scope.launch(ioDispatcher) {
            writes.withLock { runCatching { store.write(_count.value) } }
        }
        return true
    }

    companion object {
        const val DEFAULT = 3
        val RANGE = 0..10
        fun isValid(value: Int) = value in RANGE
    }
}
