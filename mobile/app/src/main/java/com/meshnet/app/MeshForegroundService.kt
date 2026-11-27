package com.meshnet.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder

class MeshForegroundService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): MeshForegroundService = this@MeshForegroundService
    }

    private val binder = LocalBinder()
    private var transport: MeshBleTransport? = null
    private var router: MeshRouter? = null
    private lateinit var outbox: MeshQueueStore
    private lateinit var secretStore: MeshSecretStore
    private var listener: ((String) -> Unit)? = null
    private var nodeId: String = "unknown"

    override fun onCreate() {
        super.onCreate()
        outbox = MeshQueueStore(this)
        secretStore = MeshSecretStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP || action == ACTION_FORGET_CREDENTIALS) {
            stopMesh()
            if (action == ACTION_FORGET_CREDENTIALS) {
                secretStore.clear()
                outbox.clearAll()
                emit("Saved group credentials and local mesh queue deleted")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val supplied = intent?.getStringExtra(EXTRA_PASSPHRASE)?.takeIf { it.isNotBlank() }
        val stored = try { secretStore.load() } catch (error: Exception) {
            if (supplied == null) {
                emit(error.message ?: "Unable to unlock saved group credentials")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            null
        }

        val passphrase = try {
            if (supplied != null) {
                if (stored != supplied) {
                    stopMesh()
                    outbox.clearAll()
                    emit("Group changed; old encrypted frames and replay IDs were discarded")
                }
                secretStore.save(supplied)
                supplied
            } else stored
        } catch (error: Exception) {
            emit(error.message ?: "Unable to save group credentials")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (transport == null && !passphrase.isNullOrBlank()) {
            startForeground(NOTIFICATION_ID, notification("Starting BLE mesh…"))
            startMesh(passphrase)
        } else if (transport == null) {
            emit("No saved group credentials. Open MeshNet and enter the group passphrase.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder
    fun setListener(callback: ((String) -> Unit)?) { listener = callback }
    fun isRunning(): Boolean = transport != null

    fun sendMessage(message: String) {
        val activeRouter = router ?: throw IllegalStateException("Mesh service is not ready")
        activeRouter.sendMessage(message)
    }

    private fun startMesh(passphrase: String) {
        nodeId = java.util.UUID.randomUUID().toString().take(8)
        val crypto = try {
            MeshCrypto(passphrase.toCharArray())
        } catch (error: Exception) {
            emit(error.message ?: "Invalid group passphrase")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        router = MeshRouter(
            nodeId = nodeId,
            crypto = crypto,
            send = { frame ->
                if (!outbox.enqueue(frame)) {
                    emit("Outbox is full or frame is invalid; message was not queued")
                    false
                } else {
                    transport?.sendToPeers(frame)
                    true
                }
            },
            onMessage = ::emit,
            hasReceived = outbox::hasReceived,
            markReceived = outbox::markReceived,
            acknowledge = outbox::acknowledge
        )
        transport = MeshBleTransport(
            this,
            { address, connected ->
                emit("${if (connected) "Connected" else "Disconnected"}: $address")
            },
            { _, payload -> router?.receive(payload) },
            { state ->
                emit(state)
                if (state.contains("channel ready")) replayOutbox()
            }
        )
        try {
            transport?.start()
            emit("BLE foreground service started · Node $nodeId")
        } catch (error: Exception) {
            emit(error.message ?: "Unable to start BLE")
            stopMesh()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun replayOutbox() {
        val link = transport ?: return
        outbox.pending().forEach { frame ->
            if (link.sendToPeers(frame)) outbox.noteReplay(frame)
        }
        emit("Retried durable outbox; messages retire on group receipt, receipts expire after 24 hours")
    }

    private fun stopMesh() {
        transport?.stop()
        transport = null
        router = null
    }

    private fun emit(message: String) {
        listener?.invoke(message)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message.take(80)))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MeshNet connectivity", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MeshNet is running")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MeshNet is running")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        stopMesh()
        outbox.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.meshnet.app.START"
        const val ACTION_STOP = "com.meshnet.app.STOP"
        const val ACTION_FORGET_CREDENTIALS = "com.meshnet.app.FORGET_CREDENTIALS"
        const val EXTRA_PASSPHRASE = "group_passphrase"
        private const val CHANNEL_ID = "meshnet_connection"
        private const val NOTIFICATION_ID = 73
    }
}
