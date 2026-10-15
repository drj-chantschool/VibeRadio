package app.viberadio.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where [PodcastGapSettings] survive restarts. Blocking; call off the main thread. */
interface PodcastGapStore {
    /** The saved settings, or null if never changed. */
    fun read(): PodcastGapSettings?
    fun write(settings: PodcastGapSettings)
}

/**
 * The gap rule on Settings → Podcasts (shown in developer mode): which [PodcastGapRule] spaces
 * episodes on song channels, and each rule's parameters. [RadioSessionCallback] reads [settings]'s value each time an
 * episode ends. Starts on the defaults and takes the saved ones once loaded; invalid changes are
 * ignored. The choice stays in force with developer mode off.
 */
class PodcastGapSettingsRepository(
    private val store: PodcastGapStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _settings = MutableStateFlow(PodcastGapSettings.DEFAULT)
    val settings: StateFlow<PodcastGapSettings> = _settings.asStateFlow()

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

    /** Sets one parameter of rule [id]; false (nothing changes) if the value isn't accepted. */
    fun set(id: String, key: String, value: Double): Boolean = update(_settings.value.set(id, key, value))

    /** Every rule's parameters back to their defaults; the choice of rule stays. */
    fun restoreDefaults(): Boolean = update(_settings.value.copy(rules = PodcastGapRule.all))

    private fun update(next: PodcastGapSettings?): Boolean {
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
