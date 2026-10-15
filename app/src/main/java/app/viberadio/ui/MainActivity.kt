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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

/**
 * Two pages, swiped between (#20): the song page (the dial) opens first; the podcast page is
 * one swipe away. Both act on the same channels. A faint gear in the top corner of either page
 * opens settings over them; back closes it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibeRadioTheme {
                val pager = rememberPagerState(pageCount = { PAGE_COUNT })
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            SONG_PAGE -> SongPage()
                            else -> PodcastPage()
                        }
                    }
                    PageDots(
                        current = pager.currentPage,
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
        val podcastGap by viewModel.podcastGap.collectAsStateWithLifecycle()
        val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
        val exporting by viewModel.exporting.collectAsStateWithLifecycle()

        val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            // Keep access across restarts, so the nightly export can write there too.
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                .onSuccess { viewModel.setFolder(uri) }
                .onFailure { showToast(getString(R.string.settings_debug_folder_refused)) }
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
    private fun PodcastPage() {
        val viewModel: PodcastViewModel = viewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        LifecycleResumeEffect(viewModel) {
            viewModel.onResume()
            onPauseOrDispose { }
        }
        PodcastScreen(
            state = state,
            onDraftChange = viewModel::onDraftChange,
            onAdd = viewModel::addFeed,
            onSetChannel = viewModel::setChannel,
            onSetPauseMusic = viewModel::setPauseMusic,
            onTogglePlayed = viewModel::togglePlayed,
            onMarkOlderPlayed = viewModel::markOlderPlayed,
            onSetSpeed = viewModel::setSpeed,
            onRemove = viewModel::remove,
        )
    }

    private companion object {
        const val PAGE_COUNT = 2
        const val SONG_PAGE = 0
    }
}

/** Two quiet dots: which page you're on, nothing to tap. */
@Composable
private fun PageDots(current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.pages_description, current + 1, 2)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        repeat(2) { index ->
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
