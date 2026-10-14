package app.viberadio.dial

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

/** Where the user's channel names survive restarts. Blocking; call off the main thread. */
interface ChannelNameStore {
    /** Channel ID → name, for the channels the user renamed. Empty if nothing was saved. */
    fun read(): Map<String, String>
    fun write(custom: Map<String, String>)
}

/**
 * Single source of truth for channel names, shared by the dial, the podcast page, settings and
 * the playback service. Starts on the defaults and lays the saved names over them once loaded.
 */
class ChannelNamesRepository(
    defaults: Map<String, String>,
    private val store: ChannelNameStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _names = MutableStateFlow(ChannelNames(defaults))
    val names: StateFlow<ChannelNames> = _names.asStateFlow()

    private val writes = Mutex()

    init {
        scope.launch {
            val saved = withContext(ioDispatcher) { runCatching { store.read() }.getOrDefault(emptyMap()) }
            // A rename made before the load finished wins over the saved one.
            _names.update { current -> current.withSaved(saved).withSaved(current.custom) }
        }
    }

    /** Renames [id]; an empty name or the default restores the default. */
    fun rename(id: String, name: String) {
        _names.update { it.renamed(id, name) }
        scope.launch(ioDispatcher) {
            // Always writes the latest names, so overlapping saves can't land out of order.
            writes.withLock { store.write(_names.value.custom) }
        }
    }
}
