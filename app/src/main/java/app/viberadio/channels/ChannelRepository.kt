package app.viberadio.channels

import app.viberadio.library.LibraryScanner
import app.viberadio.library.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single source of truth for the current channel list, shared by the UI and [app.viberadio.playback.PlaybackService].
 *
 * [place] reworks the generated channels before they're published (song placement, #14). It's
 * applied on every [refresh] and again on [replace], without rescanning, when what it reads changes.
 * Blocking; always called on [ioDispatcher].
 *
 * [filter] drops what isn't in the library (left-out folders) before the generator sees the scan;
 * [regenerate] re-runs it, the generator and [place] on the last scan when it changes.
 */
class ChannelRepository(
    private val scanner: LibraryScanner,
    private val generator: ChannelGenerator,
    private val ioDispatcher: CoroutineDispatcher,
    private val place: (List<Channel>) -> List<Channel> = { it },
    private val filter: suspend (List<Track>) -> List<Track> = { it },
) {
    sealed interface State {
        data object NotScanned : State
        data object Scanning : State
        data class Ready(val channels: List<Channel>) : State
    }

    private val _state = MutableStateFlow<State>(State.NotScanned)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _scanned = MutableStateFlow<List<Track>?>(null)

    /** Everything the last scan found, before [filter]; null before the first scan. */
    val scanned: StateFlow<List<Track>?> = _scanned.asStateFlow()

    /** The generator's output from the last scan, before [place]. */
    @Volatile
    private var generated: List<Channel>? = null

    suspend fun refresh() {
        if (_state.value is State.NotScanned) _state.value = State.Scanning
        val tracks = withContext(ioDispatcher) { scanner.scan() }
        _scanned.value = tracks
        generate(tracks)
    }

    /** Re-applies [filter], the generator and [place] to the last scan. Does nothing before the first scan. */
    suspend fun regenerate() {
        generate(_scanned.value ?: return)
    }

    private val generation = AtomicInteger()

    private suspend fun generate(tracks: List<Track>) {
        val run = generation.incrementAndGet()
        val kept = filter(tracks)
        val (output, channels) = withContext(ioDispatcher) { generator.generate(kept).let { it to place(it) } }
        // A newer scan or regeneration started meanwhile; it publishes its own result.
        if (generation.get() != run) return
        generated = output
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
