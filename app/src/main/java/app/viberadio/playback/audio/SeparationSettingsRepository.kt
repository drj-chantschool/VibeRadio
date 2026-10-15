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

/** Where [SeparationSettings] survive restarts. Blocking; call off the main thread. */
interface SeparationStore {
    /** The saved settings, or null if never changed. */
    fun read(): SeparationSettings?
    fun write(settings: SeparationSettings)
}

/**
 * Settings → Audio → Separation EQ (shown in developer mode): which [SeparationEq] pulls a
 * podcast and the music apart, and each module's parameters. The audio processors read
 * [settings]'s value on the playback thread. Starts on the defaults and takes the saved ones
 * once loaded; invalid changes are ignored. The choice stays in force with developer mode off.
 */
class SeparationSettingsRepository(
    private val store: SeparationStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _settings = MutableStateFlow(SeparationSettings.DEFAULT)
    val settings: StateFlow<SeparationSettings> = _settings.asStateFlow()

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

    fun select(id: String): Boolean = update(_settings.value.select(id))

    /** Sets one parameter of module [id]; false (nothing changes) if the value isn't accepted. */
    fun set(id: String, key: String, value: Double): Boolean = update(_settings.value.set(id, key, value))

    /** Every module's parameters back to their defaults; the choice of module stays. */
    fun restoreDefaults(): Boolean = update(_settings.value.copy(modules = SeparationEq.all))

    private fun update(next: SeparationSettings?): Boolean {
        if (next == null || !next.isValid) return false
        changedBeforeLoad = true
        _settings.value = next
        scope.launch(ioDispatcher) {
            // Always writes the latest value, so overlapping saves can't land out of order.
            writes.withLock { runCatching { store.write(_settings.value) } }
        }
        return true
    }
}
