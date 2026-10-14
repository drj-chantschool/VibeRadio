package app.viberadio.playback.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where [AudioSettings] survive restarts. Blocking; call off the main thread. */
interface AudioSettingsStore {
    /** The saved settings, or null if the user never changed them. */
    fun read(): AudioSettings?
    fun write(settings: AudioSettings)
}

/**
 * Settings → Audio. Shared by the settings page and the audio processors, which read
 * [settings]'s value on the playback thread. Starts on the defaults and takes the saved ones
 * once loaded. Invalid changes are ignored.
 */
class AudioSettingsRepository(
    private val store: AudioSettingsStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _settings = MutableStateFlow(AudioSettings.DEFAULT)
    val settings: StateFlow<AudioSettings> = _settings.asStateFlow()

    private val writes = Mutex()

    @Volatile
    private var changedBeforeLoad = false

    init {
        scope.launch {
            val saved = withContext(ioDispatcher) { runCatching { store.read() }.getOrNull() }
            // A change made before the load finished wins over the saved one.
            if (saved != null && saved.isValid && !changedBeforeLoad) _settings.value = saved
        }
    }

    /** Applies [change]; returns false (and changes nothing) if the result isn't valid. */
    fun update(change: (AudioSettings) -> AudioSettings): Boolean {
        val next = change(_settings.value)
        if (!next.isValid) return false
        changedBeforeLoad = true
        _settings.value = next
        scope.launch(ioDispatcher) {
            // Always writes the latest value, so overlapping saves can't land out of order.
            writes.withLock { runCatching { store.write(_settings.value) } }
        }
        return true
    }

    fun restoreDefaults() {
        update { AudioSettings.DEFAULT }
    }
}
