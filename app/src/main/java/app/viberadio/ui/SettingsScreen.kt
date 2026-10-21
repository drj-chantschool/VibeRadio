package app.viberadio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.viberadio.dial.ChannelNames
import app.viberadio.features.AudioFeatures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.viberadio.R
import androidx.annotation.StringRes
import androidx.compose.ui.text.input.KeyboardType
import app.viberadio.playback.audio.AudioSettings
import app.viberadio.playback.audio.AudioStream
import app.viberadio.playback.audio.EqParam
import app.viberadio.playback.audio.EqUnit
import app.viberadio.playback.audio.SeparationEq
import app.viberadio.playback.audio.SeparationSettings
import app.viberadio.playback.audio.ToneBands
import app.viberadio.playback.audio.ToneCurve
import app.viberadio.library.FolderExclusions
import app.viberadio.library.FolderNode
import app.viberadio.library.FolderState
import app.viberadio.library.FolderTree
import app.viberadio.library.PodcastFolder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.state.ToggleableState
import app.viberadio.playback.GapParam
import app.viberadio.playback.GapUnit
import app.viberadio.playback.PodcastGapRule
import app.viberadio.playback.PodcastGapSettings
import java.math.RoundingMode
import app.viberadio.diagnostics.DiagnosticsExporter
import app.viberadio.diagnostics.ExportOutcome
import app.viberadio.ui.theme.VibeRadioTheme
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The way in to settings: a small, faint gear in the top corner, the same on both pages.
 * Drawn in the rule colour like the idle thumbs, so it's there when you look for it and
 * otherwise reads as part of the page edge.
 */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = SettingsIcons.Gear,
            contentDescription = stringResource(R.string.settings_open),
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The pages inside settings. Enum, so it survives rotation and process death as-is. */
private enum class SettingsPage {
    Root, Channels, Bluetooth, Audio, Folders, Equalizer, Separation, Podcasts, Debug;

    /** Where back goes from here. */
    val parent: SettingsPage get() = if (this == Folders || this == Equalizer || this == Separation) Audio else Root
}

/** Settings → Debug as the page shows it. */
data class DebugSettingsUi(
    val developerMode: Boolean,
    /** The diagnostics folder's name; null until one is picked. */
    val folderName: String?,
    val folderChosen: Boolean,
    val nightly: Boolean,
    val exporting: Boolean,
    val lastExport: ExportOutcome?,
    /** Audio feature analysis (Similarity plan): progress and the blocks in use. */
    val features: AudioFeatures.Status = AudioFeatures.Status(),
    /** Installed model manifests that can't be used, with the reason. */
    val modelProblems: Map<String, String> = emptyMap(),
)

/** Settings → Bluetooth as the page shows it. */
data class BluetoothSettingsUi(
    val resumeOnConnect: Boolean,
    val permission: BluetoothPermissionUi,
)

/** Whether the app may hear about Bluetooth connections (the "Nearby devices" permission on Android 12+). */
enum class BluetoothPermissionUi { Granted, Needed, Denied }

/**
 * The settings panel. A full page over the pager, closed by its back arrow or the system back
 * gesture. Subpages open in place; back returns to the list first.
 */
