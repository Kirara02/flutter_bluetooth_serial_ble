package io.github.edufolly.flutterbluetoothserial.le

import java.util.ArrayDeque

interface SerialListener {
    fun onSerialConnect()
    fun onSerialConnectError(e: Exception)

    /** socket -> service */
    fun onSerialRead(data: ByteArray)

    /** service -> UI thread */
    fun onSerialRead(datas: ArrayDeque<ByteArray>)
    fun onSerialIoError(e: Exception)
}
