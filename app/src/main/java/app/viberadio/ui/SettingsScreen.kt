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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.viberadio.dial.ChannelNames
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
private enum class SettingsPage { Root, Channels, Bluetooth, Audio }

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
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    // Composed after MainActivity's handler, so it takes back first while a subpage is open.
    BackHandler(enabled = page != SettingsPage.Root) { page = SettingsPage.Root }

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
                    AudioSettingsList(audio, onAudioChange, onRestoreAudio)
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

/** One adjustable figure on Settings → Audio: how it reads, shows and writes back. */
internal enum class AudioField(@StringRes val label: Int, @StringRes val unit: Int) {
    Target(R.string.settings_audio_target, R.string.unit_lufs),
    Mixing(R.string.settings_audio_mixing, R.string.unit_db),
    PodcastLow(R.string.settings_audio_podcast_low, R.string.unit_hz),
    PodcastHigh(R.string.settings_audio_podcast_high, R.string.unit_hz),
    MusicLow(R.string.settings_audio_music_low, R.string.unit_hz),
    MusicHigh(R.string.settings_audio_music_high, R.string.unit_hz),
    MusicDepth(R.string.settings_audio_music_depth, R.string.unit_db),
    Ramp(R.string.settings_audio_ramp, R.string.unit_seconds),
    ;

    /** The value in the unit shown. */
    fun read(s: AudioSettings): Double = when (this) {
        Target -> s.targetLufs.toDouble()
        Mixing -> s.mixingFactorDb.toDouble()
        PodcastLow -> s.podcastLowHz.toDouble()
        PodcastHigh -> s.podcastHighHz.toDouble()
        MusicLow -> s.musicLowHz.toDouble()
        MusicHigh -> s.musicHighHz.toDouble()
        MusicDepth -> s.musicDepthDb.toDouble()
        Ramp -> s.rampMs / 1000.0
    }

    /**
     * [s] with this figure set to [value], in the unit shown. A target loudness is always below
     * full scale, and a depth or mixing factor always a cut, so their sign is ignored.
     */
    fun write(s: AudioSettings, value: Double): AudioSettings = when (this) {
        Target -> s.copy(targetLufs = -abs(value).toFloat())
        Mixing -> s.copy(mixingFactorDb = -abs(value).toFloat())
        PodcastLow -> s.copy(podcastLowHz = value.toFloat())
        PodcastHigh -> s.copy(podcastHighHz = value.toFloat())
        MusicLow -> s.copy(musicLowHz = value.toFloat())
        MusicHigh -> s.copy(musicHighHz = value.toFloat())
        MusicDepth -> s.copy(musicDepthDb = -abs(value).toFloat())
        Ramp -> s.copy(rampMs = (value * 1000).roundToLong())
    }

    /** No trailing zeros; a minus sign, not a hyphen. */
    fun format(s: AudioSettings): String {
        val v = read(s)
        val rounded = if (this == Ramp || this == MusicDepth || this == Target || this == Mixing) (v * 100).roundToLong() / 100.0 else v.roundToLong().toDouble()
        return BigDecimal(rounded.toString()).stripTrailingZeros().toPlainString().replace('-', '\u2212')
    }
}

/**
 * Settings → Audio: the leveling target, then how a podcast is mixed over music (mixing factor
 * and EQ), figure by figure, each tappable to change, and a way back to the defaults.
 */
@Composable
private fun AudioSettingsList(
    audio: AudioSettings,
    onAudioChange: ((AudioSettings) -> AudioSettings) -> Boolean,
    onRestore: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<AudioField?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
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
                enabled = audio != AudioSettings.DEFAULT,
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

/** Edit one figure. Out-of-range values, or a low edge not below its high edge, are refused. */
@Composable
private fun AudioFieldDialog(field: AudioField, current: AudioSettings, onSave: (Double) -> Boolean, onDismiss: () -> Unit) {
    var draft by rememberSaveable(field) { mutableStateOf(field.format(current).replace('\u2212', '-')) }
    var invalid by rememberSaveable(field) { mutableStateOf(false) }
    fun save() {
        val value = draft.trim().replace('\u2212', '-').replace(',', '.').toDoubleOrNull()
        if (value != null && value.isFinite() && onSave(value)) onDismiss() else invalid = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.label)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it.take(12)
                    invalid = false
                },
                suffix = { Text(stringResource(field.unit)) },
                isError = invalid,
                supportingText = {
                    Text(
                        if (invalid) {
                            stringResource(R.string.settings_audio_invalid)
                        } else {
                            stringResource(R.string.settings_audio_default, field.format(AudioSettings.DEFAULT), stringResource(field.unit))
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

/** The named channels, each tappable to rename. */
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
            AudioSettingsList(AudioSettings.DEFAULT.copy(musicDepthDb = -12f), onAudioChange = { true }, onRestore = {})
        }
    }
}

@Preview(name = "Gear", showBackground = true)
@Composable
private fun SettingsButtonPreview() {
    VibeRadioTheme(darkTheme = false) { SettingsButton(onClick = {}) }
}
