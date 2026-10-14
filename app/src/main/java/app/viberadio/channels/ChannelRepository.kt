package app.viberadio.channels

import app.viberadio.library.LibraryScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the current channel list, shared by the UI and [app.viberadio.playback.PlaybackService].
 *
 * [place] reworks the generated channels before they're published (song placement, #14). It's
 * applied on every [refresh] and again on [replace], without rescanning, when what it reads changes.
 * Blocking; always called on [ioDispatcher].
 */
class ChannelRepository(
    private val scanner: LibraryScanner,
    private val generator: ChannelGenerator,
    private val ioDispatcher: CoroutineDispatcher,
    private val place: (List<Channel>) -> List<Channel> = { it },
) {
    sealed interface State {
        data object NotScanned : State
        data object Scanning : State
        data class Ready(val channels: List<Channel>) : State
    }

    private val _state = MutableStateFlow<State>(State.NotScanned)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The generator's output from the last scan, before [place]. */
    @Volatile
    private var generated: List<Channel>? = null

    suspend fun refresh() {
        if (_state.value is State.NotScanned) _state.value = State.Scanning
        val channels = withContext(ioDispatcher) {
            generator.generate(scanner.scan()).also { generated = it }.let(place)
        }
        _state.value = State.Ready(channels)
    }

    /** Re-applies [place] to the last scan, e.g. after new feedback. Does nothing before the first scan. */
    suspend fun replace() {
        val last = generated ?: return
        val channels = withContext(ioDispatcher) { place(last) }
        if (generated === last) _state.value = State.Ready(channels)
    }

    fun channel(id: String): Channel? =
        (state.value as? State.Ready)?.channels?.firstOrNull { it.id == id }
}
