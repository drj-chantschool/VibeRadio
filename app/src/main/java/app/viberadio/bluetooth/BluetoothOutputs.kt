package app.viberadio.bluetooth

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** The audio outputs that count as a Bluetooth device: connect-time resume and the switching title use the same ones. */
object BluetoothOutputs {
    val TYPES: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }

    /** Whether any Bluetooth audio output is connected right now. */
    fun connected(audio: AudioManager): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in TYPES }
}
