package io.github.edufolly.flutterbluetoothserial.le

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import io.github.edufolly.flutterbluetoothserial.BuildConfig

object BluetoothUtil {

    fun interface PermissionGrantedCallback {
        fun call()
    }

    /** Caches the name, which is more efficient than [BluetoothDevice] which always does an RPC. */
    class Device @SuppressLint("MissingPermission") constructor(val device: BluetoothDevice) : Comparable<Device> {
        val name: String? = device.name

        override fun equals(other: Any?): Boolean = other is Device && device == other.device

        override fun hashCode(): Int = device.hashCode()

        /** Sorts by name, then address. Named devices come first. */
        override fun compareTo(other: Device): Int {
            val thisValid = !name.isNullOrEmpty()
            val otherValid = !other.name.isNullOrEmpty()
            if (thisValid && otherValid) {
                val ret = name!!.compareTo(other.name!!)
                if (ret != 0) return ret
                return device.address.compareTo(other.device.address)
            }
            if (thisValid) return -1
            if (otherValid) return +1
            return device.address.compareTo(other.device.address)
        }
    }

    /** Android 12 permission handling. */
    private fun showRationaleDialog(fragment: Fragment, listener: DialogInterface.OnClickListener) {
        AlertDialog.Builder(fragment.activity).apply {
            setTitle("bluetooth_permission_title") // DUMMY
            setMessage("bluetooth_permission_grant") // DUMMY
            setNegativeButton("Cancel", null)
            setPositiveButton("Continue", listener)
        }.show()
    }

    private fun showSettingsDialog(fragment: Fragment) {
        val s = fragment.resources.getString(
            fragment.resources.getIdentifier("@android:string/permgrouplab_nearby_devices", null, null),
        )
        AlertDialog.Builder(fragment.activity).apply {
            setTitle("bluetooth_permission_title")
            setMessage("bluetooth_permission_denied : $s")
            setNegativeButton("Cancel", null)
            setPositiveButton("Settings") { _, _ ->
                fragment.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + BuildConfig.LIBRARY_PACKAGE_NAME), // DUMMY not sure about this one
                    ),
                )
            }
        }.show()
    }

    /**
     * CONNECT + SCAN are granted together in the same permission group, so there is actually no need to
     * check/request both, but one never knows.
     */
    fun hasPermissions(fragment: Fragment, requestPermissionLauncher: ActivityResultLauncher<Array<String>>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val activity = fragment.requireActivity()
        val missingPermissions =
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        val showRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
            fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (!missingPermissions) return true
        if (showRationale) {
            showRationaleDialog(fragment) { _, _ -> requestPermissionLauncher.launch(permissions) }
        } else {
            requestPermissionLauncher.launch(permissions)
        }
        return false
    }

    fun onPermissionsResult(fragment: Fragment, grants: Map<String, Boolean>, cb: PermissionGrantedCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val showRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
            fragment.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
        val granted = grants.values.all { it }
        if (granted) {
            cb.call()
        } else if (showRationale) {
            showRationaleDialog(fragment) { _, _ -> cb.call() }
        } else {
            showSettingsDialog(fragment)
        }
    }
}
