package app.viberadio.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.viberadio.VibeRadioApp
import app.viberadio.bluetooth.BluetoothSettings
import app.viberadio.dial.ChannelLineup
import app.viberadio.dial.ChannelNames
import app.viberadio.dial.ChannelNamesRepository
import app.viberadio.playback.PodcastGapSettings
import app.viberadio.playback.audio.AudioSettings
import app.viberadio.playback.audio.AudioSettingsRepository
import app.viberadio.playback.audio.SeparationSettings
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import app.viberadio.diagnostics.DevModeUnlock
import app.viberadio.diagnostics.DiagnosticsExporter
import app.viberadio.diagnostics.DiagnosticsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import app.viberadio.library.FolderNode
import app.viberadio.library.FolderState
import app.viberadio.library.FolderTree
import kotlinx.coroutines.flow.stateIn

/** One named channel on the Channels settings page. */
data class ChannelNameUi(
    val id: String,
    val name: String,
    val defaultName: String,
    @StringRes val description: Int,
    /** True when the user renamed it. */
    val custom: Boolean,
)

/** Settings → Audio → Library folders as the page shows it. */
data class LibraryFoldersUi(
    /** False until the library has been scanned; there's nothing to show before that. */
    val scanned: Boolean,
    val tree: List<FolderNode>,
    /** Every row's checkbox, by [FolderNode.path]. */
    val states: Map<String, FolderState>,
) {
    companion object {
        val EMPTY = LibraryFoldersUi(scanned = false, tree = emptyList(), states = emptyMap())
    }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val names: ChannelNamesRepository = (application as VibeRadioApp).container.channelNames

    val channels: StateFlow<List<ChannelNameUi>> = names.names
        .map(::ui)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui(names.names.value))

    fun rename(id: String, name: String) = names.rename(id, name)

    private val bluetooth: BluetoothSettings = (application as VibeRadioApp).container.bluetoothSettings

    /** Settings → Bluetooth → "Resume on Bluetooth connection". */
    val resumeOnConnect: StateFlow<Boolean> = bluetooth.resumeOnConnect

    fun setResumeOnConnect(on: Boolean) = bluetooth.setResumeOnConnect(on)

    private val audio: AudioSettingsRepository = (application as VibeRadioApp).container.audioSettings

    /** Settings → Audio: the leveling target, mixing factor, ramp time and (Equalizer) both tone curves. */
    val audioSettings: StateFlow<AudioSettings> = audio.settings

    /** Applies [change] if the result is valid; false if it isn't (nothing changes). */
    fun updateAudio(change: (AudioSettings) -> AudioSettings): Boolean = audio.update(change)

    fun restoreAudioDefaults() = audio.restoreDefaults()

    /** Settings → Audio → Equalizer: both tone curves flat. */
    fun restoreTone() = audio.restoreTone()

    private val container = (application as VibeRadioApp).container

    /** Folder → tracks directly in it, from the last scan before anything is left out. */
    private val folderCounts = container.channelRepository.scanned
        .map { tracks -> tracks?.let(FolderTree::counts) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Settings → Audio → Library folders: the folders that hold audio, each in, partly in or out. */
    val libraryFolders: StateFlow<LibraryFoldersUi> = combine(folderCounts, container.libraryFolders.exclusions) { counts, exclusions ->
        if (counts == null) return@combine LibraryFoldersUi.EMPTY
        val tree = FolderTree.build(counts)
        val states = HashMap<String, FolderState>()
        fun visit(node: FolderNode) {
            states[node.path] = FolderTree.state(node, exclusions, counts)
            node.children.forEach(::visit)
        }
        tree.forEach(::visit)
        LibraryFoldersUi(scanned = true, tree = tree, states = states)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryFoldersUi.EMPTY)

    /** The checkbox on [node]: leaves it out when it's all in, otherwise puts all of it back. */
    fun toggleFolder(node: FolderNode) {
        val counts = folderCounts.value ?: return
        container.libraryFolders.update { FolderTree.toggle(node, it, counts) }
    }

    /** Settings → Podcasts, in developer mode: the gap rule between episodes and each rule's figures. */
    val podcastGap: StateFlow<PodcastGapSettings> = container.podcastGapSettings.settings

    fun selectGapRule(id: String) {
        container.podcastGapSettings.select(id)
    }

    /** Sets one figure of rule [ruleId] if accepted; false (nothing changes) if not. */
    fun setGapParam(ruleId: String, key: String, value: Double): Boolean = container.podcastGapSettings.set(ruleId, key, value)

    fun restoreGapDefaults() {
        container.podcastGapSettings.restoreDefaults()
    }

    /** Settings → Audio → Separation EQ, in developer mode: the module and each module's figures. */
    val separation: StateFlow<SeparationSettings> = container.separationSettings.settings

    fun selectSeparation(id: String) {
        container.separationSettings.select(id)
    }

    /** Sets one figure of module [moduleId] if accepted; false (nothing changes) if not. */
    fun setSeparationParam(moduleId: String, key: String, value: Double): Boolean =
        container.separationSettings.set(moduleId, key, value)

    fun restoreSeparationDefaults() {
        container.separationSettings.restoreDefaults()
    }

    /** The version line at the foot of settings, which developer mode is unlocked from. */
    val appVersion: String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** Developer mode and Settings → Debug. */
    val diagnostics: StateFlow<DiagnosticsPrefs> = container.diagnosticsSettings.prefs

    private val unlock = DevModeUnlock()

    /** One tap on the version line; seven in a row turn developer mode on. */
    fun tapVersion(): DevModeUnlock.Result {
        val result = unlock.tap(SystemClock.elapsedRealtime(), alreadyOn = diagnostics.value.developerMode)
        if (result == DevModeUnlock.Result.Unlocked) container.diagnosticsSettings.setDeveloperMode(true)
        return result
    }

    fun setDeveloperMode(on: Boolean) = container.diagnosticsSettings.setDeveloperMode(on)

    fun setNightly(on: Boolean) = container.diagnosticsSettings.setNightly(on)

    /** A folder from the system picker, its permission already persisted by the caller. */
    fun setFolder(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { DiagnosticsExporter.folderName(getApplication<Application>().contentResolver, uri) }
            container.diagnosticsSettings.setFolder(uri.toString(), name)
        }
    }

    private val _exporting = MutableStateFlow(false)

    /** An export is under way (Export now or Share export). */
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** Writes a bundle to the diagnostics folder. Outlives the screen: runs on the app scope. */
    fun exportNow() {
        if (_exporting.value) return
        _exporting.value = true
        container.appScope.launch {
            try {
                container.diagnostics.export()
            } finally {
                _exporting.value = false
            }
        }
    }

    /** Exports, then hands [onReady] a share sheet for that bundle (null if there's nothing to share). */
    fun share(onReady: (Intent?) -> Unit) {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            try {
                container.diagnostics.export()
                onReady(container.diagnostics.shareIntent())
            } finally {
                _exporting.value = false
            }
        }
    }

    private fun ui(names: ChannelNames): List<ChannelNameUi> = ChannelLineup.ids.map { id ->
        ChannelNameUi(
            id = id,
            name = names.of(id).orEmpty(),
            defaultName = names.defaults[id].orEmpty(),
            description = LineupStrings.descriptions.getValue(id),
            custom = names.isCustom(id),
        )
    }
}
