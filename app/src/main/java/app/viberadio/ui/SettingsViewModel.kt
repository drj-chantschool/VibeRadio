package app.viberadio.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.viberadio.VibeRadioApp
import app.viberadio.dial.ChannelLineup
import app.viberadio.dial.ChannelNames
import app.viberadio.dial.ChannelNamesRepository
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