@Composable
fun SettingsScreen(
    channels: List<ChannelNameUi>,
    onRename: (id: String, name: String) -> Unit,
    bluetooth: BluetoothSettingsUi,
    onResumeOnConnect: (Boolean) -> Unit,
    onAllowBluetooth: () -> Unit,
    audio: AudioSettings,
    /** Applies the change if valid; false (nothing changed) if not. */
    onAudioChange: ((AudioSettings) -> AudioSettings) -> Boolean,
    onRestoreAudio: () -> Unit,
    /** Settings → Audio → Equalizer: both tone curves flat. (Bands change through [onAudioChange].) */
    onRestoreTone: () -> Unit,
    /** Settings → Audio → Separation EQ (developer mode only): the module and its parameters. */
    separation: SeparationSettings,
    onSelectSeparation: (id: String) -> Unit,
    /** Sets one parameter of a module if the value is accepted; false (nothing changed) if not. */
    onSeparationParam: (moduleId: String, key: String, value: Double) -> Boolean,
    onRestoreSeparation: () -> Unit,
    /** Settings → Audio → Library folders. */
    libraryFolders: LibraryFoldersUi,
    onToggleFolder: (FolderNode) -> Unit,
    /** Settings → Podcasts → Podcast folder: played as a show, left out of the songs. */
    podcastFolder: PodcastFolderUi,
    /** A folder path, or null for no podcast folder. */
    onPodcastFolder: (String?) -> Unit,
    /** Settings → Podcasts: the gap rule between episodes and its parameters (shown in developer mode only). */
    podcastGap: PodcastGapSettings,
    onSelectGapRule: (id: String) -> Unit,
    /** Sets one parameter of a rule if the value is accepted; false (nothing changed) if not. */
    onGapParam: (ruleId: String, key: String, value: Double) -> Boolean,
    onRestoreGap: () -> Unit,
    /** The version line at the foot of the list; tapping it seven times turns developer mode on. */
    version: String,
    onVersionTap: () -> Unit,
    debug: DebugSettingsUi,
    onDeveloperMode: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onExportNow: () -> Unit,
    onShareExport: () -> Unit,
    onNightly: (Boolean) -> Unit,
    /** Settings → Debug → Audio features: analyse now, or stop. */
    onAnalyse: () -> Unit,
    onAddModels: () -> Unit,
    onRemoveModels: () -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    // Composed after MainActivity's handler, so it takes back first while a subpage is open.
    BackHandler(enabled = page != SettingsPage.Root) { page = page.parent }
    // Turning developer mode off hides its pages.
    LaunchedEffect(debug.developerMode) {
        if (!debug.developerMode && page == SettingsPage.Debug) page = SettingsPage.Root
        if (!debug.developerMode && page == SettingsPage.Separation) page = SettingsPage.Audio
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (page) {
                SettingsPage.Root -> {
                    SettingsHeader(stringResource(R.string.settings_title), onBack = onClose)
                    SettingsLink(
                        title = stringResource(R.string.settings_channels),
                        summary = stringResource(R.string.settings_channels_summary),
                        onClick = { page = SettingsPage.Channels },
                    )
                    Rule()
                    SettingsLink(
                        title = stringResource(R.string.settings_bluetooth),
                        summary = stringResource(R.string.settings_bluetooth_summary),
                        onClick = { page = SettingsPage.Bluetooth },
                    )
                    Rule()
                    SettingsLink(
                        title = stringResource(R.string.settings_audio),
                        summary = stringResource(R.string.settings_audio_summary),
                        onClick = { page = SettingsPage.Audio },
                    )
                    Rule()
                    SettingsLink(
                        title = stringResource(R.string.settings_podcasts),
                        summary = stringResource(R.string.settings_podcasts_summary),
                        onClick = { page = SettingsPage.Podcasts },
                    )
                    Rule()
                    if (debug.developerMode) {
                        SettingsLink(
                            title = stringResource(R.string.settings_debug),
                            summary = stringResource(R.string.settings_debug_summary),
                            onClick = { page = SettingsPage.Debug },
                        )
                        Rule()
                    }
                    Spacer(Modifier.weight(1f))
                    VersionLine(version, onVersionTap)
                }
                SettingsPage.Channels -> {
                    SettingsHeader(stringResource(R.string.settings_channels), onBack = { page = SettingsPage.Root })
                    ChannelNameList(channels, onRename)
                }
                SettingsPage.Bluetooth -> {
                    SettingsHeader(stringResource(R.string.settings_bluetooth), onBack = { page = SettingsPage.Root })
                    BluetoothSettingsList(bluetooth, onResumeOnConnect, onAllowBluetooth)
                }
                SettingsPage.Audio -> {
                    SettingsHeader(stringResource(R.string.settings_audio), onBack = { page = SettingsPage.Root })
                    AudioSettingsList(
                        audio,
                        onAudioChange,
                        onRestoreAudio,
                        developerMode = debug.developerMode,
                        onFolders = { page = SettingsPage.Folders },
                        onEqualizer = { page = SettingsPage.Equalizer },
                        onSeparation = { page = SettingsPage.Separation },
                    )
                }
                SettingsPage.Equalizer -> {
                    SettingsHeader(stringResource(R.string.settings_equalizer), onBack = { page = SettingsPage.Audio })
                    ToneSettingsList(audio, onAudioChange, onRestoreTone)
                }
                SettingsPage.Separation -> {
                    SettingsHeader(stringResource(R.string.settings_separation), onBack = { page = SettingsPage.Audio })
                    SeparationSettingsList(separation, onSelectSeparation, onSeparationParam, onRestoreSeparation)
                }
                SettingsPage.Folders -> {
                    SettingsHeader(stringResource(R.string.settings_folders), onBack = { page = SettingsPage.Audio })
                    LibraryFolderList(libraryFolders, onToggleFolder)
                }
                SettingsPage.Podcasts -> {
                    SettingsHeader(stringResource(R.string.settings_podcasts), onBack = { page = SettingsPage.Root })
                    PodcastSettingsList(debug.developerMode, podcastFolder, onPodcastFolder, podcastGap, onSelectGapRule, onGapParam, onRestoreGap)
                }
                SettingsPage.Debug -> {
                    SettingsHeader(stringResource(R.string.settings_debug), onBack = { page = SettingsPage.Root })
                    DebugSettingsList(debug, onDeveloperMode, onPickFolder, onExportNow, onShareExport, onNightly, onAnalyse, onAddModels, onRemoveModels)
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 12.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = SettingsIcons.Back,
                contentDescription = stringResource(R.string.settings_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    Rule()
}

/** A row that opens a subpage: title, a faint line on what's there, and a quiet chevron. */
@Composable
private fun SettingsLink(title: String, summary: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("\u203A", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Rule() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 24.dp))
}

/** Settings → Bluetooth: one switch, plus a line asking for the permission while it's missing. */
@Composable
private fun BluetoothSettingsList(
    bluetooth: BluetoothSettingsUi,
    onResumeOnConnect: (Boolean) -> Unit,
    onAllowBluetooth: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = bluetooth.resumeOnConnect,
                    role = Role.Switch,
                    onValueChange = onResumeOnConnect,
                )
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    stringResource(R.string.settings_bluetooth_resume),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(R.string.settings_bluetooth_resume_about),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The row handles the toggle, so the switch itself is display-only.
            Switch(checked = bluetooth.resumeOnConnect, onCheckedChange = null)
        }
        Rule()
        if (bluetooth.resumeOnConnect && bluetooth.permission != BluetoothPermissionUi.Granted) {
            Text(
                stringResource(
                    if (bluetooth.permission == BluetoothPermissionUi.Denied) {
                        R.string.settings_bluetooth_permission_denied
                    } else {
                        R.string.settings_bluetooth_permission
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAllowBluetooth)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Rule()
        }
    }
}

/** The app version, faint at the foot of settings. Tapping it is the way into developer mode. */
@Composable
private fun VersionLine(version: String, onTap: () -> Unit) {
    Text(
        stringResource(R.string.settings_version, version),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/** A row with a title, a faint line under it, and a switch; the whole row toggles. */
@Composable
private fun SwitchRow(title: String, about: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The row handles the toggle, so the switch itself is display-only.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A tappable row with a title and a faint line under it. */
@Composable
private fun ActionRow(title: String, about: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline,
        )
        Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Settings → Debug (developer mode only): where diagnostics go, exporting them now or nightly,
 * sharing the latest, audio feature analysis and its models, and the switch that turns
 * developer mode back off.
 */
@Composable
private fun DebugSettingsList(
    debug: DebugSettingsUi,
    onDeveloperMode: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onExportNow: () -> Unit,
    onShareExport: () -> Unit,
    onNightly: (Boolean) -> Unit,
    onAnalyse: () -> Unit,
    onAddModels: () -> Unit,
    onRemoveModels: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.settings_debug_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Rule()
            ActionRow(
                title = stringResource(R.string.settings_debug_folder),
                about = when {
                    !debug.folderChosen -> stringResource(R.string.settings_debug_folder_none)
                    debug.folderName != null -> debug.folderName
                    else -> stringResource(R.string.settings_debug_folder_unnamed)
                },
                onClick = onPickFolder,
            )
            Rule()
            ActionRow(
                title = stringResource(R.string.settings_debug_export),
                about = exportStatus(debug),
                enabled = !debug.exporting,
                onClick = onExportNow,
            )
            Rule()
            ActionRow(
                title = stringResource(R.string.settings_debug_share),
                about = stringResource(R.string.settings_debug_share_about),
                enabled = !debug.exporting,
                onClick = onShareExport,
            )
            Rule()
            SwitchRow(
                title = stringResource(R.string.settings_debug_nightly),
                about = stringResource(
                    if (debug.folderChosen) R.string.settings_debug_nightly_about else R.string.settings_debug_nightly_no_folder,
                ),
                checked = debug.nightly,
                onChange = onNightly,
            )
            Rule()
            val features = debug.features
            ActionRow(
                title = stringResource(R.string.settings_debug_features),
                about = if (features.running) {
                    stringResource(R.string.settings_debug_features_running, features.analysed, features.total)
                } else {
                    stringResource(
                        R.string.settings_debug_features_about,
                        features.analysed,
                        features.total,
                        features.blocks.joinToString(", ") { "${it.id} (${it.size})" },
                    )
                },
                onClick = onAnalyse,
            )
            Rule()
            ActionRow(
                title = stringResource(R.string.settings_debug_models_add),
                about = (listOf(stringResource(R.string.settings_debug_models_add_about)) +
                    debug.modelProblems.map { (file, problem) -> stringResource(R.string.settings_debug_models_problem, file, problem) })
                    .joinToString("\n"),
                onClick = onAddModels,
            )
            Rule()
            ActionRow(
                title = stringResource(R.string.settings_debug_models_remove),
                about = stringResource(R.string.settings_debug_models_remove_about),
                onClick = onRemoveModels,
            )
            Rule()
            SwitchRow(
                title = stringResource(R.string.settings_debug_developer_mode),
                about = stringResource(R.string.settings_debug_developer_mode_about),
                checked = debug.developerMode,
                onChange = onDeveloperMode,
            )
            Rule()
        }
    }
}

/** The line under "Export now": under way, or how the last one went. */
@Composable
private fun exportStatus(debug: DebugSettingsUi): String {
    val last = debug.lastExport
    return when {
        debug.exporting -> stringResource(R.string.settings_debug_exporting)
        last == null -> stringResource(R.string.settings_debug_export_never)
        last.bundle == null -> last.error.orEmpty()
        last.savedToFolder -> stringResource(R.string.settings_debug_export_saved, last.bundle)
        last.error == DiagnosticsExporter.NO_FOLDER -> stringResource(R.string.settings_debug_export_staged, last.bundle)
        else -> stringResource(R.string.settings_debug_export_failed, last.bundle, last.error.orEmpty())
    }
}

/** One adjustable figure on Settings → Audio: how it reads, shows and writes back. */
internal enum class AudioField(@StringRes val label: Int, @StringRes val unit: Int) {
    Target(R.string.settings_audio_target, R.string.unit_lufs),
    Mixing(R.string.settings_audio_mixing, R.string.unit_db),
    Ramp(R.string.settings_audio_ramp, R.string.unit_seconds),
    ;

    /** The value in the unit shown. */
    fun read(s: AudioSettings): Double = when (this) {
        Target -> s.targetLufs.toDouble()
        Mixing -> s.mixingFactorDb.toDouble()
        Ramp -> s.rampMs / 1000.0
    }

    /**
     * [s] with this figure set to [value], in the unit shown. A target loudness is always below
     * full scale, and a mixing factor always a cut, so their sign is ignored.
     */
    fun write(s: AudioSettings, value: Double): AudioSettings = when (this) {
        Target -> s.copy(targetLufs = -abs(value).toFloat())
        Mixing -> s.copy(mixingFactorDb = -abs(value).toFloat())
        Ramp -> s.copy(rampMs = (value * 1000).roundToLong())
    }

    /** No trailing zeros; a minus sign, not a hyphen. */
    fun format(s: AudioSettings): String {
        val v = read(s)
        val rounded = (v * 100).roundToLong() / 100.0
        return BigDecimal(rounded.toString()).stripTrailingZeros().toPlainString().replace('-', '\u2212')
    }
}

/**
 * Settings → Audio: links to the library folders, the equalizer and (developer mode) the
 * separation EQ, then the leveling target, mixing factor and ramp time, each tappable to change,
 * and a way back to their defaults.
 */
@Composable
private fun AudioSettingsList(
    audio: AudioSettings,
    onAudioChange: ((AudioSettings) -> AudioSettings) -> Boolean,
    onRestore: () -> Unit,
    developerMode: Boolean,
    onFolders: () -> Unit,
    onEqualizer: () -> Unit,
    onSeparation: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<AudioField?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SettingsLink(
                title = stringResource(R.string.settings_folders),
                summary = stringResource(R.string.settings_folders_summary),
                onClick = onFolders,
            )
            Rule()
            SettingsLink(
                title = stringResource(R.string.settings_equalizer),
                summary = stringResource(R.string.settings_equalizer_summary),
                onClick = onEqualizer,
            )
            Rule()
            if (developerMode) {
                SettingsLink(
                    title = stringResource(R.string.settings_separation),
                    summary = stringResource(R.string.settings_separation_summary),
                    onClick = onSeparation,
                )
                Rule()
            }
        }
        item {
            Text(
                stringResource(R.string.settings_audio_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Rule()
        }
        items(AudioField.entries.toList(), key = { it.name }) { field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { editing = field })
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Text(
                    stringResource(field.label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    field.format(audio) + " " + stringResource(field.unit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (field.read(audio) == field.read(AudioSettings.DEFAULT)) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                )
            }
            Rule()
        }
        item {
            TextButton(
                onClick = onRestore,
                enabled = audio.figuresRestored() != audio,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.settings_audio_restore)) }
        }
    }

    editing?.let { field ->
        AudioFieldDialog(
            field = field,
            current = audio,
            onSave = { value -> onAudioChange { field.write(it, value) } },
            onDismiss = { editing = null },
        )
    }
}

/** Edit one figure. Out-of-range values are refused. */
@Composable
private fun AudioFieldDialog(field: AudioField, current: AudioSettings, onSave: (Double) -> Boolean, onDismiss: () -> Unit) {
    FigureDialog(
        key = field.name,
        title = stringResource(field.label),
        unit = stringResource(field.unit),
        current = field.format(current),
        default = field.format(AudioSettings.DEFAULT),
        invalidText = stringResource(R.string.settings_audio_invalid),
        onSave = onSave,
        onDismiss = onDismiss,
    )
}

/**
 * Edit one number: a text field with its unit, the default under it, and [invalidText] instead
 * when [onSave] refuses the value. [key] keeps the draft per figure across rotation.
 */
@Composable
private fun FigureDialog(
    key: String,
    title: String,
    unit: String?,
    current: String,
    default: String,
    invalidText: String,
    onSave: (Double) -> Boolean,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(key) { mutableStateOf(current.replace('\u2212', '-')) }
    var invalid by rememberSaveable(key) { mutableStateOf(false) }
    fun save() {
        val value = draft.trim().replace('\u2212', '-').replace(',', '.').toDoubleOrNull()
        if (value != null && value.isFinite() && onSave(value)) onDismiss() else invalid = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it.take(12)
                    invalid = false
                },
                suffix = if (unit != null) {
                    { Text(unit) }
                } else {
                    null
                },
                isError = invalid,
                supportingText = {
                    Text(
                        if (invalid) {
                            invalidText
                        } else {
                            stringResource(R.string.settings_audio_default, default, unit.orEmpty()).trimEnd()
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text(stringResource(R.string.settings_rename_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Labels and number formats for the tone EQ and the separation modules; the modules themselves are pure. */
internal object EqStrings {
    /** "31 Hz", "1 kHz". */
    @Composable
    fun band(i: Int): String {
        val hz = ToneBands.HZ[i]
        return if (hz < 1_000) {
            stringResource(R.string.eq_band_hz, plain(hz, 0))
        } else {
            stringResource(R.string.eq_band_khz, plain(hz / 1_000, 1))
        }
    }

    @StringRes
    fun stream(stream: AudioStream): Int = when (stream) {
        AudioStream.MUSIC -> R.string.eq_stream_music
        AudioStream.PODCAST -> R.string.eq_stream_podcast
    }

    @StringRes
    fun title(moduleId: String): Int = when (moduleId) {
        SeparationEq.BandPassNotch.ID -> R.string.separation_band_pass_notch
        SeparationEq.Graphic.ID -> R.string.separation_graphic
        else -> throw IllegalArgumentException(moduleId)
    }

    @StringRes
    fun about(moduleId: String): Int = when (moduleId) {
        SeparationEq.BandPassNotch.ID -> R.string.separation_band_pass_notch_about
        SeparationEq.Graphic.ID -> R.string.separation_graphic_about
        else -> throw IllegalArgumentException(moduleId)
    }

    @Composable
    fun label(key: String): String = when (key) {
        SeparationEq.BandPassNotch.PODCAST_LOW -> stringResource(R.string.settings_audio_podcast_low)
        SeparationEq.BandPassNotch.PODCAST_HIGH -> stringResource(R.string.settings_audio_podcast_high)
        SeparationEq.BandPassNotch.MUSIC_LOW -> stringResource(R.string.settings_audio_music_low)
        SeparationEq.BandPassNotch.MUSIC_HIGH -> stringResource(R.string.settings_audio_music_high)
        SeparationEq.BandPassNotch.MUSIC_DEPTH -> stringResource(R.string.settings_audio_music_depth)
        else -> {
            val (onStream, index) = SeparationEq.Graphic.parse(key)
            stringResource(R.string.separation_graphic_band, stringResource(stream(onStream)), band(index))
        }
    }

    @StringRes
    fun unit(unit: EqUnit): Int = when (unit) {
        EqUnit.Hz -> R.string.unit_hz
        EqUnit.Db -> R.string.unit_db
    }

    /** Hz as whole numbers; dB to two decimals, with a plus on boosts. No trailing zeros; a minus sign, not a hyphen. */
    fun format(unit: EqUnit, value: Double): String = when (unit) {
        EqUnit.Hz -> plain(value, 0)
        EqUnit.Db -> db(value)
    }

    fun db(value: Double): String {
        val text = plain(value, 2)
        return if (value > 0 && text != "0") "+$text" else text
    }

    private fun plain(value: Double, scale: Int): String =
        BigDecimal(value.toString()).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            .replace('-', '−')
            .let { if (it == "−0") "0" else it }
}

/**
 * Settings → Audio → Equalizer: each stream's tone EQ as ten vertical sliders (±12 dB; drag,
 * double-tap for flat, or tap the figure to type one), and a way back to flat. Always in effect, whatever plays; the separation EQ, while
 * both streams play, comes on top.
 */
@Composable
private fun ToneSettingsList(
    audio: AudioSettings,
    onAudioChange: ((AudioSettings) -> AudioSettings) -> Boolean,
    onRestore: () -> Unit,
) {
    // "MUSIC:3", so it survives rotation as a plain string.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.settings_equalizer_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Rule()
        }
        for (stream in AudioStream.entries) {
            val curve = audio.tone(stream)
            item(key = "tone:" + stream.name) {
                Text(
                    stringResource(EqStrings.stream(stream)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                )
                Rule()
            }
            item(key = "tone:" + stream.name + ":bands") {
                GraphicEqSliders(
                    bandsDb = curve.bandsDb,
                    range = ToneCurve.RANGE,
                    onBand = { band, db -> onAudioChange { it.withTone(stream, it.tone(stream).with(band, db)) } },
                    onEdit = { band -> editing = stream.name + ":" + band },
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Rule()
            }
        }
        item {
            TextButton(
                onClick = onRestore,
                enabled = !audio.musicTone.isFlat || !audio.podcastTone.isFlat,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.settings_audio_restore)) }
        }
    }

    editing?.split(':')?.let { (name, index) ->
        val stream = AudioStream.valueOf(name)
        val band = index.toInt()
        FigureDialog(
            key = "tone:$name:$band",
            title = stringResource(EqStrings.stream(stream)) + ", " + EqStrings.band(band),
            unit = stringResource(R.string.unit_db),
            current = EqStrings.db(audio.tone(stream).bandsDb[band].toDouble()).removePrefix("+"),
            default = "0",
            invalidText = stringResource(R.string.settings_equalizer_invalid),
            onSave = { value -> onAudioChange { it.withTone(stream, it.tone(stream).with(band, value.toFloat())) } },
            onDismiss = { editing = null },
        )
    }
}

/** A tappable row: a label, and its value on the right (quieter while it's the default). */
@Composable
private fun FigureRow(label: String, value: String, isDefault: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Settings → Audio → Separation EQ (developer mode only): which module pulls a podcast and the
 * music apart while both play, one radio row per module, then the chosen module's own
 * parameters, each tappable to change (the graphic module's as sliders, like the tone EQ), and
 * a way back to the defaults. Each module keeps its parameters while another is chosen.
 */
@Composable
private fun SeparationSettingsList(
    settings: SeparationSettings,
    onSelect: (id: String) -> Unit,
    onParam: (moduleId: String, key: String, value: Double) -> Boolean,
    onRestore: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val active = settings.active
    val defaults = SeparationEq.all.first { it.id == active.id }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.settings_separation_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Rule()
        }
        items(settings.modules, key = { "module:" + it.id }) { module ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = module.id == settings.selected, role = Role.RadioButton, onClick = { onSelect(module.id) })
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(EqStrings.title(module.id)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(stringResource(EqStrings.about(module.id)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The row handles the choice, so the button itself is display-only.
                RadioButton(selected = module.id == settings.selected, onClick = null)
            }
            Rule()
        }
        item {
            Text(
                stringResource(R.string.settings_podcasts_params, stringResource(EqStrings.title(active.id))),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            )
            Rule()
        }
        if (active is SeparationEq.Graphic) {
            // Per-stream offsets as sliders, like the tone EQ; a figure still opens the dialog.
            val range = SeparationEq.Graphic.RANGE.let { it.start.toFloat()..it.endInclusive.toFloat() }
            for (stream in AudioStream.entries) {
                item(key = active.id + ":" + stream.name) {
                    Text(
                        stringResource(EqStrings.stream(stream)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                    )
                    Rule()
                    GraphicEqSliders(
                        bandsDb = active.offsets(stream).map { it.toFloat() },
                        range = range,
                        onBand = { band, db -> onParam(active.id, SeparationEq.Graphic.key(stream, band), db.toBigDecimal().toDouble()) },
                        onEdit = { band -> editing = SeparationEq.Graphic.key(stream, band) },
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Rule()
                }
            }
        } else items(active.params, key = { active.id + "." + it.key }) { param ->
            FigureRow(
                label = EqStrings.label(param.key),
                value = EqStrings.format(param.unit, active[param.key]) + " " + stringResource(EqStrings.unit(param.unit)),
                isDefault = active[param.key] == defaults[param.key],
                onClick = { editing = param.key },
            )
            Rule()
        }
        item {
            TextButton(
                onClick = onRestore,
                enabled = settings.modules != SeparationEq.all,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.settings_audio_restore)) }
        }
    }

    active.params.firstOrNull { it.key == editing }?.let { param: EqParam ->
        FigureDialog(
            key = active.id + "." + param.key,
            title = EqStrings.label(param.key),
            unit = stringResource(EqStrings.unit(param.unit)),
            current = EqStrings.format(param.unit, active[param.key]).removePrefix("+"),
            default = EqStrings.format(param.unit, defaults[param.key]),
            invalidText = stringResource(
                if (param.unit == EqUnit.Hz) R.string.settings_separation_invalid_hz else R.string.settings_podcasts_invalid,
                EqStrings.format(param.unit, param.range.start),
                EqStrings.format(param.unit, param.range.endInclusive),
            ),
            onSave = { value ->
                // A notch depth is always a cut, so its sign is ignored.
                onParam(active.id, param.key, if (param.key == SeparationEq.BandPassNotch.MUSIC_DEPTH) -abs(value) else value)
            },
            onDismiss = { editing = null },
        )
    }
}

/** Labels for the podcast gap rules and their parameters; the rules themselves are pure. */
internal object GapStrings {
    @StringRes
    fun title(ruleId: String): Int = when (ruleId) {
        PodcastGapRule.TracksByLength.ID -> R.string.gap_rule_tracks_by_length
        PodcastGapRule.MusicRatio.ID -> R.string.gap_rule_music_ratio
        else -> throw IllegalArgumentException(ruleId)
    }

    @StringRes
    fun about(ruleId: String): Int = when (ruleId) {
        PodcastGapRule.TracksByLength.ID -> R.string.gap_rule_tracks_by_length_about
        PodcastGapRule.MusicRatio.ID -> R.string.gap_rule_music_ratio_about
        else -> throw IllegalArgumentException(ruleId)
    }

    @StringRes
    fun label(key: String): Int = when (key) {
        PodcastGapRule.TracksByLength.LONG_MINUTES -> R.string.gap_param_long_minutes
        PodcastGapRule.TracksByLength.LONG_TRACKS -> R.string.gap_param_long_tracks
        PodcastGapRule.TracksByLength.VERY_LONG_MINUTES -> R.string.gap_param_very_long_minutes
        PodcastGapRule.TracksByLength.VERY_LONG_TRACKS -> R.string.gap_param_very_long_tracks
        PodcastGapRule.MusicRatio.RATIO -> R.string.gap_param_ratio
        PodcastGapRule.MusicRatio.FLOOR_SECONDS -> R.string.gap_param_floor_seconds
        PodcastGapRule.MusicRatio.TOLERANCE_PERCENT -> R.string.gap_param_tolerance_percent
        PodcastGapRule.MusicRatio.MAX_MINUTES -> R.string.gap_param_max_minutes
        else -> throw IllegalArgumentException(key)
    }

    /** The unit shown after a value; null for a plain number (the ratio). */
    @StringRes
    fun unit(unit: GapUnit): Int? = when (unit) {
        GapUnit.Minutes -> R.string.unit_minutes
        GapUnit.Tracks -> R.string.unit_tracks
        GapUnit.Seconds -> R.string.unit_seconds
        GapUnit.Percent -> R.string.unit_percent
        GapUnit.Ratio -> null
    }

    /** Whole numbers as such, others to three decimals at most, without trailing zeros. */
    fun format(param: GapParam, value: Double): String {
        val scale = if (param.whole) 0 else 3
        return BigDecimal(value.toString()).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}

/**
 * Settings → Podcasts. Open to everyone: the podcast folder, a folder of audio files played as a
 * show and kept off the song channels. In developer mode it also has the gap rule: which rule sets the gap between episodes on a song channel, one radio row
 * per rule, then the chosen rule's own parameters, each tappable to change, and a way back to the
 * defaults. Each rule keeps its parameters while another is chosen.
 */
@Composable
private fun PodcastSettingsList(
    developerMode: Boolean,
    folder: PodcastFolderUi,
    onFolder: (String?) -> Unit,
    settings: PodcastGapSettings,
    onSelect: (id: String) -> Unit,
    onParam: (ruleId: String, key: String, value: Double) -> Boolean,
    onRestore: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingFolder by rememberSaveable { mutableStateOf(false) }
    val active = settings.active
    val defaults = PodcastGapRule.all.first { it.id == active.id }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            FigureRow(
                label = stringResource(R.string.settings_podcast_folder),
                value = folder.selected ?: stringResource(R.string.settings_podcast_folder_none),
                isDefault = folder.selected == PodcastFolder.DEFAULT_PATH,
                onClick = { choosingFolder = true },
            )
            Text(
                stringResource(R.string.settings_podcast_folder_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            )
            Rule()
        }
        if (!developerMode) {
            item {
                Text(
                    stringResource(R.string.settings_podcasts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                Rule()
            }
            return@LazyColumn
        }
        item {
            Text(
                stringResource(R.string.settings_podcasts_gap),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
            )
            Text(
                stringResource(R.string.settings_podcasts_gap_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Rule()
        }
        items(settings.rules, key = { "rule:" + it.id }) { rule ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = rule.id == settings.selected, role = Role.RadioButton, onClick = { onSelect(rule.id) })
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(GapStrings.title(rule.id)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(stringResource(GapStrings.about(rule.id)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The row handles the choice, so the button itself is display-only.
                RadioButton(selected = rule.id == settings.selected, onClick = null)
            }
            Rule()
        }
        item {
            Text(
                stringResource(R.string.settings_podcasts_params, stringResource(GapStrings.title(active.id))),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            )
            Rule()
        }
        items(active.params, key = { active.id + "." + it.key }) { param ->
            val unit = GapStrings.unit(param.unit)?.let { stringResource(it) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { editing = param.key })
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Text(
                    stringResource(GapStrings.label(param.key)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                )
                Text(
                    listOfNotNull(GapStrings.format(param, active[param.key]), unit).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active[param.key] == defaults[param.key]) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                )
            }
            Rule()
        }
        item {
            TextButton(
                onClick = onRestore,
                enabled = settings.rules != PodcastGapRule.all,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.settings_audio_restore)) }
        }
    }

    if (choosingFolder) {
        PodcastFolderDialog(
            folder = folder,
            onChoose = { path ->
                onFolder(path)
                choosingFolder = false
            },
            onDismiss = { choosingFolder = false },
        )
    }

    active.params.firstOrNull { it.key == editing }?.takeIf { developerMode }?.let { param ->
        FigureDialog(
            key = active.id + "." + param.key,
            title = stringResource(GapStrings.label(param.key)),
            unit = GapStrings.unit(param.unit)?.let { stringResource(it) },
            current = GapStrings.format(param, active[param.key]),
            default = GapStrings.format(param, defaults[param.key]),
            invalidText = stringResource(
                when {
                    param.whole -> R.string.settings_podcasts_invalid_whole
                    param.unit == GapUnit.Minutes -> R.string.settings_podcasts_invalid_minutes
                    else -> R.string.settings_podcasts_invalid
                },
                GapStrings.format(param, param.range.start),
                GapStrings.format(param, param.range.endInclusive),
            ),
            onSave = { value -> onParam(active.id, param.key, value) },
            onDismiss = { editing = null },
        )
    }
}

/** Settings → Podcasts → Podcast folder: no folder, or one of the folders the scan found, one radio row each. */
@Composable
private fun PodcastFolderDialog(folder: PodcastFolderUi, onChoose: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_podcast_folder)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf<String?>(null) + folder.choices).forEach { path ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = path == folder.selected, role = Role.RadioButton, onClick = { onChoose(path) })
                            .padding(vertical = 10.dp),
                    ) {
                        // The row handles the choice, so the button itself is display-only.
                        RadioButton(selected = path == folder.selected, onClick = null)
                        Text(
                            path ?: stringResource(R.string.settings_podcast_folder_none),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The named channels, each tappable to rename. */
/**
 * Settings → Audio → Library folders: the folders that hold audio, as a tree, each with a
 * checkbox. Everything starts checked; unchecking leaves a folder and what's under it out.
 */
@Composable
private fun LibraryFolderList(folders: LibraryFoldersUi, onToggle: (FolderNode) -> Unit) {
    if (!folders.scanned || folders.tree.isEmpty()) {
        Text(
            stringResource(if (folders.scanned) R.string.settings_folders_none else R.string.settings_folders_not_scanned),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        return
    }
    // A lone top folder (usually "Music") starts open, so the first screen isn't one row.
    var expanded by rememberSaveable { mutableStateOf(folders.tree.singleOrNull()?.let { listOf(it.path) } ?: emptyList()) }
    val rows = buildList {
        fun visit(node: FolderNode, depth: Int) {
            add(node to depth)
            if (node.path in expanded) node.children.forEach { visit(it, depth + 1) }
        }
        folders.tree.forEach { visit(it, 0) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.settings_folders_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Rule()
        }
        items(rows, key = { (node, _) -> node.path }) { (node, depth) ->
            val open = node.path in expanded
            FolderRow(
                node = node,
                depth = depth,
                state = folders.states[node.path] ?: FolderState.In,
                open = open,
                onToggle = { onToggle(node) },
                onExpand = { expanded = if (open) expanded - node.path else expanded + node.path },
            )
        }
    }
}

@Composable
private fun FolderRow(node: FolderNode, depth: Int, state: FolderState, open: Boolean, onToggle: () -> Unit, onExpand: () -> Unit) {
    val checked = when (state) {
        FolderState.In -> ToggleableState.On
        FolderState.Partly -> ToggleableState.Indeterminate
        FolderState.Out -> ToggleableState.Off
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .triStateToggleable(state = checked, role = Role.Checkbox, onClick = onToggle)
            .padding(start = 8.dp + 20.dp * depth, end = 24.dp, top = 4.dp, bottom = 4.dp),
    ) {
        // The chevron opens and closes the folder; the rest of the row is its checkbox.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .then(if (node.children.isEmpty()) Modifier else Modifier.clickable(onClick = onExpand)),
        ) {
            if (node.children.isNotEmpty()) {
                Text(
                    if (open) "\u25BE" else "\u25B8",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                node.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (state == FolderState.Out) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onBackground,
            )
            Text(
                pluralStringResource(R.plurals.settings_folders_tracks, node.totalTracks, node.totalTracks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row handles the toggle, so the checkbox itself is display-only.
        TriStateCheckbox(state = checked, onClick = null)
    }
}

@Composable
private fun ChannelNameList(channels: List<ChannelNameUi>, onRename: (id: String, name: String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.settings_channels_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Rule()
        }
        items(channels, key = { it.id }) { channel ->
            ChannelNameRow(channel, onClick = { editing = channel.id })
            Rule()
        }
    }

    channels.firstOrNull { it.id == editing }?.let { channel ->
        RenameDialog(
            channel = channel,
            onSave = { name ->
                onRename(channel.id, name)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ChannelNameRow(channel: ChannelNameUi, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.settings_rename_title), onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(channel.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            stringResource(channel.description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (channel.custom) {
            Text(
                stringResource(R.string.settings_renamed_from, channel.defaultName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Edit one name. Saving it empty puts the default back. */
@Composable
private fun RenameDialog(channel: ChannelNameUi, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(channel.id) { mutableStateOf(channel.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_rename_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.replace('\n', ' ').take(ChannelNames.MAX_LENGTH) },
                placeholder = { Text(channel.defaultName) },
                supportingText = { Text(stringResource(R.string.settings_rename_reset_hint, channel.defaultName)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(draft) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.settings_rename_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Glyphs for settings, inlined like [ThumbIcons] so the app doesn't pull in an icon library.
 * The back arrow is Material Icons "ArrowBack" (Apache 2.0); the gear is generated: eight
 * square-ish teeth around a ring with a hole, filled even-odd.
 */
internal object SettingsIcons {
    val Back: ImageVector = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"),
            fill = SolidColor(Color.Black),
        )
        .build()

    val Gear: ImageVector = ImageVector.Builder("Gear", 24.dp, 24.dp, 24f, 24f)
        .path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            val teeth = 8
            val step = 360.0 / teeth
            fun point(radius: Float, degrees: Double): Pair<Float, Float> {
                val rad = Math.toRadians(degrees - 90)
                return Pair(12f + radius * cos(rad).toFloat(), 12f + radius * sin(rad).toFloat())
            }
            for (i in 0 until teeth) {
                val centre = i * step
                // Root, tip, tip, root: a tooth slightly narrower at the tip than at its base.
                val outline = listOf(
                    point(ROOT, centre - 15),
                    point(TIP, centre - 9),
                    point(TIP, centre + 9),
                    point(ROOT, centre + 15),
                )
                outline.forEachIndexed { index, (x, y) ->
                    if (i == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            close()
            // The hole: a full circle as two half arcs.
            moveTo(12f - HOLE, 12f)
            arcTo(HOLE, HOLE, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f + HOLE, y1 = 12f)
            arcTo(HOLE, HOLE, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f - HOLE, y1 = 12f)
            close()
        }
        .build()

    private const val TIP = 10.5f
    private const val ROOT = 8f
    private const val HOLE = 3.2f
}

private val previewDebug = DebugSettingsUi(
    developerMode = true,
    folderName = "VibeRadio diagnostics",
    folderChosen = true,
    nightly = false,
    exporting = false,
    lastExport = ExportOutcome(atMs = 0, bundle = "2026-10-15_081203", savedToFolder = true),
)

private val previewBluetooth = BluetoothSettingsUi(resumeOnConnect = true, permission = BluetoothPermissionUi.Granted)

private val previewChannels = listOf(
    ChannelNameUi("breeze", "The Breeze", "The Breeze", R.string.channel_breeze_about, custom = false),
    ChannelNameUi("buzz", "Morning coffee", "The Buzz", R.string.channel_buzz_about, custom = true),
    ChannelNameUi("podcasts", "The Voice", "The Voice", R.string.channel_voice_about, custom = false),
)

@Preview(name = "Settings light", showBackground = true)
@Composable
private fun SettingsLightPreview() {
    VibeRadioTheme(darkTheme = false) { SettingsScreen(
            previewChannels,
            onRename = { _, _ -> },
            bluetooth = previewBluetooth,
            onResumeOnConnect = {},
            onAllowBluetooth = {},
            audio = AudioSettings.DEFAULT,
            onAudioChange = { true },
            onRestoreAudio = {},
            onRestoreTone = {},
            separation = SeparationSettings.DEFAULT,
            onSelectSeparation = {},
            onSeparationParam = { _, _, _ -> true },
            onRestoreSeparation = {},
            libraryFolders = previewFolders,
            onToggleFolder = {},
            podcastFolder = PodcastFolderUi.EMPTY,
            onPodcastFolder = {},
            podcastGap = PodcastGapSettings.DEFAULT,
            onSelectGapRule = {},
            onGapParam = { _, _, _ -> true },
            onRestoreGap = {},
            version = "0.4.0",
            onVersionTap = {},
            debug = previewDebug,
            onDeveloperMode = {},
            onPickFolder = {},
            onExportNow = {},
            onShareExport = {},
            onNightly = {},
            onAnalyse = {},
            onAddModels = {},
            onRemoveModels = {},
            onClose = {},
        ) }
}

@Preview(name = "Settings dark", showBackground = true)
@Composable
private fun SettingsDarkPreview() {
    VibeRadioTheme(darkTheme = true) { SettingsScreen(
            previewChannels,
            onRename = { _, _ -> },
            bluetooth = previewBluetooth,
            onResumeOnConnect = {},
            onAllowBluetooth = {},
            audio = AudioSettings.DEFAULT,
            onAudioChange = { true },
            onRestoreAudio = {},
            onRestoreTone = {},
            separation = SeparationSettings.DEFAULT,
            onSelectSeparation = {},
            onSeparationParam = { _, _, _ -> true },
            onRestoreSeparation = {},
            libraryFolders = previewFolders,
            onToggleFolder = {},
            podcastFolder = PodcastFolderUi.EMPTY,
            onPodcastFolder = {},
            podcastGap = PodcastGapSettings.DEFAULT,
            onSelectGapRule = {},
            onGapParam = { _, _, _ -> true },
            onRestoreGap = {},
            version = "0.4.0",
            onVersionTap = {},
            debug = previewDebug,
            onDeveloperMode = {},
            onPickFolder = {},
            onExportNow = {},
            onShareExport = {},
            onNightly = {},
            onAnalyse = {},
            onAddModels = {},
            onRemoveModels = {},
            onClose = {},
        ) }
}

@Preview(name = "Channels", showBackground = true, heightDp = 640)
@Composable
private fun ChannelNamesPreview() {
    VibeRadioTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) { ChannelNameList(previewChannels, onRename = { _, _ -> }) }
    }
}

@Preview(name = "Bluetooth, permission needed", showBackground = true)
@Composable
private fun BluetoothSettingsPreview() {
    VibeRadioTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            BluetoothSettingsList(
                previewBluetooth.copy(permission = BluetoothPermissionUi.Needed),
                onResumeOnConnect = {},
                onAllowBluetooth = {},
            )
        }
    }
}

@Preview(name = "Audio", showBackground = true, heightDp = 640)
@Composable
private fun AudioSettingsPreview() {
    VibeRadioTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AudioSettingsList(
                AudioSettings.DEFAULT.copy(mixingFactorDb = -12f),
                onAudioChange = { true },
                onRestore = {},
                developerMode = true,
                onFolders = {},
                onEqualizer = {},
                onSeparation = {},
            )
        }
    }
}

@Preview(name = "Equalizer", showBackground = true, heightDp = 640)
@Composable
private fun ToneSettingsPreview() {
    VibeRadioTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ToneSettingsList(
                AudioSettings.DEFAULT.copy(musicTone = ToneCurve.FLAT.with(0, 3f).with(9, -2.5f)),
                onAudioChange = { true },
                onRestore = {},
            )
        }
    }
}

@Preview(name = "Separation EQ", showBackground = true, heightDp = 640)
@Composable
private fun SeparationSettingsPreview() {
    VibeRadioTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SeparationSettingsList(
                SeparationSettings.DEFAULT.select(SeparationEq.Graphic.ID),
                onSelect = {},
                onParam = { _, _, _ -> true },
                onRestore = {},
            )
        }
    }
}

private val previewFolders = run {
    val counts = mapOf("Music/Albums/Rock" to 120, "Music/Albums/Jazz" to 45, "Music/Singles" to 12, "Download" to 3)
    val tree = FolderTree.build(counts)
    val exclusions = FolderExclusions.NONE.exclude("Download")
    val states = HashMap<String, FolderState>()
    fun visit(node: FolderNode) {
        states[node.path] = FolderTree.state(node, exclusions, counts)
        node.children.forEach(::visit)
    }
    tree.forEach(::visit)
    LibraryFoldersUi(scanned = true, tree = tree, states = states)
}

@Preview(name = "Library folders", showBackground = true, heightDp = 640)
@Composable
private fun LibraryFoldersPreview() {
    VibeRadioTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) { LibraryFolderList(previewFolders, onToggle = {}) }
    }
}

@Preview(name = "Podcasts", showBackground = true, heightDp = 640)
@Composable
private fun PodcastSettingsPreview() {
    VibeRadioTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            PodcastSettingsList(
                developerMode = true,
                folder = PodcastFolderUi.EMPTY,
                onFolder = {},
                settings = PodcastGapSettings.DEFAULT.select(PodcastGapRule.MusicRatio.ID),
                onSelect = {},
                onParam = { _, _, _ -> true },
                onRestore = {},
            )
        }
    }
}

@Preview(name = "Debug", showBackground = true, heightDp = 640)
@Composable
private fun DebugSettingsPreview() {
    VibeRadioTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            DebugSettingsList(previewDebug, onDeveloperMode = {}, onPickFolder = {}, onExportNow = {}, onShareExport = {}, onNightly = {}, onAnalyse = {}, onAddModels = {}, onRemoveModels = {})
        }
    }
}

@Preview(name = "Gear", showBackground = true)
@Composable
private fun SettingsButtonPreview() {
    VibeRadioTheme(darkTheme = false) { SettingsButton(onClick = {}) }
}
