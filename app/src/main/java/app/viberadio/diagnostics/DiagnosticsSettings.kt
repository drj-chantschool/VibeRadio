package app.viberadio.diagnostics

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

/** How the last export went. */
data class ExportOutcome(
    /** When it finished, epoch ms. */
    val atMs: Long,
    /** The bundle's folder name, e.g. `2026-10-15_081203`; null if it failed before naming one. */
    val bundle: String?,
    /** Whether it reached the diagnostics folder (it is always kept for sharing either way). */
    val savedToFolder: Boolean,
    /** Why it failed or fell short; null when everything worked. */
    val error: String? = null,
)

/** Settings → Debug, plus whether developer mode (and so that page) is on at all. */
data class DiagnosticsPrefs(
    val developerMode: Boolean = false,
    /** Tree URI from the system folder picker, with a persisted permission; null until one is picked. */
    val folderUri: String? = null,
    /** The folder's name as the picker showed it, for the settings row. */
    val folderName: String? = null,
    /** Export once a day while charging and idle. */
    val nightly: Boolean = false,
    val lastExport: ExportOutcome? = null,
)

/** Where [DiagnosticsPrefs] survive restarts. Blocking; call off the main thread. */
interface DiagnosticsStore {
    /** The saved settings, or null if nothing was ever saved. */
    fun read(): DiagnosticsPrefs?
    fun write(prefs: DiagnosticsPrefs)
}

/**
 * Developer mode and the diagnostics export settings. Starts on the defaults (developer mode off)
 * and takes the saved settings once loaded; changes made before then are applied on top.
 */
class DiagnosticsSettings(
    private val store: DiagnosticsStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _prefs = MutableStateFlow(DiagnosticsPrefs())
    val prefs: StateFlow<DiagnosticsPrefs> = _prefs.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writes = Mutex()
    private val early = mutableListOf<(DiagnosticsPrefs) -> DiagnosticsPrefs>()

    init {
        scope.launch {
            val saved = withContext(ioDispatcher) { runCatching { store.read() }.getOrNull() }
            synchronized(early) {
                if (saved != null) _prefs.value = early.fold(saved) { acc, change -> change(acc) }
                early.clear()
                loaded.complete(Unit)
            }
        }
    }

    /** The settings once the saved ones are loaded; for callers that may run before anything else. */
    suspend fun await(): DiagnosticsPrefs {
        loaded.await()
        return _prefs.value
    }

    /** Turning developer mode off also stops the nightly export; the folder choice is kept. */
    fun setDeveloperMode(on: Boolean) = change { it.copy(developerMode = on, nightly = it.nightly && on) }

    fun setFolder(uri: String, name: String?) = change { it.copy(folderUri = uri, folderName = name) }

    fun setNightly(on: Boolean) = change { it.copy(nightly = on && it.developerMode) }

    fun recordExport(outcome: ExportOutcome) = change { it.copy(lastExport = outcome) }

    private fun change(change: (DiagnosticsPrefs) -> DiagnosticsPrefs) {
        synchronized(early) {
            if (!loaded.isCompleted) early += change
            _prefs.update(change)
        }
        scope.launch(ioDispatcher) {
            // Never before the load, so an early change can't overwrite the saved settings with
            // the defaults. Always writes the latest value, so overlapping saves can't land out of order.
            loaded.await()
            writes.withLock { runCatching { store.write(_prefs.value) } }
        }
    }
}
