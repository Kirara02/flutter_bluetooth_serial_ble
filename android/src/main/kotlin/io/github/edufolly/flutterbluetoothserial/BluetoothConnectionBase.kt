package io.github.edufolly.flutterbluetoothserial

abstract class BluetoothConnectionBase(
    private val onReadCallback: OnReadCallback,
    private val onDisconnectedCallback: OnDisconnectedCallback,
) : BluetoothConnection {

    fun interface OnReadCallback {
        fun onRead(data: ByteArray)
    }

    fun interface OnDisconnectedCallback {
        fun onDisconnected(byRemote: Boolean)
    }

    override fun onRead(data: ByteArray) = onReadCallback.onRead(data)

    override fun onDisconnected(byRemote: Boolean) = onDisconnectedCallback.onDisconnected(byRemote)
}
