package app.viberadio.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.viberadio.library.AudioPermission
import app.viberadio.ui.theme.VibeRadioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibeRadioTheme {
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
                )
            }
        }
    }
}
