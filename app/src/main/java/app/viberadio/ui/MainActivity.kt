package app.viberadio.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.viberadio.library.AudioPermission
import app.viberadio.ui.theme.VibeRadioTheme

/**
 * Two pages, swiped between (#20): the song page (the dial) opens first; the podcast page is
 * one swipe away. Both act on the same channels.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibeRadioTheme {
                val pager = rememberPagerState(pageCount = { PAGE_COUNT })
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
            onThumb = viewModel::press,
        )
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
            onSetOnAir = viewModel::setOnAir,
            onTogglePlayed = viewModel::togglePlayed,
            onMarkOlderPlayed = viewModel::markOlderPlayed,
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
