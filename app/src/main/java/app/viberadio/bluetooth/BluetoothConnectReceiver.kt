package app.viberadio.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.viberadio.AppContainer
import app.viberadio.VibeRadioApp
import app.viberadio.channels.ChannelRepository
import app.viberadio.library.AudioPermission
import app.viberadio.playback.ChannelRequest
import app.viberadio.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Settings → Bluetooth → "Resume on Bluetooth connection". Registered in the manifest for
 * ACL_CONNECTED, one of the broadcasts still delivered to manifest receivers, so it works with
 * the app closed. Receiving it also lets the app start its playback service from the background
 * for a few seconds (Android 12+), which is the window this works in.
 *
 * A connection only resumes playback once that same device shows up as an audio output, so a
 * watch or keyboard connecting does nothing and sound never starts on the phone speaker first.
 */
class BluetoothConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!BluetoothPermission.isGranted(context)) return
        val app = context.applicationContext
        val container = (app as? VibeRadioApp)?.container ?: return
        val address = deviceAddress(intent)
        val pending = goAsync()
        container.appScope.launch {
            try {
                withTimeoutOrNull(BUDGET_MS) { resume(app, container, address) }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth resume failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    // BLUETOOTH_CONNECT is checked in onReceive before this is called.
    @SuppressLint("MissingPermission")
    private fun deviceAddress(intent: Intent): String? =
        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address

    private suspend fun resume(context: Context, container: AppContainer, address: String?) {
        if (!container.bluetoothSettings.awaitResumeOnConnect()) return
        val lastChannel = withContext(Dispatchers.IO) { runCatching { container.lastChannel.read() }.getOrNull() }
        if (!awaitAudioRoute(context, address)) return

        val controller = connect(context)
        try {
            val state = controller.playbackState
            val playing = controller.playWhenReady && state != Player.STATE_IDLE && state != Player.STATE_ENDED
            val resumable = controller.mediaItemCount > 0 && state != Player.STATE_ENDED
            when (val action = BluetoothResume.decide(enabled = true, playing, resumable, lastChannel)) {
                BluetoothResume.Action.None -> return
                BluetoothResume.Action.Play -> {
                    if (state == Player.STATE_IDLE) controller.prepare()
                    controller.play()
                }
                is BluetoothResume.Action.Tune -> {
                    // With the app closed nothing has scanned the library or loaded the podcasts yet.
                    if (!awaitChannels(context, container)) return
                    controller.setMediaItem(ChannelRequest.mediaItem(action.channelId))
                    controller.prepare()
                    controller.play()
                }
            }
            // Let the commands reach the session before letting go.
            delay(SETTLE_MS)
        } finally {
            controller.release()
        }
    }

    /** Waits for the connecting device to appear as an audio output. */
    private suspend fun awaitAudioRoute(context: Context, address: String?): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return withTimeoutOrNull(ROUTE_WAIT_MS) {
            while (!hasRoute(audio, address)) delay(ROUTE_POLL_MS)
            true
        } ?: false
    }

    private fun hasRoute(audio: AudioManager, address: String?): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { output ->
            output.type in BLUETOOTH_OUTPUTS &&
                // Output addresses arrived in API 28; before that any Bluetooth output will do.
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || BluetoothResume.sameDevice(output.address, address))
        }

    private suspend fun awaitChannels(context: Context, container: AppContainer): Boolean {
        val channels = container.channelRepository
        if (channels.state.value == ChannelRepository.State.NotScanned) {
            if (!AudioPermission.isGranted(context)) return false
            container.appScope.launch { channels.refresh() }
        }
        channels.state.first { it is ChannelRepository.State.Ready }
        container.podcastRepository.loaded.first { it }
        return true
    }

    private suspend fun connect(context: Context): MediaController {
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
        return suspendCancellableCoroutine { cont ->
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                },
                ContextCompat.getMainExecutor(context),
            )
            cont.invokeOnCancellation {
                future.cancel(true)
                MediaController.releaseFuture(future)
            }
        }
    }

    private companion object {
        const val TAG = "BluetoothResume"

        /** Inside the ~10 s Android allows a service start after this broadcast. */
        const val BUDGET_MS = 9_000L

        /** Audio comes up a moment after the Bluetooth link; a device that isn't audio never does. */
        const val ROUTE_WAIT_MS = 6_000L
        const val ROUTE_POLL_MS = 250L
        const val SETTLE_MS = 1_000L

        val BLUETOOTH_OUTPUTS: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }
}
