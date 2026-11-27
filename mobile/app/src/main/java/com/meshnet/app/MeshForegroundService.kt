package com.meshnet.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
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
    private var liveVoiceCall: MeshLiveVoiceCall? = null
    private lateinit var outbox: MeshQueueStore
    private lateinit var secretStore: MeshSecretStore
    private lateinit var offlineWallet: MeshOfflineWalletStore
    private lateinit var walletEnvelopePreferences: SharedPreferences
    private data class PendingOfflineWalletEnvelope(
        val count: Int,
        val prefix: String,
        val chunks: MutableMap<Int, String>,
        val createdAt: Long
    )
    private val pendingOfflinePayments = linkedMapOf<String, PendingOfflineWalletEnvelope>()
    private var listener: ((String) -> Unit)? = null
    private var nodeId: String = "unknown"
    private var replayFrames: List<ByteArray> = emptyList()
    private var replayIndex = 0
    private var replayActive = false

    override fun onCreate() {
        super.onCreate()
        outbox = MeshQueueStore(this)
        secretStore = MeshSecretStore(this)
        offlineWallet = MeshOfflineWalletStore(this)
        walletEnvelopePreferences = getSharedPreferences(WALLET_ENVELOPE_PREFERENCES, MODE_PRIVATE)
        restorePendingOfflinePayments()
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

        if (router == null && !passphrase.isNullOrBlank()) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification("Starting local mesh…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification("Starting local mesh…"))
            }
            startMesh(passphrase)
        } else if (router == null) {
            emit("No saved group credentials. Open MeshNet and enter the group passphrase.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder
    fun setListener(callback: ((String) -> Unit)?) { listener = callback }
    fun isRunning(): Boolean = router != null

    fun sendMessage(message: String): Boolean {
        val activeRouter = router ?: throw IllegalStateException("Mesh service is not ready")
        return activeRouter.sendMessage(message)
    }

    fun offlineWalletAddress(): String = offlineWallet.address()

    @Synchronized
    fun sendOfflinePayment(recipientAddress: String, amountMinor: Long): Boolean {
        router ?: throw IllegalStateException("Start the offline mesh before sending a wallet transfer")
        val voucher = offlineWallet.createPayment(recipientAddress, amountMinor)
        val transferId = org.json.JSONObject(voucher).getString("id")
        offlineWallet.recordOutgoing(voucher)
        val queued = sendOfflineWalletEnvelope(OFFLINE_PAYMENT_PREFIX, transferId, voucher)
        if (queued) emit("Signed offline transfer queued · waiting for a signed recipient acknowledgement")
        return queued
    }

    private fun sendOfflineWalletEnvelope(prefix: String, transferId: String, payload: String): Boolean {
        val activeRouter = router ?: return false
        val encoded = android.util.Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        val chunks = encoded.chunked(OFFLINE_PAYMENT_CHUNK_SIZE)
        require(chunks.size in 1..MAX_OFFLINE_PAYMENT_CHUNKS) { "Offline wallet envelope is too large" }
        for ((index, chunk) in chunks.withIndex()) {
            val message = "$prefix|$transferId|$index|${chunks.size}|$chunk"
            if (message.toByteArray(Charsets.UTF_8).size > 120 || !activeRouter.sendMessage(message)) {
                emit("Offline wallet envelope queued only partially; it remains unconfirmed")
                return false
            }
        }
        return true
    }

    private fun handleRouterMessage(message: String) {
        if (!message.startsWith("$OFFLINE_PAYMENT_PREFIX|") && !message.startsWith("$OFFLINE_ACK_PREFIX|")) {
            emit(message)
            return
        }
        try {
            val fields = message.split('|', limit = 5)
            require(fields.size == 5 && fields[0] in setOf(OFFLINE_PAYMENT_PREFIX, OFFLINE_ACK_PREFIX)) {
                "Malformed offline wallet packet"
            }
            val isAcknowledgement = fields[0] == OFFLINE_ACK_PREFIX
            val transferId = fields[1]
            require(transferId.length in 1..64) { "Invalid offline wallet transfer ID" }
            val index = fields[2].toInt()
            val count = fields[3].toInt()
            require(count in 1..MAX_OFFLINE_PAYMENT_CHUNKS && index in 0 until count) {
                "Invalid offline wallet chunk index"
            }
            require(fields[4].length <= OFFLINE_PAYMENT_CHUNK_SIZE) { "Offline wallet chunk is too large" }
            val now = System.currentTimeMillis()
            val expired = pendingOfflinePayments.entries.removeAll { now - it.value.createdAt > WALLET_ENVELOPE_TTL_MS }
            val state = pendingOfflinePayments[transferId]
            val envelope = if (state == null) {
                require(pendingOfflinePayments.size < MAX_PENDING_OFFLINE_PAYMENTS) {
                    "Too many pending offline wallet transfers"
                }
                PendingOfflineWalletEnvelope(count, fields[0], mutableMapOf(), now)
                    .also { pendingOfflinePayments[transferId] = it }
            } else {
                require(state.count == count && state.prefix == fields[0]) { "Offline wallet envelope metadata changed" }
                state
            }
            val previous = envelope.chunks[index]
            require(previous == null || previous == fields[4]) { "Conflicting duplicate wallet chunk" }
            envelope.chunks[index] = fields[4]
            persistPendingOfflinePayments()
            if (envelope.chunks.size == count) {
                val encoded = (0 until count).joinToString("") {
                    envelope.chunks[it] ?: error("Offline wallet transfer is incomplete")
                }
                val payload = String(
                    android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                if (isAcknowledgement) {
                    val acknowledgementJson = org.json.JSONObject(payload)
                    if (acknowledgementJson.optString("sender") != offlineWallet.address()) {
                        pendingOfflinePayments.remove(transferId)
                        persistPendingOfflinePayments()
                        return
                    }
                    val acknowledged = offlineWallet.acceptAcknowledgement(payload)
                    emit("Recipient acknowledgement verified · ${acknowledged.amountMinor} minor units · ${acknowledged.recipient.take(12)}…")
                } else {
                    val json = org.json.JSONObject(payload)
                    if (json.optString("recipient") != offlineWallet.address()) {
                        pendingOfflinePayments.remove(transferId)
                        persistPendingOfflinePayments()
                        return
                    }
                    val receiptId = json.getString("id")
                    val receipt = try {
                        offlineWallet.acceptPayment(payload)
                    } catch (error: IllegalArgumentException) {
                        if (error.message?.contains("already imported on this device") == true) {
                            offlineWallet.receiptById(receiptId) ?: throw error
                        } else throw error
                    }
                    emit("Offline wallet receipt verified and saved · ${receipt.amountMinor} minor units · ${receipt.sender.take(12)}…")
                    val acknowledgement = offlineWallet.createAcknowledgement(receipt)
                    if (sendOfflineWalletEnvelope(OFFLINE_ACK_PREFIX, receipt.id, acknowledgement)) {
                        emit("Signed recipient acknowledgement queued")
                    }
                }
                pendingOfflinePayments.remove(transferId)
                persistPendingOfflinePayments()
            }
        } catch (error: Exception) {
            emit("Rejected offline wallet envelope: ${error.message ?: "invalid packet"}")
        }
    }

    fun sendFile(uri: Uri): Boolean {
        val transfer = fileTransfer ?: throw IllegalStateException("Mesh service is not ready")
        return transfer.send(uri)
    }

    fun cancelFileTransfers(): Boolean = fileTransfer?.cancelOutgoing() ?: false

    fun latestReceivedVoiceNote(): java.io.File? = fileTransfer?.latestVoiceNote()
    fun isLiveVoiceActive(): Boolean = liveVoiceCall?.isActive() == true

    fun startLiveVoiceCall(): String {
        val call = liveVoiceCall ?: throw IllegalStateException("Mesh service is not ready")
        promoteForMicrophone()
        return try { call.startOutgoing() } catch (error: Exception) {
            demoteMicrophoneForeground()
            throw error
        }
    }

    fun joinIncomingLiveVoiceCall(): String {
        val call = liveVoiceCall ?: throw IllegalStateException("Mesh service is not ready")
        promoteForMicrophone()
        return try { call.joinIncoming() } catch (error: Exception) {
            demoteMicrophoneForeground()
            throw error
        }
    }

    fun endLiveVoiceCall() {
        liveVoiceCall?.end()
        demoteMicrophoneForeground()
    }

    private fun promoteForMicrophone() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification("MeshNet live voice active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
    }

    private fun demoteMicrophoneForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification("MeshNet running"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    fun discoverWifiDirect() {
        wifiAwareTransport?.stop()
        val wifi = wifiTransport ?: throw IllegalStateException("Wi-Fi Direct is not ready")
        wifi.start()
        wifi.discover()
    }
    fun createWifiDirectGroup() {
        wifiAwareTransport?.stop()
        val wifi = wifiTransport ?: throw IllegalStateException("Wi-Fi Direct is not ready")
        wifi.start()
        wifi.createGroup()
    }
    fun connectWifiDirect(address: String) {
        wifiAwareTransport?.stop()
        val wifi = wifiTransport ?: throw IllegalStateException("Wi-Fi Direct is not ready")
        wifi.start()
        wifi.connect(address)
    }
    fun resumeWifiAware() {
        wifiTransport?.stop()
        wifiAwareTransport?.start() ?: throw IllegalStateException("Wi-Fi Aware is not ready")
    }

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
            onPayload = { plaintext ->
                if (plaintext.startsWith("$OFFLINE_PAYMENT_PREFIX|") || plaintext.startsWith("$OFFLINE_ACK_PREFIX|")) {
                    handleRouterMessage(plaintext)
                    true
                } else fileTransfer?.accept(plaintext) == true
            },
            sendEphemeralFrame = { frame ->
                val bleSent = transport?.sendToPeers(frame) == true
                val p2pSent = wifiTransport?.sendToPeers(frame) == true
                val awareSent = wifiAwareTransport?.sendToPeers(frame) == true
                bleSent || p2pSent || awareSent
            },
            onEphemeralPayload = { origin, plaintext -> liveVoiceCall?.accept(origin, plaintext) },
            sendRelay = { frame, ingress ->
                if (!outbox.enqueue(frame)) {
                    emit("Relay frame could not be stored in the durable outbox")
                    false
                } else {
                    transport?.sendToPeers(frame, ingress)
                    wifiTransport?.sendToPeers(frame, ingress)
                    wifiAwareTransport?.sendToPeers(frame, ingress)
                    true
                }
            },
            sendEphemeralRelay = { frame, ingress ->
                val bleSent = transport?.sendToPeers(frame, ingress) == true
                val p2pSent = wifiTransport?.sendToPeers(frame, ingress) == true
                val awareSent = wifiAwareTransport?.sendToPeers(frame, ingress) == true
                bleSent || p2pSent || awareSent
            }
        )
        processCompletedPendingOfflinePayments()
        liveVoiceCall = MeshLiveVoiceCall(
            context = this,
            localNodeId = nodeId,
            sendEphemeral = { text -> router?.sendEphemeral(text) ?: false },
            onEvent = { message ->
                emit(message)
                if (message.contains("ended by another participant")) demoteMicrophoneForeground()
            }
        )
        transport = MeshBleTransport(
            this,
            { address, connected ->
                emit("${if (connected) "Connected" else "Disconnected"}: $address")
            },
            { peer, payload -> router?.receive(payload, peer) },
            { state ->
                emit(state)
                handleTransportState(state)
            }
        )
        try {
            runCatching { transport?.start() }.onFailure { error ->
                emit("BLE unavailable on this device; continuing with local Wi-Fi transports: ${error.message}")
                runCatching { transport?.stop() }
                transport = null
            }
            wifiTransport = MeshWifiDirectTransport(
                this,
                { peer, payload -> router?.receive(payload, peer) },
                { state ->
                    emit(state)
                    if (state.contains("data channel ready")) handleTransportState("Wi-Fi Direct channel ready")
                }
            )
            wifiAwareTransport = MeshWifiAwareTransport(
                this,
                passphrase,
                { peer, payload -> router?.receive(payload, peer) },
                { state ->
                    emit(state)
                    if (state.contains("data channel ready")) handleTransportState("Wi-Fi Aware channel ready")
                }
            )
            runCatching { wifiAwareTransport?.start() }
                .onFailure { emit("Wi-Fi Aware unavailable; other transports remain active: ${it.message}") }
            emit("Mesh service started · BLE ${if (transport != null) "ready" else "unavailable"} · Node $nodeId; Wi-Fi Direct can be started when supported")
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
        if (!replayActive) return
        while (replayIndex < replayFrames.size) {
            val frame = replayFrames[replayIndex]
            // A receipt may have retired this message after the replay snapshot was created.
            if (!outbox.containsFrame(frame)) {
                replayIndex++
                continue
            }
            // A full per-peer BLE queue pauses this pass. Queue-drained callbacks resume it.
            val bleAccepted = transport?.sendToPeers(frame) == true
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
        liveVoiceCall?.end()
        liveVoiceCall = null
        fileTransfer?.cancelOutgoing()
        fileTransfer?.close()
        fileTransfer = null
        transport?.stop()
        transport = null
        wifiTransport?.stop()
        wifiTransport = null
        wifiAwareTransport?.stop()
        wifiAwareTransport = null
        router = null
        replayFrames = emptyList()
        replayIndex = 0
        replayActive = false
    }

    private fun restorePendingOfflinePayments() {
        pendingOfflinePayments.clear()
        val stored = runCatching {
            org.json.JSONArray(walletEnvelopePreferences.getString(KEY_PENDING_WALLET_ENVELOPES, "[]"))
        }.getOrDefault(org.json.JSONArray())
        val now = System.currentTimeMillis()
        for (i in 0 until stored.length()) {
            runCatching {
                val item = stored.getJSONObject(i)
                val id = item.getString("id")
                val count = item.getInt("count")
                val createdAt = item.getLong("createdAt")
                require(id.length in 1..64 && count in 1..MAX_OFFLINE_PAYMENT_CHUNKS)
                require(createdAt > 0L && now - createdAt <= WALLET_ENVELOPE_TTL_MS)
                val chunkObject = item.getJSONObject("chunks")
                val chunks = mutableMapOf<Int, String>()
                val keys = chunkObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val index = key.toInt()
                    val value = chunkObject.getString(key)
                    require(index in 0 until count && value.length <= OFFLINE_PAYMENT_CHUNK_SIZE)
                    chunks[index] = value
                }
                val prefix = item.getString("prefix")
                require(prefix == OFFLINE_PAYMENT_PREFIX || prefix == OFFLINE_ACK_PREFIX)
                require(chunks.size <= count)
                if (pendingOfflinePayments.size < MAX_PENDING_OFFLINE_PAYMENTS) {
                    pendingOfflinePayments[id] = PendingOfflineWalletEnvelope(count, prefix, chunks, createdAt)
                }
            }
        }
        persistPendingOfflinePayments()
    }

    private fun processCompletedPendingOfflinePayments() {
        pendingOfflinePayments.toList().forEach { (id, envelope) ->
            if (envelope.chunks.size == envelope.count) {
                val lastIndex = envelope.count - 1
                val lastChunk = envelope.chunks[lastIndex] ?: return@forEach
                handleRouterMessage("${envelope.prefix}|$id|$lastIndex|${envelope.count}|$lastChunk")
            }
        }
    }

    @Synchronized
    private fun persistPendingOfflinePayments() {
        val stored = org.json.JSONArray()
        pendingOfflinePayments.forEach { (id, envelope) ->
            val chunks = org.json.JSONObject()
            envelope.chunks.forEach { (index, value) -> chunks.put(index.toString(), value) }
            stored.put(
                org.json.JSONObject()
                    .put("id", id)
                    .put("count", envelope.count)
                    .put("prefix", envelope.prefix)
                    .put("createdAt", envelope.createdAt)
                    .put("chunks", chunks)
            )
        }
        check(walletEnvelopePreferences.edit().putString(KEY_PENDING_WALLET_ENVELOPES, stored.toString()).commit()) {
            "Could not persist pending offline wallet chunks"
        }
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
        private const val OFFLINE_PAYMENT_PREFIX = "MESHOP1"
        private const val OFFLINE_ACK_PREFIX = "MESHACK1"
        private const val WALLET_ENVELOPE_PREFERENCES = "meshnet_wallet_envelope_v1"
        private const val KEY_PENDING_WALLET_ENVELOPES = "pending_envelopes"
        private const val WALLET_ENVELOPE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val OFFLINE_PAYMENT_CHUNK_SIZE = 48
        private const val MAX_OFFLINE_PAYMENT_CHUNKS = 32
        private const val MAX_PENDING_OFFLINE_PAYMENTS = 16
    }
}
