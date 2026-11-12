package io.github.edufolly.flutterbluetoothserial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Fixes the PlatformException "One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be
 * specified when a receiver isn't being registered exclusively for system broadcasts"
 * on devices with Android 12 or higher when the app is built with Flutter 3.22.0 or higher.
 */
object BluetoothContextCompat {
    @JvmStatic
    fun registerReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }
}
