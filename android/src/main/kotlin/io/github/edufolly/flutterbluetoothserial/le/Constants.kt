package io.github.edufolly.flutterbluetoothserial.le

import io.github.edufolly.flutterbluetoothserial.BuildConfig

object Constants {
    // Values have to be globally unique.
    const val INTENT_ACTION_DISCONNECT = BuildConfig.LIBRARY_PACKAGE_NAME + ".Disconnect"
    const val NOTIFICATION_CHANNEL = BuildConfig.LIBRARY_PACKAGE_NAME + ".Channel"
    const val INTENT_CLASS_MAIN_ACTIVITY = BuildConfig.LIBRARY_PACKAGE_NAME + ".MainActivity"

    // Values have to be unique within each app.
    const val NOTIFY_MANAGER_START_FOREGROUND_SERVICE = 1001
}
