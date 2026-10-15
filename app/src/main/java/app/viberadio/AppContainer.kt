package app.viberadio

import android.content.Context
import app.viberadio.bluetooth.BluetoothSettings
import app.viberadio.bluetooth.PrefsBluetoothSettingsStore
import app.viberadio.channels.ChannelGenerator
import app.viberadio.channels.ChannelRepository
import app.viberadio.dial.ChannelLineup
import app.viberadio.dial.ChannelNamesRepository
import app.viberadio.dial.PrefsChannelNameStore
import app.viberadio.feedback.FeedbackDatabase
import app.viberadio.feedback.FeedbackRecorder
import app.viberadio.feedback.ListeningClock
import app.viberadio.feedback.PlayHistory
import app.viberadio.learning.Learner
import app.viberadio.learning.LearningModels
import app.viberadio.library.MediaStoreLibraryScanner
import app.viberadio.playback.LastChannelStore
import app.viberadio.playback.LiveRadio
import app.viberadio.playback.PrefsLastChannelStore
import app.viberadio.podcasts.EpisodeDownloader
import app.viberadio.podcasts.HttpFeedFetcher
import app.viberadio.podcasts.JsonPodcastStore
import app.viberadio.podcasts.PodcastRepository
import app.viberadio.ui.LineupStrings
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import app.viberadio.playback.StreamPlanner
import app.viberadio.playback.WeightedRandomStreamPlanner
import app.viberadio.playback.audio.AudioProcessorProvider
import app.viberadio.playback.audio.AudioSettingsRepository
import app.viberadio.playback.audio.FileLevelStore
import app.viberadio.playback.audio.LevelRequest
import app.viberadio.playback.audio.Leveling
import app.viberadio.playback.audio.LoudnessAnalyzer
import app.viberadio.playback.EpisodeMediaId
import app.viberadio.playback.TrackMediaId
import app.viberadio.playback.audio.MixState
import app.viberadio.playback.audio.PrefsAudioSettingsStore
import app.viberadio.diagnostics.ChannelNameRow
import app.viberadio.diagnostics.DiagnosticsExporter
import app.viberadio.diagnostics.DiagnosticsSettings
import app.viberadio.diagnostics.DiagnosticsSnapshot
import app.viberadio.diagnostics.NightlyExportJob
import app.viberadio.diagnostics.PrefsDiagnosticsStore
import app.viberadio.library.AudioPermission
import android.util.AtomicFile
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manual dependency wiring, shared by the UI and the playback service (same process).
 * Swap implementations here when the issues land; nothing else should construct these.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Lives as long as the process: feed refreshes, downloads and saves outlast any screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the named channels are called: the defaults, or the user's names from settings. */
    val channelNames = ChannelNamesRepository(
        defaults = LineupStrings.defaultNames(context),
        store = PrefsChannelNameStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** Settings → Bluetooth: resume when a Bluetooth audio device connects. */
    val bluetoothSettings = BluetoothSettings(
        store = PrefsBluetoothSettingsStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** The channel that played last, across restarts; Bluetooth resume tunes it when nothing is loaded. */
    val lastChannel: LastChannelStore = PrefsLastChannelStore(context)

    // App-specific storage: no permission needed, removed with the app.
    private val podcastsFile = File(context.filesDir, "podcasts.json")
    private val levelsFile = File(context.filesDir, "levels.tsv")

    private val episodeDownloader = EpisodeDownloader(File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes"))

    /** The podcast pipeline (#17): hand-assigned shows, one global queue. Independent of [channelRepository]. */
    val podcastRepository = PodcastRepository(
        store = JsonPodcastStore(podcastsFile),
        fetcher = HttpFeedFetcher(),
        downloader = episodeDownloader,
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /**
     * Per-song state keyed on each file's content hash, so it survives moves, renames and rescans.
     * In internal storage, so it's included in backups. Imports the tab-separated feedback.log
     * from earlier builds on first open.
     */
    private val feedbackDatabase = FeedbackDatabase(context, legacyFile = File(context.filesDir, "feedback.log"))

    private val openTrack: (String) -> InputStream =
        { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri) }

    /** Thumbs feedback on songs (#13): an append-only log. */
    val feedback = FeedbackRecorder(
        log = feedbackDatabase.feedbackLog,
        open = openTrack,
        ioDispatcher = Dispatchers.IO,
    )

    /** Song listening time, which the shuffle measures "since last heard" on. Loaded at startup; saved off the main thread. */
    val listeningClock = ListeningClock(
        store = feedbackDatabase.listeningClock,
        save = { total -> appScope.launch(Dispatchers.IO) { runCatching { feedbackDatabase.listeningClock.write(total) } } },
    ).also { clock -> appScope.launch(Dispatchers.IO) { clock.load() } }

    /** When each song last played; the shuffle weights by it. Loaded off the main thread at startup. */
    val playHistory = PlayHistory(
        store = feedbackDatabase.playHistory,
        hashStore = feedbackDatabase.trackHashes,
        open = openTrack,
        ioDispatcher = Dispatchers.IO,
        listeningClock = listeningClock::now,
    ).also { history -> appScope.launch(Dispatchers.IO) { history.load() } }

    /** What each song channel last aired, so tuning back in can join a song still on air. Loaded at startup. */
    val liveRadio = LiveRadio(
        store = feedbackDatabase.channelAirings,
        hashOf = playHistory::hashOf,
        ioDispatcher = Dispatchers.IO,
    ).also { radio -> appScope.launch(Dispatchers.IO) { radio.load() } }

    /**
     * Song placement and preference (#14), learned from the thumbs log. Swap models in
     * [LearningModels]; nothing else constructs one. Log read off the main thread at startup.
     */
    val learner = Learner(
        models = LearningModels(),
        readEvents = feedbackDatabase.feedbackLog::readAll,
        hashOf = playHistory::hashOf,
    ).also { learner -> appScope.launch(Dispatchers.IO) { learner.load() } }

    val channelRepository = ChannelRepository(
        scanner = MediaStoreLibraryScanner(context.contentResolver),
        generator = ChannelGenerator(
            // Generated channels fill the five mood slots (The Breeze … The Hall) in order.
            maxChannels = ChannelLineup.moods.size,
            slotIds = ChannelLineup.moods,
            labels = ChannelGenerator.Labels(
                all = context.getString(R.string.channel_all),
                other = context.getString(R.string.channel_other),
                allSongs = context.getString(R.string.channel_shuffle),
            ),
        ),
        ioDispatcher = Dispatchers.IO,
        place = learner::place,
    )

    /** #2 and #4 replace this. */
    val streamPlanner: StreamPlanner = WeightedRandomStreamPlanner(
        lastHeardAt = playHistory::lastHeardAt,
        listeningNow = listeningClock::now,
        preference = learner::preferenceWeight,
    )

    /** Settings → Audio: the EQ figures for mixing a podcast over music, and the ramp time. */
    val audioSettings = AudioSettingsRepository(
        store = PrefsAudioSettingsStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** Whether a podcast is playing over the music; the music's EQ follows it. */
    val mix = MixState()

    private val loudnessAnalyzer = LoudnessAnalyzer(context)

    /**
     * Auto track leveling (#1): one fixed gain per song or episode, from its measured loudness.
     * Songs are keyed on their content hash, downloaded episodes on their media ID; streamed
     * episodes aren't measured (they play at 0 dB until downloaded).
     */
    val leveling = Leveling(
        store = FileLevelStore(levelsFile),
        keyOf = { request -> levelKey(request) },
        measure = { request -> loudnessAnalyzer.measure(request.uri) },
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    ).also { leveling ->
        // Measure downloaded episodes ahead, so they start at their level.
        appScope.launch {
            podcastRepository.downloaded.collect { ids ->
                leveling.request(ids.map { id -> LevelRequest(EpisodeMediaId.of(id), "file://${episodeDownloader.file(id).absolutePath}") })
            }
        }
    }

    private suspend fun levelKey(request: LevelRequest): String? {
        TrackMediaId.trackId(request.mediaId)?.let { id ->
            val channels = (channelRepository.state.value as? ChannelRepository.State.Ready)?.channels ?: return null
            val track = channels.firstNotNullOfOrNull { channel -> channel.tracks.firstOrNull { it.id == id } } ?: return null
            return playHistory.hash(track)?.let { "song:$it" }
        }
        if (EpisodeMediaId.episodeId(request.mediaId) != null && request.uri.startsWith("file:")) return request.mediaId
        return null
    }

    /** Each stream's chain: its gain (leveling #1, mixing factor #2), then its EQ (#2). #5 and #6 add processors here. */
    val audioProcessors: AudioProcessorProvider =
        AudioProcessorProvider.leveledEq(leveling, mix) { audioSettings.settings.value }

    /** Developer mode and Settings → Debug. */
    val diagnosticsSettings = DiagnosticsSettings(
        store = PrefsDiagnosticsStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    ).also { settings ->
        // Keep the nightly job in step with the switch (and with developer mode, which turns it off).
        appScope.launch(Dispatchers.IO) {
            settings.await()
            settings.prefs.map { it.nightly }.distinctUntilChanged().collect { on -> NightlyExportJob.sync(appContext, on) }
        }
    }

    /** Settings → Debug → Export: everything below, as a bundle of CSV and JSON files. */
    val diagnostics = DiagnosticsExporter(
        context = context,
        settings = diagnosticsSettings,
        snapshot = ::diagnosticsSnapshot,
        ioDispatcher = Dispatchers.IO,
    )

    private suspend fun diagnosticsSnapshot(): DiagnosticsSnapshot {
        // The nightly job can run in a fresh process, before anything scanned the library.
        if (channelRepository.state.value == ChannelRepository.State.NotScanned && AudioPermission.isGranted(appContext)) {
            runCatching { channelRepository.refresh() }
        }
        val channels = withTimeoutOrNull(SCAN_WAIT_MS) {
            channelRepository.state.first { it is ChannelRepository.State.Ready || it == ChannelRepository.State.NotScanned }
        } as? ChannelRepository.State.Ready
        val names = channelNames.names.value
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val tracks = channels?.channels.orEmpty().flatMap { it.tracks }.distinctBy { it.uri }
            DiagnosticsSnapshot(
                appVersion = runCatching {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                }.getOrNull() ?: "unknown",
                exportedAtMs = now,
                utcOffsetMinutes = TimeZone.getDefault().getOffset(now) / 60_000,
                audio = audioSettings.settings.value,
                resumeOnBluetooth = bluetoothSettings.awaitResumeOnConnect(),
                channelNames = ChannelLineup.ids.map { id ->
                    ChannelNameRow(id = id, defaultName = names.defaults[id].orEmpty(), name = names.of(id).orEmpty())
                },
                lastChannelId = runCatching { lastChannel.read() }.getOrNull(),
                libraryScanned = channels != null,
                channels = channels?.channels.orEmpty(),
                trackHashes = tracks.mapNotNull { track -> playHistory.hashOf(track)?.let { track.uri to it } }.toMap(),
                feedback = feedbackDatabase.feedbackLog.readAll(),
                playHistory = runCatching { feedbackDatabase.playHistory.readAll() }.getOrDefault(emptyMap()),
                listeningClockMs = runCatching { feedbackDatabase.listeningClock.read() }.getOrDefault(0L),
                airings = runCatching { feedbackDatabase.channelAirings.readAll() }.getOrDefault(emptyList()),
                levels = runCatching { FileLevelStore(levelsFile).readAll() }.getOrDefault(emptyMap()),
                podcastsJson = runCatching { AtomicFile(podcastsFile).readFully().decodeToString() }.getOrNull(),
            )
        }
    }

    private companion object {
        /** How long an export waits for a library scan already under way. */
        const val SCAN_WAIT_MS = 60_000L
    }
}
