package app.viberadio.playback

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import app.viberadio.bluetooth.BluetoothOutputs
import app.viberadio.dial.ChannelNames
import app.viberadio.podcasts.Episode
import app.viberadio.podcasts.PodcastLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the media session tells controllers is playing. Over a Bluetooth audio output (headphones,
 * a car's display) the title and artist are switched every [OnAirTitles.INTERVAL_MS] between the
 * song, the episode playing over it and the episode's current chapter ([OnAirTitles]), the primary
 * stream's title wearing the channel's short tag ([ChannelNames.shortOf]). Without Bluetooth
 * nothing is changed.
 *
 * The session is built on this player and the callback keeps working on the real one. Only
 * [getMediaMetadata] differs; it also carries the item's real title and artist as extras
 * ([ChannelRequest.EXTRA_STREAM_TITLE]) so the app's own screen doesn't follow the switching.
 * The chapter comes from the file's ID3 chapters, else timestamps in the show notes
 * ([Chapters.fromDescription]).
 */
@OptIn(UnstableApi::class)
class OnAirTitlePlayer(
    private val base: Player,
    /** Plays the podcast over the music. */
    private val overlay: Player,
    /** The episode on [overlay], or null. */
    private val overlayEpisode: StateFlow<String?>,
    private val library: () -> PodcastLibrary,
    private val names: () -> ChannelNames,
    private val audio: AudioManager,
    private val handler: Handler,
    private val scope: CoroutineScope,
) : ForwardingPlayer(base) {

    private val listeners = LinkedHashMap<Player.Listener, Intercept>()
    private var bluetooth = false
    private var lines: List<TitleLine> = emptyList()
    private var step = 0

    /** What the lines are about (the playing item and the overlay episode); a change starts the switching over. */
    private var key: String? = null
    private var published: TitleLine? = null
    private var ticking = false
    private var watching: Job? = null
    private val notesChapters = HashMap<String, List<Chapter>>()

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
    }

    private val tick = object : Runnable {
        override fun run() {
            step++
            publish()
            if (ticking) handler.postDelayed(this, OnAirTitles.INTERVAL_MS)
        }
    }

    /** Starts following the Bluetooth outputs and the overlay episode. */
    fun start() {
        audio.registerAudioDeviceCallback(devices, handler)
        bluetooth = BluetoothOutputs.connected(audio)
        watching = scope.launch { overlayEpisode.collect { publish() } }
    }

    fun detach() {
        watching?.cancel()
        watching = null
        audio.unregisterAudioDeviceCallback(devices)
        handler.removeCallbacks(tick)
        ticking = false
    }

    private fun devicesChanged() {
        val now = BluetoothOutputs.connected(audio)
        if (now == bluetooth) return
        bluetooth = now
        publish()
    }

    override fun addListener(listener: Player.Listener) {
        val wrapped = Intercept(listener)
        listeners[listener] = wrapped
        super.addListener(wrapped)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    override fun getMediaMetadata(): MediaMetadata {
        val real = base.mediaMetadata
        val shown = shown() ?: return real
        val extras = Bundle(real.extras ?: Bundle.EMPTY).apply {
            putString(ChannelRequest.EXTRA_STREAM_TITLE, real.title?.toString())
            putString(ChannelRequest.EXTRA_STREAM_ARTIST, real.artist?.toString())
        }
        return real.buildUpon().setTitle(shown.title).setArtist(shown.artist).setExtras(extras).build()
    }

    private fun shown(): TitleLine? = if (bluetooth) OnAirTitles.at(lines, step) else null

    /** Recomputes what is on air; tells the session if the title it shows changed. */
    private fun publish() {
        refresh()
        val now = shown()
        if (now == published) return
        published = now
        val metadata = getMediaMetadata()
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        for (listener in listeners.values.toList()) {
            listener.inner.onMediaMetadataChanged(metadata)
            listener.inner.onEvents(this, events)
        }
    }

    private fun refresh() {
        val item = base.currentMediaItem
        val soloEpisode = EpisodeMediaId.episodeId(item)
        val overOverlay = if (soloEpisode == null) overlayEpisode.value else null
        val newKey = "${item?.mediaId}|${overlayEpisode.value}"
        if (newKey != key) {
            key = newKey
            step = 0
        }
        lines = if (!bluetooth || item == null) emptyList() else compose(item, soloEpisode, overOverlay)
        updateTicker()
    }

    private fun compose(item: MediaItem, soloEpisode: String?, overlayEpisodeId: String?): List<TitleLine> {
        val library = library()
        val channelId = item.mediaMetadata.extras?.getString(ChannelRequest.EXTRA_CHANNEL_ID)
        val tag = channelId?.let { names().shortOf(it, item.mediaMetadata.station?.toString()) }
        val song = if (soloEpisode == null) {
            base.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }?.let {
                TitleLine(it, base.mediaMetadata.artist?.toString()?.takeIf(String::isNotBlank))
            }
        } else {
            null
        }
        val episode = soloEpisode?.let { titles(library, it, base) } ?: overlayEpisodeId?.let { titles(library, it, overlay) }
        return OnAirTitles.lines(tag, song, episode)
    }

    private fun titles(library: PodcastLibrary, episodeId: String, player: Player): EpisodeTitles? {
        val episode = library.episode(episodeId) ?: return null
        return EpisodeTitles(episode.title, library.podcast(episode.feedUrl)?.title, chapterAt(episode, player))
    }

    private fun chapterAt(episode: Episode, player: Player): String? {
        val chapters = id3Chapters(player.currentTracks).ifEmpty {
            notesChapters.getOrPut(episode.id) { Chapters.fromDescription(episode.description) }
        }
        return Chapters.at(chapters, player.currentPosition)
    }

    private fun id3Chapters(tracks: Tracks): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                for (j in 0 until metadata.length()) {
                    val frame = metadata.get(j) as? ChapterFrame ?: continue
                    val title = (0 until frame.subFrameCount).map { frame.getSubFrame(it) }
                        .filterIsInstance<TextInformationFrame>()
                        .firstOrNull { it.id == "TIT2" }?.values?.firstOrNull()
                    if (!title.isNullOrBlank()) chapters += Chapter(frame.startTimeMs.toLong(), title)
                }
            }
        }
        return chapters.sortedBy { it.startMs }
    }

    /** Switching runs while there is more than one title to show and something is playing. */
    private fun updateTicker() {
        val playing = base.playWhenReady &&
            (base.playbackState == Player.STATE_READY || base.playbackState == Player.STATE_BUFFERING)
        val want = bluetooth && playing && lines.size > 1
        if (want && !ticking) {
            ticking = true
            handler.postDelayed(tick, OnAirTitles.INTERVAL_MS)
        } else if (!want && ticking) {
            ticking = false
            handler.removeCallbacks(tick)
        }
    }

    /**
     * Brings the lines up to date before a listener hears about an item or metadata change, so
     * the session never reads the title of the item before.
     */
    private inner class Intercept(val inner: Player.Listener) : Player.Listener by inner {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            refresh()
            published = shown()
            inner.onMediaMetadataChanged(getMediaMetadata())
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refresh()
            inner.onMediaItemTransition(mediaItem, reason)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            inner.onEvents(player, events)
        }
    }
}
