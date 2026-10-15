package app.viberadio.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the left-out folders survive restarts. Blocking; call off the main thread. */
interface LibraryFolderStore {
    fun read(): FolderExclusions
    fun write(exclusions: FolderExclusions)
}

/**
 * Settings → Audio → Library folders: the folders left out of the library. Starts with nothing
 * left out and takes the saved choice once loaded; [awaitExclusions] waits for it, so the first
 * scan never lets a left-out song through.
 */
class LibraryFolderSettings(
    private val store: LibraryFolderStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _exclusions = MutableStateFlow(FolderExclusions.NONE)
    val exclusions: StateFlow<FolderExclusions> = _exclusions.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writes = Mutex()

    @Volatile
    private var changedBeforeLoad = false

    init {
        scope.launch {
            val saved = withContext(ioDispatcher) { runCatching { store.read() }.getOrNull() }
            // A change made before the load finished wins over the saved one.
            if (saved != null && !changedBeforeLoad) _exclusions.value = saved
            loaded.complete(Unit)
        }
    }

    fun update(change: (FolderExclusions) -> FolderExclusions) {
        if (!loaded.isCompleted) changedBeforeLoad = true
        _exclusions.update(change)
        scope.launch(ioDispatcher) {
            // Always writes the latest value, so overlapping saves can't land out of order.
            writes.withLock { runCatching { store.write(_exclusions.value) } }
        }
    }

    suspend fun awaitExclusions(): FolderExclusions {
        loaded.await()
        return _exclusions.value
    }
}
