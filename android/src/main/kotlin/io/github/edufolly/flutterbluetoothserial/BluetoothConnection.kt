package io.github.edufolly.flutterbluetoothserial

import java.io.IOException
import java.util.UUID

interface BluetoothConnection {
    fun isConnected(): Boolean

    /** Connects to the given device by hardware address. */
    @Throws(IOException::class)
    fun connect(address: String, uuid: UUID)

    /** Connects to the given device by hardware address (default UUID used). */
    @Throws(IOException::class)
    fun connect(address: String)

    /** Disconnects the current session (ignored if not connected). */
    fun disconnect()

    /** Writes to the connected remote device. */
    @Throws(IOException::class)
    fun write(data: ByteArray)

    /** Callback for reading data. */
    fun onRead(data: ByteArray)

    /** Callback for disconnection. */
    fun onDisconnected(byRemote: Boolean)
}
