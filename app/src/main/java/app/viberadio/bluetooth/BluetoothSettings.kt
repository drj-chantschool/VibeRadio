package app.viberadio.bluetooth

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

/** Where the Bluetooth settings survive restarts. Blocking; call off the main thread. */
interface BluetoothSettingsStore {
    /** The saved choice, or null if the user never changed it. */
    fun readResumeOnConnect(): Boolean?
    fun writeResumeOnConnect(on: Boolean)
}

/**
 * Settings → Bluetooth. Shared by the settings page and [BluetoothConnectReceiver]. Starts on the
 * default and takes the saved choice once loaded.
 */
class BluetoothSettings(
    private val store: BluetoothSettingsStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _resumeOnConnect = MutableStateFlow(DEFAULT_RESUME_ON_CONNECT)

    /** Resume playback when a Bluetooth audio device connects. */
    val resumeOnConnect: StateFlow<Boolean> = _resumeOnConnect.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writes = Mutex()

    @Volatile
    private var changedBeforeLoad = false

    init {
        scope.launch {
            val saved = withContext(ioDispatcher) { runCatching { store.readResumeOnConnect() }.getOrNull() }
            // A change made before the load finished wins over the saved one.
            if (saved != null && !changedBeforeLoad) _resumeOnConnect.value = saved
            loaded.complete(Unit)
        }
    }

    fun setResumeOnConnect(on: Boolean) {
        if (!loaded.isCompleted) changedBeforeLoad = true
        _resumeOnConnect.value = on
        scope.launch(ioDispatcher) {
            // Always writes the latest value, so overlapping saves can't land out of order.
            writes.withLock { runCatching { store.writeResumeOnConnect(_resumeOnConnect.value) } }
        }
    }

    /** The setting once the saved choice is loaded; for callers that may run before anything else. */
    suspend fun awaitResumeOnConnect(): Boolean {
        loaded.await()
        return _resumeOnConnect.value
    }

    companion object {
        /** On: defaults should be right the first time, and headphones going on means listening. */
        const val DEFAULT_RESUME_ON_CONNECT = true
    }
}
