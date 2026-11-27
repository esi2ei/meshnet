package com.meshnet.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder

class MeshForegroundService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): MeshForegroundService = this@MeshForegroundService
    }

    private val binder = LocalBinder()
    private var transport: MeshBleTransport? = null
    private var wifiTransport: MeshWifiDirectTransport? = null
    private var wifiAwareTransport: MeshWifiAwareTransport? = null
    private var router: MeshRouter? = null
    private var fileTransfer: MeshFileTransfer? = null
    private lateinit var outbox: MeshQueueStore
    private lateinit var secretStore: MeshSecretStore
    private var listener: ((String) -> Unit)? = null
    private var nodeId: String = "unknown"
    private var replayFrames: List<ByteArray> = emptyList()
    private var replayIndex = 0
    private var replayActive = false

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
            secretStore.clear()
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

    fun sendMessage(message: String): Boolean {
        val activeRouter = router ?: throw IllegalStateException("Mesh service is not ready")
        return activeRouter.sendMessage(message)
    }

    fun sendFile(uri: Uri): Boolean {
        val transfer = fileTransfer ?: throw IllegalStateException("Mesh service is not ready")
        return transfer.send(uri)
    }

    fun latestReceivedVoiceNote(): java.io.File? = fileTransfer?.latestVoiceNote()

    fun discoverWifiDirect() { wifiTransport?.discover() ?: throw IllegalStateException("Wi-Fi Direct is not ready") }
    fun createWifiDirectGroup() { wifiTransport?.createGroup() ?: throw IllegalStateException("Wi-Fi Direct is not ready") }
    fun connectWifiDirect(address: String) { wifiTransport?.connect(address) ?: throw IllegalStateException("Wi-Fi Direct is not ready") }

    private fun startMesh(passphrase: String) {
        // Encode the full 128-bit random UUID as compact base64url to keep route headers bounded.
        val nodeUuid = java.util.UUID.randomUUID()
        val nodeBytes = java.nio.ByteBuffer.allocate(16)
            .putLong(nodeUuid.mostSignificantBits)
            .putLong(nodeUuid.leastSignificantBits)
            .array()
        nodeId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nodeBytes)
        val crypto = try {
            MeshCrypto(passphrase.toCharArray())
        } catch (error: Exception) {
            emit(error.message ?: "Invalid group passphrase")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        fileTransfer = MeshFileTransfer(
            context = this,
            sendMessage = { text -> router?.sendMessage(text) ?: false },
            onEvent = ::emit,
            canQueueBatch = outbox::canAcceptBatch
        )
        router = MeshRouter(
            nodeId = nodeId,
            crypto = crypto,
            send = { frame ->
                if (!outbox.enqueue(frame)) {
                    emit("Outbox is full or frame is invalid; message was not queued")
                    false
                } else {
                    // A receipt has no ACK-of-ACK. Once a connected peer accepts it into
                    // its ordered BLE write queue, retain only the ability to regenerate it
                    // when the original message is seen again.
                    val bleAccepted = transport?.sendToPeers(frame) == true
                    val wifiAccepted = wifiTransport?.sendToPeers(frame) == true
                    val awareAccepted = wifiAwareTransport?.sendToPeers(frame) == true
                    if (bleAccepted || wifiAccepted || awareAccepted) outbox.retireAcknowledgement(frame)
                    true
                }
            },
            onMessage = ::emit,
            hasReceived = outbox::hasReceived,
            markReceived = outbox::markReceived,
            acknowledge = outbox::acknowledge,
            onPayload = { plaintext -> fileTransfer?.accept(plaintext) == true }
        )
        transport = MeshBleTransport(
            this,
            { address, connected ->
                emit("${if (connected) "Connected" else "Disconnected"}: $address")
            },
            { _, payload -> router?.receive(payload) },
            { state ->
                emit(state)
                handleTransportState(state)
            }
        )
        try {
            transport?.start()
            wifiTransport = MeshWifiDirectTransport(
                this,
                { _, payload -> router?.receive(payload) },
                { state ->
                    emit(state)
                    if (state.contains("data channel ready")) handleTransportState("Wi-Fi Direct channel ready")
                }
            )
            runCatching { wifiTransport?.start() }
                .onFailure { emit("Wi-Fi Direct unavailable; BLE remains active: ${it.message}") }
            wifiAwareTransport = MeshWifiAwareTransport(
                this,
                passphrase,
                { _, payload -> router?.receive(payload) },
                { state ->
                    emit(state)
                    if (state.contains("data channel ready")) handleTransportState("Wi-Fi Aware channel ready")
                }
            )
            runCatching { wifiAwareTransport?.start() }
                .onFailure { emit("Wi-Fi Aware unavailable; other transports remain active: ${it.message}") }
            emit("BLE foreground service started · Node $nodeId")
        } catch (error: Exception) {
            emit(error.message ?: "Unable to start BLE")
            stopMesh()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized
    private fun handleTransportState(state: String) {
        when {
            state.contains("channel ready") -> {
                if (!replayActive) beginOutboxReplay() else pumpOutboxReplay()
            }
            state.contains("write queue drained") || state.contains("write queue available") ->
                pumpOutboxReplay()
        }
    }

    @Synchronized
    private fun beginOutboxReplay() {
        replayFrames = outbox.pending()
        replayIndex = 0
        replayActive = true
        pumpOutboxReplay()
    }

    @Synchronized
    private fun pumpOutboxReplay() {
        val link = transport ?: return
        if (!replayActive) return
        while (replayIndex < replayFrames.size) {
            val frame = replayFrames[replayIndex]
            // A receipt may have retired this message after the replay snapshot was created.
            if (!outbox.containsFrame(frame)) {
                replayIndex++
                continue
            }
            // A full per-peer BLE queue pauses this pass. Queue-drained callbacks resume it.
            val bleAccepted = link.sendToPeers(frame)
            val wifiAccepted = wifiTransport?.sendToPeers(frame) == true
            val awareAccepted = wifiAwareTransport?.sendToPeers(frame) == true
            if (!bleAccepted && !wifiAccepted && !awareAccepted) return
            outbox.noteReplay(frame)
            outbox.retireAcknowledgement(frame)
            replayIndex++
        }
        val queued = replayFrames.size
        replayFrames = emptyList()
        replayIndex = 0
        replayActive = false
        emit("Queued $queued durable frames for BLE transmission; messages retire on group receipt")
    }

    @Synchronized
    private fun stopMesh() {
        transport?.stop()
        transport = null
        wifiTransport?.stop()
        wifiTransport = null
        wifiAwareTransport?.stop()
        wifiAwareTransport = null
        router = null
        fileTransfer = null
        replayFrames = emptyList()
        replayIndex = 0
        replayActive = false
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
