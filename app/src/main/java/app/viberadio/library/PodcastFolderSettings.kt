package app.viberadio.library

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

/** Where the podcast folder choice survives restarts. Blocking; call off the main thread. */
interface PodcastFolderStore {
    /** The saved choice, or null if the user never chose (the default applies). */
    fun read(): PodcastFolder?
    fun write(folder: PodcastFolder)
}

/**
 * Settings → Podcasts → Podcast folder. Starts on [PodcastFolder.DEFAULT] and takes the saved
 * choice once loaded; [awaitFolder] waits for it, so the first scan never puts a podcast folder's
 * files on the song channels.
 */
class PodcastFolderSettings(
    private val store: PodcastFolderStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _folder = MutableStateFlow(PodcastFolder.DEFAULT)
    val folder: StateFlow<PodcastFolder> = _folder.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writes = Mutex()

    @Volatile
    private var changedBeforeLoad = false

    init {
        scope.launch {
            val saved = withContext(ioDispatcher) { runCatching { store.read() }.getOrNull() }
            // A change made before the load finished wins over the saved one.
            if (saved != null && !changedBeforeLoad) _folder.value = saved
            loaded.complete(Unit)
        }
    }

    /** [path] as [Track.folder] gives it, or null for no podcast folder. */
    fun set(path: String?) {
        if (!loaded.isCompleted) changedBeforeLoad = true
        _folder.value = PodcastFolder.of(path)
        scope.launch(ioDispatcher) {
            // Always writes the latest value, so overlapping saves can't land out of order.
            writes.withLock { runCatching { store.write(_folder.value) } }
        }
    }

    suspend fun awaitFolder(): PodcastFolder {
        loaded.await()
        return _folder.value
    }
}
