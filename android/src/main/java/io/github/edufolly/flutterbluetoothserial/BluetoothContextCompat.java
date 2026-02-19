package io.github.edufolly.flutterbluetoothserial;

import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import androidx.core.content.ContextCompat;

/**
 * Fixed PlatformException "One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be
 * specified when a receiver isn't being registered exclusively for system broadcasts"
 * on devices with Android 12 or higher
 * when app builded with Flutter 3.22.0 or higher
 */
public class BluetoothContextCompat {
    static public void registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
        );
    }
}