package io.github.edufolly.flutterbluetoothserial

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Universal Bluetooth serial connection class (classic RFCOMM). */
@SuppressLint("MissingPermission") // The plugin checks permissions before calling into this class.
open class BluetoothConnectionClassic(
    onReadCallback: OnReadCallback,
    onDisconnectedCallback: OnDisconnectedCallback,
    protected val bluetoothAdapter: BluetoothAdapter,
) : BluetoothConnectionBase(onReadCallback, onDisconnectedCallback) {

    protected var connectionThread: ConnectionThread? = null

    override fun isConnected(): Boolean = connectionThread?.requestedClosing == false

    // @TODO `connect` could be performed on another thread
    // @TODO `connect` parameter: timeout
    // @TODO `connect` other methods than `createRfcommSocketToServiceRecord`, including the hidden raw `createRfcommSocket` (on channel)
    @Throws(IOException::class)
    override fun connect(address: String, uuid: UUID) {
        if (isConnected()) throw IOException("already connected")

        val device = bluetoothAdapter.getRemoteDevice(address) ?: throw IOException("device not found")
        var socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
            ?: throw IOException("socket connection not established")

        // Cancel discovery, even though we didn't start it.
        bluetoothAdapter.cancelDiscovery()

        try {
            socket.connect()
        } catch (e: IOException) {
            try {
                // Newer versions of Android may require this fallback; see https://stackoverflow.com/a/25647197
                socket = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
                socket.connect()
            } catch (e2: Exception) {
                throw IOException("Failed to connect", e2)
            }
        }

        connectionThread = ConnectionThread(socket).also { it.start() }
    }

    @Throws(IOException::class)
    override fun connect(address: String) = connect(address, DEFAULT_UUID)

    override fun disconnect() {
        if (isConnected()) {
            connectionThread?.cancel()
            connectionThread = null
        }
    }

    @Throws(IOException::class)
    override fun write(data: ByteArray) {
        if (!isConnected()) throw IOException("not connected")
        connectionThread?.write(data)
    }

    /** Thread to handle connection I/O. */
    protected inner class ConnectionThread(private val socket: BluetoothSocket) : Thread() {
        private val input: InputStream? = runCatching { socket.inputStream }.getOrNull()
        private val output: OutputStream? = runCatching { socket.outputStream }.getOrNull()

        @Volatile
        var requestedClosing = false
            private set

        override fun run() {
            val buffer = ByteArray(1024)
            while (!requestedClosing) {
                try {
                    val bytes = input!!.read(buffer)
                    onRead(buffer.copyOf(bytes))
                } catch (e: IOException) {
                    // `input.read` throws when closed by the remote device.
                    break
                }
            }

            // Make sure both streams are closed.
            runCatching { output?.close() }
            runCatching { input?.close() }

            // Callback on disconnected, with information which side is closing.
            onDisconnected(!requestedClosing)

            // Just prevent unnecessary `cancel`ing.
            requestedClosing = true
        }

        /** Writes to the output stream. */
        fun write(bytes: ByteArray) {
            try {
                output!!.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        /** Stops the thread and disconnects. */
        fun cancel() {
            if (requestedClosing) return
            requestedClosing = true

            // Flush output buffers before closing.
            runCatching { output?.flush() }

            // Close the connection socket.
            runCatching {
                // Might be useful (see https://stackoverflow.com/a/22769260/4880243)
                sleep(111)
                socket.close()
            }
        }
    }

    protected companion object {
        val DEFAULT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
