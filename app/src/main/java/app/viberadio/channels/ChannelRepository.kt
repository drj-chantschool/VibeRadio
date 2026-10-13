package app.viberadio.channels

import app.viberadio.library.LibraryScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the current channel list, shared by the UI and [app.viberadio.playback.PlaybackService].
 */
class ChannelRepository(
    private val scanner: LibraryScanner,
    private val generator: ChannelGenerator,
    private val ioDispatcher: CoroutineDispatcher,
) {
    sealed interface State {
        data object NotScanned : State
        data object Scanning : State
        data class Ready(val channels: List<Channel>) : State
    }

    private val _state = MutableStateFlow<State>(State.NotScanned)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun refresh() {
        if (_state.value is State.NotScanned) _state.value = State.Scanning
        val channels = withContext(ioDispatcher) { generator.generate(scanner.scan()) }
        _state.value = State.Ready(channels)
    }

    fun channel(id: String): Channel? =
        (state.value as? State.Ready)?.channels?.firstOrNull { it.id == id }
}
