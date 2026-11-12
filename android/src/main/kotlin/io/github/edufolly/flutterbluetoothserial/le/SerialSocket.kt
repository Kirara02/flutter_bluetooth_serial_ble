package io.github.edufolly.flutterbluetoothserial.le

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.edufolly.flutterbluetoothserial.BluetoothContextCompat
import java.io.IOException
import java.security.InvalidParameterException
import java.util.UUID

/**
 * Wraps BLE communication into a socket-like class:
 *  - connect, disconnect and write as methods,
 *  - read data and status are returned through [SerialListener].
 */
@SuppressLint("MissingPermission") // various BluetoothGatt, BluetoothDevice methods
class SerialSocket(private val context: Context, device: BluetoothDevice) : BluetoothGattCallback() {

    /** Device specific behaviour is delegated to subclasses of this class. */
    private open class DeviceDelegate {
        open fun connectCharacteristics(s: BluetoothGattService): Boolean = true

        // The following methods are only overridden for Telit devices.
        open fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {}
        open fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {}
        open fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {}
        open fun canWrite(): Boolean = true
        open fun disconnect() {}
    }

    private val writeBuffer = ArrayList<ByteArray>()
    private val pairingIntentFilter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
    }
    private val pairingBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onPairingBroadcastReceive(intent)
        }
    }
    private val disconnectBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            listener?.onSerialIoError(IOException("background disconnect"))
            disconnect() // disconnect now, else it would be queued until the UI is re-attached
        }
    }

    private var listener: SerialListener? = null
    private var delegate: DeviceDelegate? = null
    private var device: BluetoothDevice? = device
    private var gatt: BluetoothGatt? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var writePending = false
    private var canceled = false
    private var connected = false
    private var payloadSize = DEFAULT_MTU - 3

    init {
        if (context is Activity) throw InvalidParameterException("expected non UI context")
    }

    // Kept as a function (not a property) so callers keep using getName().
    val name: String get() = device?.let { it.name ?: it.address } ?: ""

    fun disconnect() {
        Log.d(TAG, "disconnect")
        listener = null // ignore remaining data and errors
        device = null
        canceled = true
        synchronized(writeBuffer) {
            writePending = false
            writeBuffer.clear()
        }
        readCharacteristic = null
        writeCharacteristic = null
        delegate?.disconnect()
        gatt?.let { g ->
            Log.d(TAG, "gatt.disconnect")
            g.disconnect()
            Log.d(TAG, "gatt.close")
            try {
                g.close()
            } catch (_: Exception) {
            }
            gatt = null
            connected = false
        }
        try {
            context.unregisterReceiver(pairingBroadcastReceiver)
        } catch (_: Exception) {
        }
        try {
            context.unregisterReceiver(disconnectBroadcastReceiver)
        } catch (_: Exception) {
        }
    }

    /** Connect success and most connect errors are returned asynchronously to the listener. */
    @Throws(IOException::class)
    fun connect(listener: SerialListener) {
        if (connected || gatt != null) throw IOException("already connected")
        val dev = device ?: throw IOException("no device")
        canceled = false
        this.listener = listener
        BluetoothContextCompat.registerReceiver(
            context, disconnectBroadcastReceiver, IntentFilter(Constants.INTENT_ACTION_DISCONNECT),
        )
        Log.d(TAG, "connect $dev")
        BluetoothContextCompat.registerReceiver(context, pairingBroadcastReceiver, pairingIntentFilter)
        Log.d(TAG, "connectGatt,LE")
        gatt = dev.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) throw IOException("connectGatt failed")
        // continues asynchronously in onPairingBroadcastReceive() and onConnectionStateChange()
    }

    private fun onPairingBroadcastReceive(intent: Intent) {
        // For ARM Mbed, Microbit, ... use pairing from the Android bluetooth settings.
        // For HM10 clones, ... pairing is initiated here.
        val dev = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (dev == null || dev != device) return
        when (intent.action) {
            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                Log.d(TAG, "pairing request $pairingVariant")
                onSerialConnectError(IOException("Pairing requested: pair and connect again"))
                // The pairing dialog brings the app to the background (onPause), but it is still partly
                // visible (no onStop), so there is no automatic disconnect().
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                Log.d(TAG, "bond state $previousBondState->$bondState")
            }
            else -> Log.d(TAG, "unknown broadcast ${intent.action}")
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        // status is taken directly from gatt_api.h, e.g. 133=0x85=GATT_ERROR ~= timeout
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "connect status $status, discoverServices")
            if (!gatt.discoverServices()) onSerialConnectError(IOException("discoverServices failed"))
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (connected) onSerialIoError(IOException("gatt status $status"))
            else onSerialConnectError(IOException("gatt status $status"))
        } else {
            Log.d(TAG, "unknown connect state $newState $status")
        }
        // continues asynchronously in onServicesDiscovered()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Log.d(TAG, "servicesDiscovered, status $status")
        if (canceled) return
        connectCharacteristics1(gatt)
    }

    private fun connectCharacteristics1(gatt: BluetoothGatt) {
        var sync = true
        writePending = false
        for (gattService in gatt.services) {
            when (gattService.uuid) {
                BLUETOOTH_LE_CC254X_SERVICE -> delegate = Cc245XDelegate()
                BLUETOOTH_LE_MICROCHIP_SERVICE -> delegate = MicrochipDelegate()
                BLUETOOTH_LE_NRF_SERVICE -> delegate = NrfDelegate()
                BLUETOOTH_LE_TIO_SERVICE -> delegate = TelitDelegate()
            }
            delegate?.let {
                sync = it.connectCharacteristics(gattService)
                break
            }
        }
        if (canceled) return
        if (delegate == null || readCharacteristic == null || writeCharacteristic == null) {
            for (gattService in gatt.services) {
                Log.d(TAG, "service ${gattService.uuid}")
                for (characteristic in gattService.characteristics) {
                    Log.d(TAG, "characteristic ${characteristic.uuid}")
                }
            }
            onSerialConnectError(IOException("no serial profile found"))
            return
        }
        if (sync) connectCharacteristics2(gatt)
    }

    private fun connectCharacteristics2(gatt: BluetoothGatt) {
        Log.d(TAG, "request max MTU")
        if (!gatt.requestMtu(MAX_MTU)) onSerialConnectError(IOException("request MTU failed"))
        // continues asynchronously in onMtuChanged
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(TAG, "mtu size $mtu, status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            payloadSize = mtu - 3
            Log.d(TAG, "payload size $payloadSize")
        }
        connectCharacteristics3(gatt)
    }

    private fun connectCharacteristics3(gatt: BluetoothGatt) {
        val readChar = readCharacteristic
        val writeChar = writeCharacteristic
        if (readChar == null || writeChar == null) {
            onSerialConnectError(IOException("no serial profile found"))
            return
        }
        // Microbit and HM10 clones have WRITE; HM10, TI uart and Telit have only WRITE_NO_RESPONSE.
        val writeProperties = writeChar.properties
        if ((writeProperties and
                (BluetoothGattCharacteristic.PROPERTY_WRITE + BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0
        ) {
            onSerialConnectError(IOException("write characteristic not writable"))
            return
        }
        if (!gatt.setCharacteristicNotification(readChar, true)) {
            onSerialConnectError(IOException("no notification for read characteristic"))
            return
        }
        val readDescriptor = readChar.getDescriptor(BLUETOOTH_LE_CCCD)
        if (readDescriptor == null) {
            onSerialConnectError(IOException("no CCCD descriptor for read characteristic"))
            return
        }
        val readProperties = readChar.properties
        val cccdValue: ByteArray
        if ((readProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            Log.d(TAG, "enable read indication")
            cccdValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else if ((readProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            Log.d(TAG, "enable read notification")
            cccdValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            onSerialConnectError(IOException("no indication/notification for read characteristic ($readProperties)"))
            return
        }
        Log.d(TAG, "writing read characteristic descriptor")
        if (!writeDescriptorCompat(gatt, readDescriptor, cccdValue)) {
            onSerialConnectError(IOException("read characteristic CCCD descriptor not writable"))
        }
        // continues asynchronously in onDescriptorWrite()
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        delegate?.onDescriptorWrite(gatt, descriptor, status)
        if (canceled) return
        if (descriptor.characteristic === readCharacteristic) {
            Log.d(TAG, "writing read characteristic descriptor finished, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onSerialConnectError(IOException("write descriptor failed"))
            } else {
                // onCharacteristicChanged with incoming data can happen after writeDescriptor(ENABLE_INDICATION/
                // NOTIFICATION) before it is confirmed by this method, so received data can be shown before
                // the device is shown as 'Connected'.
                onSerialConnect()
                connected = true
                Log.d(TAG, "connected")
            }
        }
    }

    /** Android 13+ delivers the value as a parameter; this is the entry point there. */
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        handleCharacteristicChanged(gatt, characteristic, value)
    }

    /** Pre-Android 13 callback. On API 33+ the system calls the overload above instead. */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            handleCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
        }
    }

    /* read */
    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (canceled) return
        delegate?.onCharacteristicChanged(gatt, characteristic, value)
        if (canceled) return
        if (characteristic === readCharacteristic) {
            onSerialRead(value)
            Log.d(TAG, "read, len=${value.size}")
        }
    }

    /* write */
    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (canceled || !connected || writeCharacteristic == null) throw IOException("not connected")
        var data0: ByteArray? = null
        synchronized(writeBuffer) {
            data0 = if (data.size <= payloadSize) data else data.copyOfRange(0, payloadSize)
            if (!writePending && writeBuffer.isEmpty() && delegate?.canWrite() != false) {
                writePending = true
            } else {
                writeBuffer.add(data0!!)
                Log.d(TAG, "write queued, len=${data0!!.size}")
                data0 = null
            }
            if (data.size > payloadSize) {
                for (i in 1 until (data.size + payloadSize - 1) / payloadSize) {
                    val from = i * payloadSize
                    val to = minOf(from + payloadSize, data.size)
                    writeBuffer.add(data.copyOfRange(from, to))
                    Log.d(TAG, "write queued, len=${to - from}")
                }
            }
        }
        data0?.let { chunk ->
            if (!writeCharacteristicCompat(writeCharacteristic, chunk)) {
                onSerialIoError(IOException("write failed"))
            } else {
                Log.d(TAG, "write started, len=${chunk.size}")
            }
        }
        // continues asynchronously in onCharacteristicWrite()
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (canceled || !connected || writeCharacteristic == null) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            onSerialIoError(IOException("write failed"))
            return
        }
        delegate?.onCharacteristicWrite(gatt, characteristic, status)
        if (canceled) return
        if (characteristic === writeCharacteristic) {
            Log.d(TAG, "write finished, status=$status")
            writeNext()
        }
    }

    private fun writeNext() {
        val data: ByteArray?
        synchronized(writeBuffer) {
            if (writeBuffer.isNotEmpty() && delegate?.canWrite() != false) {
                writePending = true
                data = writeBuffer.removeAt(0)
            } else {
                writePending = false
                data = null
            }
        }
        if (data != null) {
            if (!writeCharacteristicCompat(writeCharacteristic, data)) {
                onSerialIoError(IOException("write failed"))
            } else {
                Log.d(TAG, "write started, len=${data.size}")
            }
        }
    }

    // ------------------------------------------------------------------ API-level compat helpers

    /** Writes a characteristic value: the Android 13+ API passes the value, older versions use setValue(). */
    @Suppress("DEPRECATION") // legacy branch only runs below API 33
    private fun writeCharacteristicCompat(characteristic: BluetoothGattCharacteristic?, value: ByteArray): Boolean {
        val g = gatt ?: return false
        if (characteristic == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, value, characteristic.writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = value
            g.writeCharacteristic(characteristic)
        }
    }

    /** Writes a descriptor value: the Android 13+ API passes the value, older versions use setValue(). */
    @Suppress("DEPRECATION") // legacy branch only runs below API 33
    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }

    // ------------------------------------------------------------------------- SerialListener

    private fun onSerialConnect() {
        listener?.onSerialConnect()
    }

    private fun onSerialConnectError(e: Exception) {
        canceled = true
        listener?.onSerialConnectError(e)
    }

    private fun onSerialRead(data: ByteArray) {
        listener?.onSerialRead(data)
    }

    private fun onSerialIoError(e: Exception) {
        writePending = false
        canceled = true
        listener?.onSerialIoError(e)
    }

    // --------------------------------------------------------------------------- device delegates

    private inner class Cc245XDelegate : DeviceDelegate() {
        override fun connectCharacteristics(s: BluetoothGattService): Boolean {
            Log.d(TAG, "service cc254x uart")
            readCharacteristic = s.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW)
            writeCharacteristic = s.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW)
            return true
        }
    }

    private inner class MicrochipDelegate : DeviceDelegate() {
        override fun connectCharacteristics(s: BluetoothGattService): Boolean {
            Log.d(TAG, "service microchip uart")
            readCharacteristic = s.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_RW)
            writeCharacteristic = s.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_W)
                ?: s.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_RW)
            return true
        }
    }

    private inner class NrfDelegate : DeviceDelegate() {
        override fun connectCharacteristics(s: BluetoothGattService): Boolean {
            Log.d(TAG, "service nrf uart")
            val rw2 = s.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW2)
            val rw3 = s.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW3)
            if (rw2 != null && rw3 != null) {
                val rw2prop = rw2.properties
                val rw3prop = rw3.properties
                val rw2write = (rw2prop and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                val rw3write = (rw3prop and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                Log.d(TAG, "characteristic properties $rw2prop/$rw3prop")
                if (rw2write && rw3write) {
                    onSerialConnectError(IOException("multiple write characteristics ($rw2prop/$rw3prop)"))
                } else if (rw2write) {
                    writeCharacteristic = rw2
                    readCharacteristic = rw3
                } else if (rw3write) {
                    writeCharacteristic = rw3
                    readCharacteristic = rw2
                } else {
                    onSerialConnectError(IOException("no write characteristic ($rw2prop/$rw3prop)"))
                }
            }
            return true
        }
    }

    private inner class TelitDelegate : DeviceDelegate() {
        private var readCreditsCharacteristic: BluetoothGattCharacteristic? = null
        private var writeCreditsCharacteristic: BluetoothGattCharacteristic? = null
        private var readCredits = 0
        private var writeCredits = 0

        override fun connectCharacteristics(s: BluetoothGattService): Boolean {
            Log.d(TAG, "service telit tio 2.0")
            readCredits = 0
            writeCredits = 0
            readCharacteristic = s.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_RX)
            writeCharacteristic = s.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_TX)
            val readCreditsChar = s.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_RX_CREDITS)
            readCreditsCharacteristic = readCreditsChar
            writeCreditsCharacteristic = s.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_TX_CREDITS)
            if (readCharacteristic == null) {
                onSerialConnectError(IOException("read characteristic not found"))
                return false
            }
            if (writeCharacteristic == null) {
                onSerialConnectError(IOException("write characteristic not found"))
                return false
            }
            if (readCreditsChar == null) {
                onSerialConnectError(IOException("read credits characteristic not found"))
                return false
            }
            if (writeCreditsCharacteristic == null) {
                onSerialConnectError(IOException("write credits characteristic not found"))
                return false
            }
            val g = gatt
            if (g == null || !g.setCharacteristicNotification(readCreditsChar, true)) {
                onSerialConnectError(IOException("no notification for read credits characteristic"))
                return false
            }
            val readCreditsDescriptor = readCreditsChar.getDescriptor(BLUETOOTH_LE_CCCD)
            if (readCreditsDescriptor == null) {
                onSerialConnectError(IOException("no CCCD descriptor for read credits characteristic"))
                return false
            }
            Log.d(TAG, "writing read credits characteristic descriptor")
            if (!writeDescriptorCompat(g, readCreditsDescriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                onSerialConnectError(IOException("read credits characteristic CCCD descriptor not writable"))
                return false
            }
            Log.d(TAG, "writing read credits characteristic descriptor")
            return false
            // continues asynchronously in connectCharacteristics2
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.characteristic === readCreditsCharacteristic) {
                Log.d(TAG, "writing read credits characteristic descriptor finished, status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onSerialConnectError(IOException("write credits descriptor failed"))
                } else {
                    connectCharacteristics2(g)
                }
            }
            if (d.characteristic === readCharacteristic) {
                Log.d(TAG, "writing read characteristic descriptor finished, status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    readCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    writeCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    grantReadCredits()
                    // grantReadCredits includes gatt.writeCharacteristic(writeCreditsCharacteristic),
                    // but we do not have to wait for confirmation, as it is the last write of the connect phase.
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c === readCreditsCharacteristic) {
                val newCredits = value[0].toInt()
                synchronized(writeBuffer) {
                    writeCredits += newCredits
                }
                Log.d(TAG, "got write credits +$newCredits =$writeCredits")

                if (!writePending && writeBuffer.isNotEmpty()) {
                    Log.d(TAG, "resume blocked write")
                    writeNext()
                }
            }
            if (c === readCharacteristic) {
                grantReadCredits()
                Log.d(TAG, "read, credits=$readCredits")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c === writeCharacteristic) {
                synchronized(writeBuffer) {
                    if (writeCredits > 0) writeCredits -= 1
                }
                Log.d(TAG, "write finished, credits=$writeCredits")
            }
            if (c === writeCreditsCharacteristic) {
                Log.d(TAG, "write credits finished, status=$status")
            }
        }

        override fun canWrite(): Boolean {
            if (writeCredits > 0) return true
            Log.d(TAG, "no write credits")
            return false
        }

        override fun disconnect() {
            readCreditsCharacteristic = null
            writeCreditsCharacteristic = null
        }

        private fun grantReadCredits() {
            val minReadCredits = 16
            val maxReadCredits = 64
            if (readCredits > 0) readCredits -= 1
            if (readCredits <= minReadCredits) {
                val newCredits = maxReadCredits - readCredits
                readCredits += newCredits
                Log.d(TAG, "grant read credits +$newCredits =$readCredits")
                if (!writeCharacteristicCompat(writeCreditsCharacteristic, byteArrayOf(newCredits.toByte()))) {
                    if (connected) onSerialIoError(IOException("write read credits failed"))
                    else onSerialConnectError(IOException("write read credits failed"))
                }
            }
        }
    }

    private companion object {
        val BLUETOOTH_LE_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BLUETOOTH_LE_CC254X_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val BLUETOOTH_LE_CC254X_CHAR_RW: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val BLUETOOTH_LE_NRF_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val BLUETOOTH_LE_NRF_CHAR_RW2: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // read on microbit, write on adafruit
        val BLUETOOTH_LE_NRF_CHAR_RW3: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val BLUETOOTH_LE_MICROCHIP_SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        val BLUETOOTH_LE_MICROCHIP_CHAR_RW: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
        val BLUETOOTH_LE_MICROCHIP_CHAR_W: UUID = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")

        // https://play.google.com/store/apps/details?id=com.telit.tiosample
        // https://www.telit.com/wp-content/uploads/2017/09/TIO_Implementation_Guide_r6.pdf
        val BLUETOOTH_LE_TIO_SERVICE: UUID = UUID.fromString("0000FEFB-0000-1000-8000-00805F9B34FB")
        val BLUETOOTH_LE_TIO_CHAR_TX: UUID = UUID.fromString("00000001-0000-1000-8000-008025000000") // WNR
        val BLUETOOTH_LE_TIO_CHAR_RX: UUID = UUID.fromString("00000002-0000-1000-8000-008025000000") // N
        val BLUETOOTH_LE_TIO_CHAR_TX_CREDITS: UUID = UUID.fromString("00000003-0000-1000-8000-008025000000") // W
        val BLUETOOTH_LE_TIO_CHAR_RX_CREDITS: UUID = UUID.fromString("00000004-0000-1000-8000-008025000000") // I

        // The BLE standard does not limit the MTU, some BLE 4.2 devices support 251, and various sources say
        // that Android has a maximum of 512.
        const val MAX_MTU = 512
        const val DEFAULT_MTU = 23
        const val TAG = "SerialSocket"
    }
}
