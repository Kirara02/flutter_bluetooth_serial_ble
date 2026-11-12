package io.github.edufolly.flutterbluetoothserial

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.util.SparseArray
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import io.github.edufolly.flutterbluetoothserial.le.BluetoothConnectionLE
import java.io.PrintWriter
import java.io.StringWriter
import java.net.NetworkInterface
import java.util.concurrent.Executors

class FlutterBluetoothSerialPlugin : FlutterPlugin, ActivityAware {

    // Plugin
    private var methodChannel: MethodChannel? = null
    private var pendingResultForActivityResult: Result? = null

    // General Bluetooth
    private var bluetoothAdapter: BluetoothAdapter? = null

    // State
    private var stateSink: EventSink? = null
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sink = stateSink ?: return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                // Disconnect all connections.
                for (i in 0 until connections.size()) connections.valueAt(i).disconnect()
                connections.clear()

                sink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothDevice.ERROR))
            }
        }
    }

    // Pairing requests
    private var isPairingRequestHandlerSet = false
    private var bondStateBroadcastReceiver: BroadcastReceiver? = null
    private val pairingRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return // Ignore other actions.

            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
            val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
            Log.d(TAG, "Pairing request (variant $pairingVariant) incoming from ${device.address}")
            when (pairingVariant) {
                // Simplest method - 4 digit number.
                BluetoothDevice.PAIRING_VARIANT_PIN -> handlePinPairing(this, intent, device, pairingVariant)

                // Note: `BluetoothDevice.PAIRING_VARIANT_PASSKEY` seems to be unsupported anyway... Probably is abandoned.
                // See https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/bluetooth/BluetoothDevice.java#1528

                // The displayed passkey on the other device should be the same as received here (3 = consent: just
                // yes or no, without any auth; it can share the code with passkey confirmation since the passed
                // passkey, which is 0 or error at the moment, should not be used anyway).
                BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION, PAIRING_VARIANT_CONSENT ->
                    handleConfirmationPairing(this, intent, device, pairingVariant)

                // 4 = display passkey: the generated key must be entered on the remote device.
                // 5 = display pin: same as the previous, but for a 4 digit pin.
                PAIRING_VARIANT_DISPLAY_PASSKEY, PAIRING_VARIANT_DISPLAY_PIN -> {
                    val pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
                    methodChannel?.invokeMethod(
                        "handlePairingRequest",
                        mapOf("address" to device.address, "variant" to pairingVariant, "pairingKey" to pairingKey),
                    )
                }

                // Note: `PAIRING_VARIANT_OOB_CONSENT` and `PAIRING_VARIANT_PIN_16_DIGITS` seem to be unsupported for now.
                // See https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/bluetooth/BluetoothDevice.java#1559
                else -> Log.w(TAG, "Unknown pairing variant: $pairingVariant")
            }
        }
    }

    // Discovery
    private var discoverySink: EventSink? = null
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    val discoveryResult = mapOf(
                        "address" to device.address,
                        "name" to device.name,
                        "type" to device.type,
                        "isConnected" to checkIsDeviceConnected(device),
                        "bondState" to device.bondState,
                        "rssi" to rssi,
                    )

                    Log.d(TAG, "Discovered ${device.address}")
                    discoverySink?.success(discoveryResult)
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Discovery finished")
                    unregisterQuietly(context, this)
                    bluetoothAdapter?.cancelDiscovery()
                    endDiscoveryStream()
                }
            }
        }
    }

    /** Contains all active connections. Maps the ID of the connection with plugin data channels. */
    private val connections = SparseArray<BluetoothConnection>(2)

    /** Last ID given to any connection, used to avoid duplicate IDs. */
    private var lastConnectionId = 0
    private var activity: Activity? = null
    private var messenger: BinaryMessenger? = null
    private var activeContext: Context? = null

    /** Background executor for blocking Bluetooth operations (connect, write). */
    private val executor = Executors.newCachedThreadPool()

    private fun interface EnsurePermissionsCallback {
        fun onResult(granted: Boolean)
    }

    private var pendingPermissionsEnsureCallbacks: EnsurePermissionsCallback? = null

    // ------------------------------------------------------------------ FlutterPlugin

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.v("FlutterBluetoothSerial", "Attached to engine")
        val messenger = binding.binaryMessenger
        this.messenger = messenger

        methodChannel = MethodChannel(messenger, "$PLUGIN_NAMESPACE/methods").also {
            it.setMethodCallHandler(::onMethodCall)
        }

        EventChannel(messenger, "$PLUGIN_NAMESPACE/state").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink) {
                stateSink = events
                BluetoothContextCompat.registerReceiver(
                    activeContext!!, stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                )
            }

            override fun onCancel(arguments: Any?) {
                stateSink = null
                unregisterQuietly(activeContext, stateReceiver)
            }
        })

        EventChannel(messenger, "$PLUGIN_NAMESPACE/discovery").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink) {
                discoverySink = events
            }

            override fun onCancel(arguments: Any?) {
                Log.d(TAG, "Canceling discovery (stream closed)")
                cancelDiscovery()
            }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
    }

    // ------------------------------------------------------------------ ActivityAware

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        val activity = binding.activity
        this.activity = activity
        val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        binding.addActivityResultListener { requestCode, resultCode, _ ->
            when (requestCode) {
                REQUEST_ENABLE_BLUETOOTH -> {
                    // `0` is the underlying value of `Activity.RESULT_CANCELED`.
                    pendingResultForActivityResult?.success(resultCode != 0)
                    true
                }
                REQUEST_DISCOVERABLE_BLUETOOTH -> {
                    pendingResultForActivityResult?.success(if (resultCode == 0) -1 else resultCode)
                    true
                }
                else -> false
            }
        }
        binding.addRequestPermissionsResultListener { requestCode, _, grantResults ->
            if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
                pendingPermissionsEnsureCallbacks?.onResult(
                    grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED,
                )
                pendingPermissionsEnsureCallbacks = null
                true
            } else {
                false
            }
        }
        activeContext = activity.applicationContext
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    override fun onDetachedFromActivity() {}

    // ------------------------------------------------------------------ helpers

    private fun ensurePermissions(callbacks: EnsurePermissionsCallback) {
        val activity = activity!!
        val granted = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            callbacks.onResult(true)
        } else {
            ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS, REQUEST_COARSE_LOCATION_PERMISSIONS)
            pendingPermissionsEnsureCallbacks = callbacks
        }
    }

    private fun unregisterQuietly(context: Context?, receiver: BroadcastReceiver) {
        try {
            context?.unregisterReceiver(receiver)
        } catch (ex: IllegalArgumentException) {
            // Ignore `Receiver not registered` exception.
        }
    }

    private fun endDiscoveryStream() {
        discoverySink?.endOfStream()
        discoverySink = null
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscovery() {
        unregisterQuietly(activeContext, discoveryReceiver)
        bluetoothAdapter?.cancelDiscovery()
        endDiscoveryStream()
    }

    private fun runOnUi(block: () -> Unit) = activity!!.runOnUiThread(block)

    /** Reads a remote MAC address argument; replies with an error and returns null when invalid. */
    private fun addressArgument(call: MethodCall, result: Result): String? {
        if (!call.hasArgument("address")) {
            result.error("invalid_argument", "argument 'address' not found", null)
            return null
        }
        return try {
            val address = call.argument<String>("address")
            if (!BluetoothAdapter.checkBluetoothAddress(address)) throw ClassCastException()
            address
        } catch (ex: ClassCastException) {
            result.error(
                "invalid_argument",
                "'address' argument is required to be string containing remote MAC address",
                null,
            )
            null
        }
    }

    // ------------------------------------------------------------------ pairing

    @Suppress("DEPRECATION") // `setPin` has no replacement for third party apps.
    @SuppressLint("MissingPermission")
    private fun handlePinPairing(
        receiver: BroadcastReceiver,
        intent: Intent,
        device: BluetoothDevice,
        pairingVariant: Int,
    ) {
        val broadcastResult = receiver.goAsync()
        val arguments = mapOf("address" to device.address, "variant" to pairingVariant)

        methodChannel?.invokeMethod("handlePairingRequest", arguments, object : Result {
            override fun success(result: Any?) {
                Log.d(TAG, result.toString())
                if (result is String) {
                    try {
                        Log.d(TAG, "Trying to set passkey for pairing to $result")
                        device.setPin(result.toByteArray())
                        broadcastResult.abortBroadcast()
                    } catch (ex: Exception) {
                        Log.e(TAG, ex.message ?: "setPin failed")
                        ex.printStackTrace()
                        // @TODO pass the error on
                    }
                } else {
                    Log.d(TAG, "Manual pin pairing in progress")
                    ActivityCompat.startActivity(activity!!, intent, null)
                }
                broadcastResult.finish()
            }

            override fun notImplemented() = throw UnsupportedOperationException()

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) =
                throw UnsupportedOperationException()
        })
    }

    @SuppressLint("MissingPermission")
    private fun handleConfirmationPairing(
        receiver: BroadcastReceiver,
        intent: Intent,
        device: BluetoothDevice,
        pairingVariant: Int,
    ) {
        val pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
        val arguments = mapOf("address" to device.address, "variant" to pairingVariant, "pairingKey" to pairingKey)

        val broadcastResult = receiver.goAsync()
        methodChannel?.invokeMethod("handlePairingRequest", arguments, object : Result {
            override fun success(result: Any?) {
                if (result is Boolean) {
                    try {
                        Log.d(TAG, "Trying to set pairing confirmation to $result (key: $pairingKey)")
                        // @WARN `BLUETOOTH_PRIVILEGED` permission required, but might be unavailable
                        // for third party apps on newer versions of Android.
                        device.setPairingConfirmation(result)
                        broadcastResult.abortBroadcast()
                    } catch (ex: Exception) {
                        Log.e(TAG, ex.message ?: "setPairingConfirmation failed")
                        ex.printStackTrace()
                        // @TODO pass the error on
                    }
                } else {
                    Log.d(TAG, "Manual passkey confirmation pairing in progress (key: $pairingKey)")
                    ActivityCompat.startActivity(activity!!, intent, null)
                }
                broadcastResult.finish()
            }

            override fun notImplemented() = throw UnsupportedOperationException()

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                Log.e(TAG, "$errorCode $errorMessage")
                throw UnsupportedOperationException()
            }
        })
    }

    // ------------------------------------------------------------------ method calls

    @SuppressLint("MissingPermission")
    private fun onMethodCall(call: MethodCall, result: Result) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            if (call.method == "isAvailable") {
                result.success(false)
            } else {
                result.error("bluetooth_unavailable", "bluetooth is not available", null)
            }
            return
        }

        when (call.method) {
            // ---- Adapter settings and general
            "isAvailable" -> result.success(true)

            "isOn", "isEnabled" -> result.success(adapter.isEnabled)

            "openSettings" -> {
                ContextCompat.startActivity(activity!!, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), null)
                result.success(null)
            }

            "requestEnable" ->
                if (!adapter.isEnabled) {
                    pendingResultForActivityResult = result
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    ActivityCompat.startActivityForResult(activity!!, intent, REQUEST_ENABLE_BLUETOOTH, null)
                } else {
                    result.success(true)
                }

            "requestDisable" ->
                if (adapter.isEnabled) {
                    @Suppress("DEPRECATION") // Deprecated since API 33 without a replacement for third party apps.
                    adapter.disable()
                    result.success(true)
                } else {
                    result.success(false)
                }

            "ensurePermissions" -> ensurePermissions { result.success(it) }

            "getState" -> result.success(adapter.state)

            "getAddress" -> result.success(localAddress(adapter))

            "getName" -> result.success(adapter.name)

            "setName" -> {
                if (!call.hasArgument("name")) {
                    result.error("invalid_argument", "argument 'name' not found", null)
                    return
                }
                val name = try {
                    call.argument<String>("name")
                } catch (ex: ClassCastException) {
                    result.error("invalid_argument", "'name' argument is required to be string", null)
                    return
                }
                result.success(adapter.setName(name))
            }

            // ---- Discovering and bonding devices
            "getDeviceBondState" -> {
                val address = addressArgument(call, result) ?: return
                result.success(adapter.getRemoteDevice(address).bondState)
            }

            "removeDeviceBond" -> removeDeviceBond(adapter, call, result)

            "bondDevice" -> bondDevice(adapter, call, result)

            "pairingRequestHandlingEnable" -> {
                if (isPairingRequestHandlerSet) {
                    result.error("logic_error", "pairing request handling is already enabled", null)
                    return
                }
                Log.d(TAG, "Starting listening for pairing requests to handle")
                isPairingRequestHandlerSet = true
                BluetoothContextCompat.registerReceiver(
                    activeContext!!, pairingRequestReceiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST),
                )
            }

            "pairingRequestHandlingDisable" -> {
                isPairingRequestHandlerSet = false
                try {
                    activeContext?.unregisterReceiver(pairingRequestReceiver)
                    Log.d(TAG, "Stopped listening for pairing requests to handle")
                } catch (ex: IllegalArgumentException) {
                    // Ignore `Receiver not registered` exception.
                }
            }

            "getBondedDevices" -> ensurePermissions { granted ->
                if (!granted) {
                    result.error("no_permissions", "discovering other devices requires location access permission", null)
                    return@ensurePermissions
                }
                result.success(
                    adapter.bondedDevices.map { device ->
                        mapOf(
                            "address" to device.address,
                            "name" to device.name,
                            "type" to device.type,
                            "isConnected" to checkIsDeviceConnected(device),
                            "bondState" to BluetoothDevice.BOND_BONDED,
                        )
                    },
                )
            }

            "isDiscovering" -> result.success(adapter.isDiscovering)

            "startDiscovery" -> ensurePermissions { granted ->
                if (!granted) {
                    result.error("no_permissions", "discovering other devices requires location access permission", null)
                    return@ensurePermissions
                }
                Log.d(TAG, "Starting discovery")
                val filter = IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                    addAction(BluetoothDevice.ACTION_FOUND)
                }
                BluetoothContextCompat.registerReceiver(activeContext!!, discoveryReceiver, filter)
                adapter.startDiscovery()
                result.success(null)
            }

            "cancelDiscovery" -> {
                Log.d(TAG, "Canceling discovery")
                cancelDiscovery()
                result.success(null)
            }

            "isDiscoverable" ->
                result.success(adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE)

            "requestDiscoverable" -> {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                if (call.hasArgument("duration")) {
                    try {
                        val duration = call.argument<Int>("duration") as Int
                        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, duration)
                    } catch (ex: ClassCastException) {
                        result.error("invalid_argument", "'duration' argument is required to be integer", null)
                        return
                    }
                }
                pendingResultForActivityResult = result
                ActivityCompat.startActivityForResult(activity!!, intent, REQUEST_DISCOVERABLE_BLUETOOTH, null)
            }

            // ---- Connecting and connection
            "connect" -> connect(adapter, call, result)

            "write" -> write(call, result)

            else -> result.notImplemented()
        }
    }

    /** Local adapter address; falls back to other sources because the system may hide it. */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun localAddress(adapter: BluetoothAdapter): String? {
        var address: String? = adapter.address
        if (address != "02:00:00:00:00:00") return address

        Log.w(TAG, "Local Bluetooth MAC address is hidden by system, trying other options...")

        Log.d(TAG, "Trying to obtain address using Settings Secure bank")
        try {
            // Requires `LOCAL_MAC_ADDRESS`, which could be unavailable for third party applications.
            val value = Settings.Secure.getString(activeContext!!.contentResolver, "bluetooth_address")
                ?: throw NullPointerException("null returned, might be no permissions problem")
            return value
        } catch (ex: Exception) {
            // Ignoring failure (it isn't critical API for most applications).
            Log.d(TAG, "Obtaining address using Settings Secure bank failed")
        }

        Log.d(TAG, "Trying to obtain address using reflection against internal Android code")
        try {
            // This will most likely work, but it is unsafe.
            val serviceField = adapter.javaClass.getDeclaredField("mService")
            serviceField.isAccessible = true
            val managerService = serviceField.get(adapter)
            if (managerService == null) {
                if (!adapter.isEnabled) Log.d(TAG, "Probably failed just because adapter is disabled!")
                throw NullPointerException()
            }
            val value = managerService.javaClass.getMethod("getAddress").invoke(managerService) as String?
                ?: throw NullPointerException()
            Log.d(TAG, "Probably succeed: $value")
            return value
        } catch (ex: Exception) {
            Log.d(TAG, "Obtaining address using reflection against internal Android code failed")
        }

        Log.d(TAG, "Trying to look up address by network interfaces - might be invalid on some devices")
        try {
            // This may return an invalid MAC address (Bluetooth might use another address than WiFi).
            var value: String? = null
            for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
                if (!networkInterface.name.equals("wlan0", ignoreCase = true)) continue
                networkInterface.hardwareAddress?.let { bytes ->
                    value = bytes.joinToString(":") { "%02X".format(it) }
                }
            }
            address = value ?: throw NullPointerException()
        } catch (ex: Exception) {
            Log.w(TAG, "Looking for address by network interfaces failed")
        }
        return address
    }

    private fun removeDeviceBond(adapter: BluetoothAdapter, call: MethodCall, result: Result) {
        val address = addressArgument(call, result) ?: return
        val device = adapter.getRemoteDevice(address)
        when (device.bondState) {
            BluetoothDevice.BOND_BONDING -> {
                result.error("bond_error", "device already bonding", null)
                return
            }
            BluetoothDevice.BOND_NONE -> {
                result.error("bond_error", "device already unbonded", null)
                return
            }
        }

        try {
            val value = device.javaClass.getMethod("removeBond").invoke(device) as Boolean
            result.success(value)
        } catch (ex: Exception) {
            result.error("bond_error", "error while unbonding", exceptionToString(ex))
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondDevice(adapter: BluetoothAdapter, call: MethodCall, result: Result) {
        val address = addressArgument(call, result) ?: return
        if (bondStateBroadcastReceiver != null) {
            result.error("bond_error", "another bonding process is ongoing from local device", null)
            return
        }

        val device = adapter.getRemoteDevice(address)
        when (device.bondState) {
            BluetoothDevice.BOND_BONDING -> {
                result.error("bond_error", "device already bonding", null)
                return
            }
            BluetoothDevice.BOND_BONDED -> {
                result.error("bond_error", "device already bonded", null)
                return
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // @TODO BluetoothDevice.ACTION_PAIRING_CANCEL
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

                val someDevice = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (someDevice != device) return

                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDING -> return // Wait for the true bond result.
                    BluetoothDevice.BOND_BONDED -> result.success(true)
                    BluetoothDevice.BOND_NONE -> result.success(false)
                    else -> result.error("bond_error", "invalid bond state while bonding", null)
                }
                activeContext?.unregisterReceiver(this)
                bondStateBroadcastReceiver = null
            }
        }
        bondStateBroadcastReceiver = receiver

        BluetoothContextCompat.registerReceiver(
            activeContext!!, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
        )

        if (!device.createBond()) {
            result.error("bond_error", "error starting bonding process", null)
        }
    }

    private fun connect(adapter: BluetoothAdapter, call: MethodCall, result: Result) {
        if (!call.hasArgument("address")) {
            result.error("invalid_argument", "argument 'address' not found", null)
            return
        }

        val isLE = call.hasArgument("isLE") && call.argument<Boolean>("isLE") == true
        val address = addressArgument(call, result) ?: return

        val id = ++lastConnectionId
        var readSink: EventSink? = null

        val readChannel = EventChannel(messenger!!, "$PLUGIN_NAMESPACE/read/$id")
        // If canceled by local, disconnects - in other case, by remote, does nothing.
        readChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink) {
                readSink = events
            }

            override fun onCancel(arguments: Any?) {
                connections.get(id)?.disconnect()

                // True dispose.
                executor.execute {
                    readChannel.setStreamHandler(null)
                    connections.remove(id)
                    Log.d(TAG, "Disconnected (id: $id)")
                }
            }
        })

        val onRead = BluetoothConnectionBase.OnReadCallback { data ->
            runOnUi { readSink?.success(data) }
        }
        val onDisconnected = BluetoothConnectionBase.OnDisconnectedCallback { byRemote ->
            runOnUi {
                if (byRemote) {
                    Log.d(TAG, "onDisconnected by remote (id: $id)")
                    readSink?.endOfStream()
                    readSink = null
                } else {
                    Log.d(TAG, "onDisconnected by local (id: $id)")
                }
            }
        }

        val connection: BluetoothConnection =
            if (isLE) BluetoothConnectionLE(onRead, onDisconnected, activeContext!!)
            else BluetoothConnectionClassic(onRead, onDisconnected, adapter)
        connections.put(id, connection)

        Log.d(TAG, "Connecting to $address (id: $id)")

        executor.execute {
            try {
                connection.connect(address)
                runOnUi { result.success(id) }
            } catch (ex: Exception) {
                runOnUi { result.error("connect_error", ex.message, exceptionToString(ex)) }
                connections.remove(id)
            }
        }
    }

    private fun write(call: MethodCall, result: Result) {
        if (!call.hasArgument("id")) {
            result.error("invalid_argument", "argument 'id' not found", null)
            return
        }

        val id = try {
            call.argument<Int>("id") as Int
        } catch (ex: ClassCastException) {
            result.error("invalid_argument", "'id' argument is required to be integer id of connection", null)
            return
        }

        val connection = connections.get(id)
        if (connection == null) {
            result.error("invalid_argument", "there is no connection with provided id", null)
            return
        }

        val data: ByteArray = when {
            call.hasArgument("string") -> call.argument<String>("string")!!.toByteArray()
            call.hasArgument("bytes") -> call.argument<ByteArray>("bytes")!!
            else -> {
                result.error("invalid_argument", "there must be 'string' or 'bytes' argument", null)
                return
            }
        }

        executor.execute {
            try {
                connection.write(data)
                runOnUi { result.success(null) }
            } catch (ex: Exception) {
                runOnUi { result.error("write_error", ex.message, exceptionToString(ex)) }
            }
        }
    }

    private companion object {
        const val TAG = "FlutterBluePlugin"
        const val PLUGIN_NAMESPACE = "flutter_bluetooth_serial_ble"

        // Permissions and request constants
        const val REQUEST_COARSE_LOCATION_PERMISSIONS = 1451
        const val REQUEST_ENABLE_BLUETOOTH = 1337
        const val REQUEST_DISCOVERABLE_BLUETOOTH = 2137

        // Pairing variants missing from the public SDK constants.
        const val PAIRING_VARIANT_CONSENT = 3
        const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
        const val PAIRING_VARIANT_DISPLAY_PIN = 5

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        /** Helper function to get a string out of an exception. */
        fun exceptionToString(ex: Exception): String {
            val sw = StringWriter()
            ex.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }

        /** Helper function to check whether a device is connected (hidden API). */
        fun checkIsDeviceConnected(device: BluetoothDevice): Boolean = try {
            device.javaClass.getMethod("isConnected").invoke(device) as Boolean
        } catch (ex: Exception) {
            false
        }
    }
}
