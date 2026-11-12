package io.github.edufolly.flutterbluetoothserial.le

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.util.ArrayDeque

/**
 * Creates a notification and queues serial data while the activity is not in the foreground.
 * Uses the listener chain: SerialSocket -> SerialService -> UI fragment.
 */
class SerialService : Service(), SerialListener {

    inner class SerialBinder : Binder() {
        val service: SerialService get() = this@SerialService
    }

    private enum class QueueType { Connect, ConnectError, Read, IoError }

    private class QueueItem private constructor(
        val type: QueueType,
        var datas: ArrayDeque<ByteArray>? = null,
        val e: Exception? = null,
    ) {
        constructor(type: QueueType) : this(type, if (type == QueueType.Read) ArrayDeque() else null)
        constructor(type: QueueType, e: Exception) : this(type, null, e)
        constructor(type: QueueType, datas: ArrayDeque<ByteArray>) : this(type, datas, null)

        fun init() {
            datas = ArrayDeque()
        }

        fun add(data: ByteArray) {
            datas!!.add(data)
        }
    }

    private val mainLooper = Handler(Looper.getMainLooper())
    private val binder: IBinder = SerialBinder()
    private val queue1 = ArrayDeque<QueueItem>()
    private val queue2 = ArrayDeque<QueueItem>()
    private val lastRead = QueueItem(QueueType.Read)

    private var socket: SerialSocket? = null
    private var listener: SerialListener? = null
    private var connected = false

    // Lifecycle

    override fun onDestroy() {
        cancelNotification()
        disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Api

    @Throws(IOException::class)
    fun connect(socket: SerialSocket) {
        socket.connect(this)
        this.socket = socket
        connected = true
    }

    fun disconnect() {
        connected = false // ignore data, errors while disconnecting
        cancelNotification()
        socket?.disconnect()
        socket = null
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (!connected) throw IOException("not connected")
        socket!!.write(data)
    }

    fun attach(listener: SerialListener) {
        require(Looper.getMainLooper().thread === Thread.currentThread()) { "not in main thread" }
        cancelNotification()
        // Use synchronized() to prevent new items in queue2.
        // New items will not be added to queue1 because mainLooper.post and attach() run in the main thread.
        synchronized(this) {
            this.listener = listener
        }
        for (item in queue1) dispatch(listener, item)
        for (item in queue2) dispatch(listener, item)
        queue1.clear()
        queue2.clear()
    }

    private fun dispatch(listener: SerialListener, item: QueueItem) {
        when (item.type) {
            QueueType.Connect -> listener.onSerialConnect()
            QueueType.ConnectError -> listener.onSerialConnectError(item.e!!)
            QueueType.Read -> listener.onSerialRead(item.datas!!)
            QueueType.IoError -> listener.onSerialIoError(item.e!!)
        }
    }

    fun detach() {
        if (connected) createNotification()
        // Items already in the event queue (posted before detach() to mainLooper) will end up in queue1.
        // Items occurring later will be moved directly to queue2.
        // detach() and mainLooper.post run in the main thread, so all items are caught.
        listener = null
    }

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nc = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL,
                "Background service",
                NotificationManager.IMPORTANCE_LOW,
            )
            nc.setShowBadge(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(nc)
        }
        val disconnectIntent = Intent().setAction(Constants.INTENT_ACTION_DISCONNECT)
        val restartIntent = Intent()
            .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = PendingIntent.FLAG_IMMUTABLE // minSdk >= 23
        val disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags)
        val restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent, flags)
        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
            .setColor(0xffd84315.toInt())
            .setContentTitle("//DUMMY appname")
            .setContentText(socket?.let { "Connected to ${it.name}" } ?: "Background Service")
            .setContentIntent(restartPendingIntent)
            .setOngoing(true)
            .addAction(NotificationCompat.Action(android.R.drawable.ic_delete, "Disconnect", disconnectPendingIntent))
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, builder.build())
    }

    private fun cancelNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // SerialListener

    override fun onSerialConnect() {
        if (!connected) return
        synchronized(this) {
            if (listener != null) {
                mainLooper.post {
                    val l = listener
                    if (l != null) l.onSerialConnect() else queue1.add(QueueItem(QueueType.Connect))
                }
            } else {
                queue2.add(QueueItem(QueueType.Connect))
            }
        }
    }

    override fun onSerialConnectError(e: Exception) {
        if (!connected) return
        synchronized(this) {
            if (listener != null) {
                mainLooper.post {
                    val l = listener
                    if (l != null) {
                        l.onSerialConnectError(e)
                    } else {
                        queue1.add(QueueItem(QueueType.ConnectError, e))
                        disconnect()
                    }
                }
            } else {
                queue2.add(QueueItem(QueueType.ConnectError, e))
                disconnect()
            }
        }
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        throw UnsupportedOperationException()
    }

    /**
     * Reduces the number of UI updates by merging data chunks.
     * Data can arrive at hundreds of chunks per second, but the UI can only perform a dozen updates
     * if receiveText already contains much text.
     *
     * On new data inform the UI thread once (1). While not consumed (2), add more data (3).
     */
    override fun onSerialRead(data: ByteArray) {
        if (!connected) return
        synchronized(this) {
            if (listener != null) {
                val first: Boolean
                synchronized(lastRead) {
                    first = lastRead.datas!!.isEmpty() // (1)
                    lastRead.add(data) // (3)
                }
                if (first) {
                    mainLooper.post {
                        val datas: ArrayDeque<ByteArray>
                        synchronized(lastRead) {
                            datas = lastRead.datas!!
                            lastRead.init() // (2)
                        }
                        val l = listener
                        if (l != null) l.onSerialRead(datas) else queue1.add(QueueItem(QueueType.Read, datas))
                    }
                }
            } else {
                if (queue2.isEmpty() || queue2.last.type != QueueType.Read) {
                    queue2.add(QueueItem(QueueType.Read))
                }
                queue2.last.add(data)
            }
        }
    }

    override fun onSerialIoError(e: Exception) {
        if (!connected) return
        synchronized(this) {
            if (listener != null) {
                mainLooper.post {
                    val l = listener
                    if (l != null) {
                        l.onSerialIoError(e)
                    } else {
                        queue1.add(QueueItem(QueueType.IoError, e))
                        disconnect()
                    }
                }
            } else {
                queue2.add(QueueItem(QueueType.IoError, e))
                disconnect()
            }
        }
    }
}
