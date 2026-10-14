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
import app.viberadio.playback.audio.AudioSettings
import app.viberadio.playback.audio.AudioSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

    /** Settings → Audio: the EQ figures and ramp time. */
    val audioSettings: StateFlow<AudioSettings> = audio.settings

    /** Applies [change] if the result is valid; false if it isn't (nothing changes). */
    fun updateAudio(change: (AudioSettings) -> AudioSettings): Boolean = audio.update(change)

    fun restoreAudioDefaults() = audio.restoreDefaults()

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
