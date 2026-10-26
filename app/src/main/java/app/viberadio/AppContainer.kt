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
import app.viberadio.features.AudioFeatureJob
import app.viberadio.features.AudioFeatures
import app.viberadio.features.ExcerptDecoder
import app.viberadio.features.FeatureModels
import app.viberadio.features.FileAudioFeatureStore
import app.viberadio.features.SpectralFeatures
import app.viberadio.learning.Learner
import app.viberadio.learning.LearningModels
import app.viberadio.library.LibraryFolderSettings
import app.viberadio.library.MediaStoreLibraryScanner
import app.viberadio.library.PodcastFolder
import app.viberadio.library.PodcastFolderSettings
import app.viberadio.library.PrefsLibraryFolderStore
import app.viberadio.library.PrefsPodcastFolderStore
import app.viberadio.library.ContentKind
import app.viberadio.library.Track
import app.viberadio.playback.LastChannelStore
import app.viberadio.playback.LiveRadio
import app.viberadio.playback.FilePodcastEventStore
import app.viberadio.playback.PodcastEventLog
import app.viberadio.playback.PodcastWaitStore
import app.viberadio.playback.PrefsPodcastWaitStore
import app.viberadio.playback.PodcastGapSettingsRepository
import app.viberadio.playback.PrefsLastChannelStore
import app.viberadio.playback.PrefsPodcastGapStore
import app.viberadio.podcasts.EpisodeDownloader
import app.viberadio.podcasts.Feed
import app.viberadio.podcasts.FeedItem
import app.viberadio.podcasts.LocalFeeds
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
import app.viberadio.playback.audio.PrefsSeparationStore
import app.viberadio.playback.audio.SeparationSettingsRepository
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.drop
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
    private val podcastStateFile = File(context.filesDir, "podcast_state.json")
    private val levelsFile = File(context.filesDir, "levels.tsv")

    private val episodeDownloader = EpisodeDownloader(File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes"))

    /** The podcast pipeline (#17): hand-assigned shows, one global queue. Independent of [channelRepository]. */
    val podcastRepository = PodcastRepository(
        store = JsonPodcastStore(podcastsFile, podcastStateFile),
        fetcher = HttpFeedFetcher(),
        downloader = episodeDownloader,
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** A music gap between podcast episodes still owed, kept across restarts of the player. */
    val podcastWaitStore: PodcastWaitStore = PrefsPodcastWaitStore(context)

    /** What the podcast scheduling on song channels decided, for diagnostics. */
    val podcastLog = PodcastEventLog(
        store = FilePodcastEventStore(File(context.filesDir, "podcast_events.tsv")),
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

    /** Settings → Debug → Add feature models: ONNX models with a manifest, in app storage. */
    val featureModels = FeatureModels(File(context.filesDir, "models"))

    private val excerptDecoder = ExcerptDecoder(context)

    /**
     * Audio feature vectors per song (Similarity plan): the built-in signal features plus a block
     * per installed model, and the similarity index the learner borrows evidence through. Add or
     * swap extractors here; nothing else names one. Analysed by [AudioFeatureJob] while charging
     * and idle, or from Settings → Debug.
     */
    val audioFeatures = AudioFeatures(
        store = FileAudioFeatureStore(File(context.filesDir, "features")),
        extractors = { listOf(SpectralFeatures()) + featureModels.extractors() },
        decode = { track, plan -> excerptDecoder.decode(track.uri, track.durationMs, plan) },
        hashOf = { track -> playHistory.hash(track) },
        knownHash = playHistory::hashOf,
        ioDispatcher = Dispatchers.IO,
    ).also { AudioFeatureJob.schedule(appContext) }

    /**
     * Song placement and preference (#14), learned from the thumbs log and spread to similar
     * songs through [audioFeatures]. Swap models in [LearningModels]; nothing else constructs
     * one. Log read off the main thread at startup.
     */
    val learner = Learner(
        models = LearningModels(),
        readEvents = feedbackDatabase.feedbackLog::readAll,
        hashOf = playHistory::hashOf,
        similarity = { audioFeatures.index.value },
    ).also { learner -> appScope.launch(Dispatchers.IO) { learner.load() } }

    /** Settings → Audio → Library folders: folders left out of the library. */
    val libraryFolders = LibraryFolderSettings(
        store = PrefsLibraryFolderStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** Settings → Podcasts → Podcast folder: a folder whose files are a podcast show, not songs. */
    val podcastFolder = PodcastFolderSettings(
        store = PrefsPodcastFolderStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

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
        filter = { tracks -> libraryFolders.awaitExclusions().filter(podcastFolder.awaitFolder().songs(tracks)) },
    ).also { repository ->
        // A folder put back or left out rebuilds the channels from the last scan; the playing queue is left alone.
        appScope.launch { libraryFolders.exclusions.drop(1).collect { repository.regenerate() } }
        appScope.launch { podcastFolder.folder.drop(1).collect { repository.regenerate() } }
        // Stored audio features load after each scan; a new similarity index re-places the songs.
        appScope.launch {
            repository.scanned.filterNotNull().collectLatest {
                repository.state.first { it is ChannelRepository.State.Ready }
                audioFeatures.refresh(songs())
            }
        }
        appScope.launch { audioFeatures.index.drop(1).collect { repository.replace() } }
        // The podcast folder's files become its show on the podcast page, after every scan and folder change.
        appScope.launch {
            combine(repository.scanned.filterNotNull(), podcastFolder.folder) { tracks, folder -> tracks to folder }
                .collectLatest { (tracks, _) ->
                    // The saved choice, not the default it starts on, so a show isn't made only to be removed.
                    val folder = podcastFolder.awaitFolder()
                    podcastRepository.syncLocalFeeds(withContext(Dispatchers.Default) { localFeeds(tracks, folder) })
                }
        }
    }

    /** Every song on the dial's song channels, once each. */
    private fun songs(): List<Track> =
        (channelRepository.state.value as? ChannelRepository.State.Ready)?.channels.orEmpty()
            .flatMap { it.tracks }.distinctBy { it.uri }.filter { it.kind == ContentKind.MUSIC }

    /**
     * Computes missing audio features for every song, scanning the library first if nothing has
     * (the job can start in a fresh process). Cancellable between songs.
     */
    suspend fun analyseSongs() {
        if (channelRepository.state.value == ChannelRepository.State.NotScanned && AudioPermission.isGranted(appContext)) {
            runCatching { channelRepository.refresh() }
        }
        withTimeoutOrNull(SCAN_WAIT_MS) { channelRepository.state.first { it is ChannelRepository.State.Ready || it == ChannelRepository.State.NotScanned } }
        audioFeatures.analyse(songs())
    }

    private var analysis: kotlinx.coroutines.Job? = null

    /** Settings → Debug → Audio features: starts analysing now, or stops a run under way. */
    fun toggleAnalysis() {
        val running = analysis
        if (running != null && running.isActive) {
            running.cancel()
            return
        }
        analysis = appScope.launch { runCatching { analyseSongs() } }
    }

    /** Settings → Debug: copies picked model files in, then re-reads which blocks exist. */
    fun installModels(uris: List<Uri>, done: (Int) -> Unit) {
        appScope.launch {
            val copied = withContext(Dispatchers.IO) { featureModels.install(appContext.contentResolver, uris) }
            audioFeatures.refresh(songs())
            done(copied)
        }
    }

    /** Settings → Debug: removes every installed model; songs fall back to the built-in features. */
    fun removeModels() {
        appScope.launch {
            withContext(Dispatchers.IO) { featureModels.removeAll() }
            audioFeatures.refresh(songs())
        }
    }

    /** The podcast folder as a feed, for the podcast pipeline, which never sees a [Track]. */
    private fun localFeeds(tracks: List<Track>, folder: PodcastFolder): Map<String, Feed> {
        val path = folder.path ?: return emptyMap()
        val items = folder.episodes(tracks).map { track ->
            FeedItem(
                guid = PodcastFolder.episodeKey(track),
                title = track.title,
                publishedMs = track.modifiedSec * 1000,
                audioUrl = track.uri,
                durationMs = track.durationMs,
            )
        }
        return mapOf(LocalFeeds.url(path) to Feed(title = folder.title.orEmpty(), items = items))
    }

    /** #2 and #4 replace this. */
    val streamPlanner: StreamPlanner = WeightedRandomStreamPlanner(
        lastHeardAt = playHistory::lastHeardAt,
        listeningNow = listeningClock::now,
        preference = learner::preferenceWeight,
    )

    /** Settings → Audio → Separation EQ, in developer mode: how a podcast and the music are pulled apart (#2). */
    val separationSettings = SeparationSettingsRepository(
        store = PrefsSeparationStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** Settings → Audio: the leveling target, mixing factor, ramp time and each stream's tone EQ. */
    val audioSettings = AudioSettingsRepository(
        store = PrefsAudioSettingsStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** Settings → Podcasts, in developer mode: the rule for the gap between episodes on a song channel. */
    val podcastGapSettings = PodcastGapSettingsRepository(
        store = PrefsPodcastGapStore(context),
        scope = appScope,
        ioDispatcher = Dispatchers.IO,
    )

    /** Whether a podcast is playing over the music; the music's EQ follows it. */
    val mix = MixState()

    /**
     * The separation module in effect right now: the selected one while a podcast plays over the
     * music, null otherwise (with one stream, separation is flat). Recorded with each thumbs press.
     */
    fun separationInEffect(): String? = if (mix.podcastOverMusic) separationSettings.settings.value.selected else null

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
        // Downloaded episodes and the podcast folder's files; streamed ones aren't measured.
        if (EpisodeMediaId.episodeId(request.mediaId) != null && (request.uri.startsWith("file:") || request.uri.startsWith("content:"))) {
            return request.mediaId
        }
        return null
    }

    /** Each stream's chain: its gain (leveling #1, mixing factor #2), then its EQ (tone, then separation #2). #5 and #6 add processors here. */
    val audioProcessors: AudioProcessorProvider =
        AudioProcessorProvider.leveledEq(
            leveling,
            mix,
            settings = { audioSettings.settings.value },
            separation = { separationSettings.settings.value.active },
        )

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
        // A fresh process reads the podcast library from disk first.
        withTimeoutOrNull(SCAN_WAIT_MS) { podcastRepository.loaded.first { it } }
        val podcasts = podcastRepository.library.value
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
                separation = separationSettings.settings.value,
                podcastGap = podcastGapSettings.settings.value,
                resumeOnBluetooth = bluetoothSettings.awaitResumeOnConnect(),
                libraryFolders = libraryFolders.awaitExclusions(),
                podcastFolder = podcastFolder.awaitFolder().path,
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
                podcastStateJson = runCatching { AtomicFile(podcastStateFile).readFully().decodeToString() }.getOrNull(),
                podcasts = podcasts,
                downloadedEpisodes = podcastRepository.downloaded.value,
                podcastEvents = runCatching { podcastLog.readAll() }.getOrDefault(emptyList()),
                audioFeatures = audioFeatures.snapshot(),
            )
        }
    }

    private companion object {
        /** How long an export waits for a library scan already under way. */
        const val SCAN_WAIT_MS = 60_000L
    }
}
