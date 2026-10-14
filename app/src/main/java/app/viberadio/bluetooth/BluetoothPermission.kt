package app.viberadio.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * What it takes to hear about Bluetooth connections. API 31+ needs the runtime "Nearby devices"
 * permission (BLUETOOTH_CONNECT); below that the install-time BLUETOOTH permission covers it.
 */
object BluetoothPermission {
    /** The runtime permission to request, or null where none is needed. */
    val name: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    fun isGranted(context: Context): Boolean =
        name?.let { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ?: true
}
