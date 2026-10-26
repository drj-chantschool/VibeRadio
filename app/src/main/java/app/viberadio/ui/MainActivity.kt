package app.viberadio.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.viberadio.R
import app.viberadio.bluetooth.BluetoothPermission
import app.viberadio.diagnostics.DevModeUnlock
import app.viberadio.library.AudioPermission
import app.viberadio.ui.theme.VibeRadioTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Two pages, swiped between (#20): the song page (the dial) opens first; the podcast page is
 * one swipe away. Both act on the same channels. A long press on an episode in the podcast
 * page's Up next or History adds a third page with its details, gone again once the pager is
 * back on the podcast page. A faint gear in the top corner of any page opens settings over
 * them; back closes it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibeRadioTheme {
                // The episode on the details page; null while there's no such page.
                var detailEpisode by rememberSaveable { mutableStateOf<String?>(null) }
                val pager = rememberPagerState(pageCount = { if (detailEpisode != null) 3 else 2 })
                val scope = rememberCoroutineScope()
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                // The one show open on the podcast page. Held here, not on the page, so leaving
                // the page closes it even though the pager drops the page once it's off screen.
                var openShow by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(pager.currentPage) {
                    if (pager.currentPage == SONG_PAGE) openShow = null
                }
                // Once the pager comes to rest short of it, not while a swipe is still running.
                LaunchedEffect(pager) {
                    snapshotFlow { pager.isScrollInProgress to pager.currentPage }.collect { (scrolling, page) ->
                        if (!scrolling && page < DETAIL_PAGE) detailEpisode = null
                    }
                }
                LaunchedEffect(detailEpisode) {
                    if (detailEpisode != null && pager.currentPage != DETAIL_PAGE) pager.animateScrollToPage(DETAIL_PAGE)
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            SONG_PAGE -> SongPage()
                            PODCAST_PAGE -> PodcastPage(
                                openShow = openShow,
                                onOpenShow = { openShow = it },
                                onOpenEpisode = { detailEpisode = it },
                            )
                            else -> EpisodePage(detailEpisode)
                        }
                    }
                    BackHandler(enabled = !settingsOpen && pager.currentPage == DETAIL_PAGE) {
                        scope.launch { pager.animateScrollToPage(PODCAST_PAGE) }
                    }
                    PageDots(
                        current = pager.currentPage,
                        count = pager.pageCount,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
                    )
                    if (settingsOpen) {
                        BackHandler { settingsOpen = false }
                        SettingsPage(onClose = { settingsOpen = false })
                    } else {
                        SettingsButton(
                            onClick = { settingsOpen = true },
                            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SongPage() {
        val viewModel: RadioViewModel = viewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
            viewModel::onPermissionResult,
        )
        LifecycleResumeEffect(viewModel) {
            viewModel.onResume()
            onPauseOrDispose { }
        }
        RadioScreen(
            state = state,
            onRequestPermission = { permissionLauncher.launch(AudioPermission.name) },
            onTune = viewModel::tune,
            onPlay = viewModel::play,
            onThumb = viewModel::press,
        )
    }

    @Composable
    private fun SettingsPage(onClose: () -> Unit) {
        val viewModel: SettingsViewModel = viewModel()
        val channels by viewModel.channels.collectAsStateWithLifecycle()
        val resumeOnConnect by viewModel.resumeOnConnect.collectAsStateWithLifecycle()
        val audio by viewModel.audioSettings.collectAsStateWithLifecycle()
        val libraryFolders by viewModel.libraryFolders.collectAsStateWithLifecycle()
        val podcastGap by viewModel.podcastGap.collectAsStateWithLifecycle()
        val podcastFolder by viewModel.podcastFolder.collectAsStateWithLifecycle()
        val separation by viewModel.separation.collectAsStateWithLifecycle()
        val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
        val exporting by viewModel.exporting.collectAsStateWithLifecycle()
        val features by viewModel.features.collectAsStateWithLifecycle()
        val modelProblems by viewModel.modelProblems.collectAsStateWithLifecycle()

        val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            // Keep access across restarts, so the nightly export can write there too.
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                .onSuccess { viewModel.setFolder(uri) }
                .onFailure { showToast(getString(R.string.settings_debug_folder_refused)) }
        }

        val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            viewModel.installModels(uris) { copied -> showToast(getString(R.string.settings_debug_models_added, copied)) }
        }

        // Re-checked on resume, so a grant made in system settings shows up on return.
        var bluetoothGranted by remember { mutableStateOf(BluetoothPermission.isGranted(this)) }
        var bluetoothDenied by rememberSaveable { mutableStateOf(false) }
        LifecycleResumeEffect(Unit) {
            bluetoothGranted = BluetoothPermission.isGranted(this@MainActivity)
            onPauseOrDispose { }
        }
        val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            bluetoothGranted = granted
            bluetoothDenied = !granted
        }
        fun allowBluetooth() {
            val permission = BluetoothPermission.name ?: return
            // Once Android stops showing the prompt, only system settings can grant it.
            if (bluetoothDenied && !shouldShowRequestPermissionRationale(permission)) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            } else {
                bluetoothLauncher.launch(permission)
            }
        }

        SettingsScreen(
            channels = channels,
            onRename = viewModel::rename,
            bluetooth = BluetoothSettingsUi(
                resumeOnConnect = resumeOnConnect,
                permission = when {
                    bluetoothGranted -> BluetoothPermissionUi.Granted
                    bluetoothDenied -> BluetoothPermissionUi.Denied
                    else -> BluetoothPermissionUi.Needed
                },
            ),
            onResumeOnConnect = { on ->
                viewModel.setResumeOnConnect(on)
                if (on && !bluetoothGranted) allowBluetooth()
            },
            onAllowBluetooth = ::allowBluetooth,
            audio = audio,
            onAudioChange = viewModel::updateAudio,
            onRestoreAudio = viewModel::restoreAudioDefaults,
            onRestoreTone = viewModel::restoreTone,
            separation = separation,
            onSelectSeparation = viewModel::selectSeparation,
            onSeparationParam = viewModel::setSeparationParam,
            onRestoreSeparation = viewModel::restoreSeparationDefaults,
            libraryFolders = libraryFolders,
            onToggleFolder = viewModel::toggleFolder,
            podcastFolder = podcastFolder,
            onPodcastFolder = viewModel::setPodcastFolder,
            podcastGap = podcastGap,
            onSelectGapRule = viewModel::selectGapRule,
            onGapParam = viewModel::setGapParam,
            onRestoreGap = viewModel::restoreGapDefaults,
            version = viewModel.appVersion,
            onVersionTap = {
                when (val result = viewModel.tapVersion()) {
                    is DevModeUnlock.Result.Countdown ->
                        showToast(resources.getQuantityString(R.plurals.developer_mode_steps, result.remaining, result.remaining))
                    DevModeUnlock.Result.Unlocked -> showToast(getString(R.string.developer_mode_on))
                    DevModeUnlock.Result.AlreadyOn -> showToast(getString(R.string.developer_mode_already_on))
                    DevModeUnlock.Result.Quiet -> Unit
                }
            },
            debug = DebugSettingsUi(
                developerMode = diagnostics.developerMode,
                folderName = diagnostics.folderName,
                folderChosen = diagnostics.folderUri != null,
                nightly = diagnostics.nightly,
                exporting = exporting,
                lastExport = diagnostics.lastExport,
                features = features,
                modelProblems = modelProblems,
            ),
            onDeveloperMode = viewModel::setDeveloperMode,
            onPickFolder = { runCatching { folderLauncher.launch(null) } },
            onExportNow = viewModel::exportNow,
            onShareExport = {
                viewModel.share { intent ->
                    if (intent == null) showToast(getString(R.string.settings_debug_share_nothing)) else startActivity(intent)
                }
            },
            onNightly = viewModel::setNightly,
            onAnalyse = viewModel::toggleAnalysis,
            onAddModels = { runCatching { modelLauncher.launch(arrayOf("*/*")) } },
            onRemoveModels = viewModel::removeModels,
            onClose = onClose,
        )
    }

    private var toast: Toast? = null

    /** One toast at a time, so a run of taps on the version line counts down in place. */
    private fun showToast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    @Composable
    private fun PodcastPage(openShow: String?, onOpenShow: (String?) -> Unit, onOpenEpisode: (String) -> Unit) {
        val viewModel: PodcastViewModel = viewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
        LifecycleResumeEffect(viewModel) {
            viewModel.onResume()
            onPauseOrDispose { }
        }
        PodcastScreen(
            state = state,
            refreshing = refreshing,
            onRefresh = viewModel::refresh,
            openShow = openShow,
            onOpenShow = onOpenShow,
            onOpenEpisode = onOpenEpisode,
            onDraftChange = viewModel::onDraftChange,
            onAdd = viewModel::addFeed,
            onSetChannel = viewModel::setChannel,
            onSetPauseMusic = viewModel::setPauseMusic,
            onTogglePlayed = viewModel::togglePlayed,
            onMarkOlderPlayed = viewModel::markOlderPlayed,
            onSetSpeed = viewModel::setSpeed,
            onSetPriority = viewModel::setPriority,
            onSetSkipIntro = viewModel::setSkipIntro,
            onRemove = viewModel::remove,
        )
    }

    @Composable
    private fun EpisodePage(episodeId: String?) {
        // The podcast page's view model: same activity, same instance.
        val viewModel: PodcastViewModel = viewModel()
        val details = remember(episodeId) { episodeId?.let(viewModel::episodeDetail) ?: flowOf(null) }
        val episode by details.collectAsStateWithLifecycle(initialValue = null)
        EpisodeScreen(episode)
    }

    private companion object {
        const val SONG_PAGE = 0
        const val PODCAST_PAGE = 1
        const val DETAIL_PAGE = 2
    }
}

/** Quiet dots, one per page: which page you're on, nothing to tap. */
@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.pages_description, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}
