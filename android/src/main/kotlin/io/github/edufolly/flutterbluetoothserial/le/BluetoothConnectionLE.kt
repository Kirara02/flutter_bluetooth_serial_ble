package io.github.edufolly.flutterbluetoothserial.le

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.edufolly.flutterbluetoothserial.BluetoothConnectionBase
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch

@SuppressLint("MissingPermission") // The plugin checks permissions before calling into this class.
class BluetoothConnectionLE(
    onReadCallback: OnReadCallback,
    onDisconnectedCallback: OnDisconnectedCallback,
    private val ctx: Context,
) : BluetoothConnectionBase(onReadCallback, onDisconnectedCallback) {

    enum class Connected { False, Pending, True }

    @Volatile
    private var connected = Connected.False
    private var socket: SerialSocket? = null

    override fun isConnected(): Boolean = connected == Connected.True

    @Throws(IOException::class)
    override fun connect(address: String, uuid: UUID) = connect(address) // The uuid is not used.

    @Throws(IOException::class)
    override fun connect(address: String) {
        if (isConnected()) throw IOException("already connected")
        try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device = adapter.getRemoteDevice(address)
            connected = Connected.Pending
            val newSocket = SerialSocket(ctx, device)

            var failure: Exception? = null
            val done = CountDownLatch(1)
            // RAINY THINK: make sure the semantics here are the same as with classic Bluetooth.
            newSocket.connect(object : SerialListener {
                override fun onSerialConnect() {
                    done.countDown()
                }

                override fun onSerialConnectError(e: Exception) {
                    e.printStackTrace()
                    failure = e
                    done.countDown()
                    // CHECK: byRemote? Also, should this happen on the initial connection attempt?
                    this@BluetoothConnectionLE.onDisconnected(true)
                }

                override fun onSerialRead(data: ByteArray) = this@BluetoothConnectionLE.onRead(data)

                override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
                    datas.forEach { this@BluetoothConnectionLE.onRead(it) }
                }

                override fun onSerialIoError(e: Exception) {
                    throw RuntimeException("//DUMMY", e) // THINK: send a connection error instead?
                }
            })
            done.await() // DUMMY: timeout?
            failure?.let { throw it }
            socket = newSocket
            connected = Connected.True
        } catch (e: Exception) {
            try {
                disconnect() // THINK: is this still correct?
            } finally {
                throw IOException(e)
            }
        }
    }

    override fun disconnect() {
        if (isConnected()) {
            connected = Connected.False // Ignore data and errors while disconnecting.
            socket?.disconnect()
            socket = null
        }
    }

    @Throws(IOException::class)
    override fun write(data: ByteArray) {
        if (!isConnected()) throw IOException("not connected")
        socket?.write(data)
    }
}
