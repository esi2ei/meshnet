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
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MeshForegroundService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): MeshForegroundService = this@MeshForegroundService
    }

    private val binder = LocalBinder()
    private val peerRegistry = MeshPeerRegistry()
    private val linkQualityRegistry = MeshLinkQualityRegistry()
    private val routeRegistry = MeshRouteRegistry(linkQualityRegistry::score)
    private var transport: MeshBleTransport? = null
    private var wifiTransport: MeshWifiDirectTransport? = null
    private var wifiAwareTransport: MeshWifiAwareTransport? = null
    private var router: MeshRouter? = null
    private var groupMembershipStore: MeshGroupMembershipStore? = null
    private var membershipSync: MeshMembershipSyncProtocol? = null
    private var localMembershipJoin: MeshMembershipEvent? = null
    private var fileTransfer: MeshFileTransfer? = null
    private var ipPacketRouter: MeshIpPacketRouter? = null
    private var ipPacketListener: ((ByteArray) -> Unit)? = null
    private var ipDnsForwarder: ((ByteArray) -> ByteArray?)? = null
    private var offlinePortalServer: MeshOfflinePortalServer? = null
    private lateinit var offlineLibrary: MeshOfflineLibrary
    private val activeLibraryResponses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingOfflineDownloadIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
    private var deviceIdentity: MeshDeviceIdentity? = null
    private var replayFrames: List<ByteArray> = emptyList()
    private var replayIndex = 0
    private var replayActive = false
    private var lastWalletRetryAt = 0L
    private var wifiDirectFallbackAttempted = false
    private val ipAnnouncementHandler = Handler(Looper.getMainLooper())
    private val ipAnnouncementTicker = object : Runnable {
        override fun run() {
            if (router == null || ipPacketRouter == null) return
            // Each active node refreshes only its own record. Peer discovery queries are
            // sent on startup/connection, not every interval, to avoid periodic mesh-wide floods.
            announceIpAddress()
            ipAnnouncementHandler.postDelayed(this, IP_ADDRESS_ANNOUNCE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        outbox = MeshQueueStore(this)
        secretStore = MeshSecretStore(this)
        offlineWallet = MeshOfflineWalletStore(this)
        offlineLibrary = MeshOfflineLibrary(this)
        offlineLibrary.items().filter { it.pending }.forEach { pendingOfflineDownloadIds.add(it.id) }
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
    fun connectedPeersJson(): org.json.JSONArray = if (router != null) peerRegistry.toJson() else org.json.JSONArray()
    fun observedRoutesJson(): org.json.JSONArray = if (router != null) routeRegistry.toJson() else org.json.JSONArray()
    fun localNodeIdValue(): String? = if (router != null) nodeId else null
    fun offlineNodeIpValue(): String? = ipPacketRouter?.localIp
    fun offlinePortalUrlValue(): String? {
        val id = localNodeIdValue() ?: return null
        if (offlinePortalServer?.isRunning != true) return null
        return "http://${MeshIpAddress.dnsNameForNodeId(id)}:${MeshOfflinePortalServer.DEFAULT_PORT}/"
    }
    fun reportExternalEvent(message: String) = emit(message)

    fun startIpOverlay(localIp: String) {
        router ?: throw IllegalStateException("Start the local mesh before enabling its IP overlay")
        require(localIp == MeshIpAddress.forNodeId(nodeId)) { "VPN address does not match this device identity" }
        if (ipPacketRouter != null) return
        val packetRouter = MeshIpPacketRouter(
            localNodeId = nodeId,
            localIp = localIp,
            sendToNode = { destination, payload -> router?.sendEphemeralTo(destination, payload) == true },
            deliverPacket = { packet -> ipPacketListener?.invoke(packet) },
            resolveName = { name ->
                val target = MeshIpAddress.nodeIdFromDnsName(name)
                when {
                    target == nodeId -> localIp
                    target != null -> ipPacketRouter?.addressForNode(target)
                    else -> null
                }
            },
            forwardDnsQuery = { query -> ipDnsForwarder?.invoke(query) }
        )
        ipPacketRouter = packetRouter
        offlinePortalServer = MeshOfflinePortalServer(localIp, offlineLibrary, onEvent = ::emit).also { it.start() }
        announceIpAddress()
        router?.sendEphemeral(IP_ADDRESS_QUERY)
        ipAnnouncementHandler.removeCallbacks(ipAnnouncementTicker)
        ipAnnouncementHandler.postDelayed(ipAnnouncementTicker, IP_ADDRESS_ANNOUNCE_INTERVAL_MS)
        emit("Offline IPv4 mesh active at $localIp · portal ${offlinePortalUrlValue()}")
    }

    fun setIpPacketListener(listener: ((ByteArray) -> Unit)?) { ipPacketListener = listener }
    fun setIpDnsForwarder(forwarder: ((ByteArray) -> ByteArray?)?) { ipDnsForwarder = forwarder }
    fun sendIpPacket(packet: ByteArray): Boolean = ipPacketRouter?.send(packet) == true

    fun stopIpOverlay() {
        ipAnnouncementHandler.removeCallbacks(ipAnnouncementTicker)
        val wasActive = ipPacketRouter != null || offlinePortalServer != null
        offlinePortalServer?.close()
        offlinePortalServer = null
        ipPacketRouter = null
        ipPacketListener = null
        ipDnsForwarder = null
        if (wasActive) emit("Offline IPv4 mesh stopped")
    }

    private fun announceIpAddress() {
        val address = ipPacketRouter?.announcement() ?: return
        router?.sendEphemeral(address)
    }

    private fun handleEphemeralPayload(origin: String, plaintext: String) {
        if (plaintext == IP_ADDRESS_QUERY) {
            if (ipPacketRouter != null) announceIpAddress()
            return
        }
        if (ipPacketRouter?.accept(origin, plaintext) == true) return
        liveVoiceCall?.accept(origin, plaintext)
    }
    fun offlineLibraryJson(): org.json.JSONArray = offlineLibrary.toJson()

    fun publishOfflineContent(uri: Uri): String {
        router ?: throw IllegalStateException("Start the local mesh before publishing offline content")
        val item = offlineLibrary.publish(uri)
        announceOfflineItem(item)
        emit("Published offline content to the mesh: ${item.name} (${item.size} bytes)")
        return item.id
    }

    fun requestOfflineContent(id: String): Boolean {
        val activeRouter = router ?: throw IllegalStateException("Start the local mesh before requesting content")
        val item = offlineLibrary.remoteItem(id) ?: throw IllegalArgumentException("Offline content is no longer advertised")
        require(offlineLibrary.markPending(id)) { "This content is already downloading or unavailable" }
        pendingOfflineDownloadIds.add(id)
        val accepted = try { activeRouter.sendMessageTo(item.origin ?: error("Missing provider identity"), "~L1Q|$id") }
        catch (error: Exception) { offlineLibrary.cancelPending(id); pendingOfflineDownloadIds.remove(id); throw error }
        if (!accepted) { offlineLibrary.cancelPending(id); pendingOfflineDownloadIds.remove(id) }
        else emit("Requested ${item.name} from mesh node ${item.origin}")
        return accepted
    }

    fun removeOfflineContent(id: String): Boolean = offlineLibrary.removeLocal(id)

    private fun announceOfflineItem(item: MeshOfflineLibrary.Item) {
        router?.sendMessage("~L1A|${item.id}|${item.size}|${item.name}")
    }
    private fun announceOfflineLibrary() {
        offlineLibrary.items().filter { it.local }.forEach(::announceOfflineItem)
    }

    private fun handleOfflineLibraryPayload(origin: String, plaintext: String): Boolean {
        if (plaintext.startsWith("~L1A|")) {
            val fields = plaintext.split('|', limit = 4)
            if (fields.size != 4) return true
            val size = fields[2].toLongOrNull() ?: return true
            runCatching { offlineLibrary.observeRemote(origin, fields[1], size, fields[3]) }
                .onSuccess { if (it) emit("Discovered offline content: ${fields[3]}") }
                .onFailure { emit("Ignored invalid offline content advertisement: ${it.message}") }
            return true
        }
        if (plaintext.startsWith("~L1Q|")) {
            val fields = plaintext.split('|')
            if (fields.size != 2 || !fields[1].matches(Regex("[a-f0-9]{64}"))) return true
            val id = fields[1]
            val file = offlineLibrary.localFile(id) ?: return true
            if (activeLibraryResponses.size >= MAX_LIBRARY_RESPONSES || !activeLibraryResponses.add(id)) return true
            Thread({
                try {
                    emit("Serving explicitly published offline content: ${file.name}")
                    fileTransfer?.send(Uri.fromFile(file))
                } catch (error: Exception) {
                    emit("Offline content transfer failed: ${error.message}")
                } finally { activeLibraryResponses.remove(id) }
            }, "meshnet-library-send").apply { isDaemon = true; start() }
            return true
        }
        return false
    }

    private fun onOfflineFileCompleted(file: java.io.File) {
        val digest = runCatching {
            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        }.getOrNull()
        val item = offlineLibrary.items().firstOrNull {
            !it.local && it.id in pendingOfflineDownloadIds && it.id == digest && it.size == file.length()
        }
        if (item != null && offlineLibrary.completeDownload(item.id, file)) {
            pendingOfflineDownloadIds.remove(item.id)
            emit("Downloaded and verified offline content: ${item.name}")
        }
    }

    private fun recordPeerConnection(transportName: String, address: String, connected: Boolean) {
        peerRegistry.setConnected(transportName, address, connected)
        if (connected && router != null) {
            announceOfflineLibrary()
            announceIpAddress()
            router?.sendEphemeral(IP_ADDRESS_QUERY)
            localMembershipJoin?.let { membershipSync?.publish(it) }
            membershipSync?.requestSync(force = true)
        }
        if (!connected) {
            val routeTransport = when (transportName) {
                "BLE/GATT" -> "BLE"
                "Wi-Fi Direct" -> "P2P"
                "Wi-Fi Aware" -> "AWARE"
                else -> null
            }
            if (routeTransport != null) {
                val peer = "$routeTransport/$address"
                routeRegistry.removePeer(peer)
                linkQualityRegistry.remove(peer)
            }
        }
        emit("$transportName peer ${if (connected) "connected" else "disconnected"}: $address")
    }

    fun sendMessage(message: String): Boolean {
        val activeRouter = router ?: throw IllegalStateException("Mesh service is not ready")
        return activeRouter.sendMessage(message)
    }

    fun sendDirectMessage(destinationNodeId: String, message: String): Boolean {
        val activeRouter = router ?: throw IllegalStateException("Mesh service is not ready")
        return activeRouter.sendMessageTo(destinationNodeId, message)
    }

    /** Sends transient packet-plane frames without placing them in the durable outbox. */
    private fun sendEphemeralMeshFrameToPeer(frame: ByteArray, peer: String): Boolean {
        val separator = peer.indexOf('/')
        if (separator <= 0 || separator == peer.lastIndex) return false
        val transportName = peer.substring(0, separator)
        val address = peer.substring(separator + 1)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val accepted = when (transportName) {
            "BLE" -> transport?.sendToPeer(frame, address) == true
            "P2P" -> wifiTransport?.sendToPeer(frame, address) == true
            "AWARE" -> wifiAwareTransport?.sendToPeer(frame, address) == true
            else -> false
        }
        linkQualityRegistry.record(peer, accepted, android.os.SystemClock.elapsedRealtime() - startedAt)
        return accepted
    }

    private fun sendMeshFrameToPeer(frame: ByteArray, peer: String): Boolean {
        val separator = peer.indexOf('/')
        if (separator <= 0 || separator == peer.lastIndex) return false
        if (!outbox.containsFrame(frame) && !outbox.enqueue(frame)) return false
        val transportName = peer.substring(0, separator)
        val address = peer.substring(separator + 1)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val accepted = when (transportName) {
            "BLE" -> transport?.sendToPeer(frame, address) == true
            "P2P" -> wifiTransport?.sendToPeer(frame, address) == true
            "AWARE" -> wifiAwareTransport?.sendToPeer(frame, address) == true
            else -> false
        }
        linkQualityRegistry.record(peer, accepted, android.os.SystemClock.elapsedRealtime() - startedAt)
        if (accepted && runCatching { MeshFrame.decode(frame).kind == MeshFrame.KIND_ACK }.getOrDefault(false)) {
            outbox.retireAcknowledgement(frame)
        }
        return accepted
    }

    private fun sendMeshFrameToOthers(frame: ByteArray, ingress: String?, durable: Boolean = true): Boolean {
        if (durable && !outbox.containsFrame(frame) && !outbox.enqueue(frame)) return false
        val separator = ingress?.indexOf('/') ?: -1
        val ingressTransport = if (separator > 0) ingress!!.substring(0, separator) else null
        val ingressAddress = if (separator > 0) ingress!!.substring(separator + 1) else ingress
        val bleSent = transport?.sendToPeers(frame, if (ingressTransport == "BLE") ingressAddress else null) == true
        val p2pSent = wifiTransport?.sendToPeers(frame, if (ingressTransport == "P2P") ingressAddress else null) == true
        val awareSent = wifiAwareTransport?.sendToPeers(frame, if (ingressTransport == "AWARE") ingressAddress else null) == true
        val accepted = bleSent || p2pSent || awareSent
        if (durable && accepted && runCatching { MeshFrame.decode(frame).kind == MeshFrame.KIND_ACK }.getOrDefault(false)) {
            outbox.retireAcknowledgement(frame)
        }
        return accepted
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

    /** Requeue unchanged signed vouchers whose recipient acknowledgement has not arrived. */
    @Synchronized
    fun retryPendingOfflinePayments(): Int {
        router ?: throw IllegalStateException("Start the offline mesh before retrying wallet transfers")
        val pending = offlineWallet.pendingOutgoingVouchers()
        var queued = 0
        pending.forEach { voucher ->
            if (sendOfflineWalletEnvelope(OFFLINE_PAYMENT_PREFIX, voucher.id, voucher.payload)) queued++
        }
        emit("Requeued $queued of ${pending.size} unconfirmed offline wallet transfers")
        return queued
    }

    private fun retryPendingOfflineWalletTransfers() {
        val now = System.currentTimeMillis()
        if (now - lastWalletRetryAt < WALLET_AUTO_RETRY_INTERVAL_MS) return
        lastWalletRetryAt = now
        val pending = runCatching { offlineWallet.pendingOutgoingVouchers() }.getOrDefault(emptyList())
        pending.forEach { voucher ->
            if (sendOfflineWalletEnvelope(OFFLINE_PAYMENT_PREFIX, voucher.id, voucher.payload)) {
                emit("Retrying unconfirmed offline wallet transfer " + voucher.id.take(8))
            }
        }
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
            require(
                fields[4].isNotEmpty() &&
                    fields[4].length <= OFFLINE_PAYMENT_CHUNK_SIZE &&
                    (index == count - 1 || fields[4].length == OFFLINE_PAYMENT_CHUNK_SIZE)
            ) { "Offline wallet chunk has an invalid length" }
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
                validateOfflineWalletEnvelopeId(transferId, payload, isAcknowledgement)
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
        val identity = try {
            deviceIdentity ?: MeshDeviceIdentity.loadOrCreate(this).also { deviceIdentity = it }
        } catch (error: Exception) {
            emit("Device identity could not be unlocked or created: ${error.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        nodeId = identity.nodeId
        val crypto = try {
            MeshCrypto(passphrase.toCharArray())
        } catch (error: Exception) {
            emit(error.message ?: "Invalid group passphrase")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val membershipStore = MeshGroupMembershipStore(this)
        groupMembershipStore = membershipStore
        val localCounter = membershipStore.events(crypto.groupId)
            .filter { it.memberId == nodeId }
            .maxOfOrNull { it.counter }?.plus(1L) ?: 1L
        val localJoin = MeshMembershipEvent.create(
            identity, crypto.groupId, localCounter, MeshMembershipEvent.JOIN
        )
        membershipStore.append(localJoin)
        localMembershipJoin = localJoin
        fileTransfer = MeshFileTransfer(
            context = this,
            sendMessage = { text -> router?.sendMessage(text) ?: false },
            onEvent = ::emit,
            canQueueBatch = outbox::canAcceptBatch,
            onCompleted = ::onOfflineFileCompleted
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
                    // an available local transport write queue, retain only the ability to regenerate it
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
            validateAcknowledgement = outbox::receiptMatches,
            recordDestination = outbox::recordDestination,
            forgetDestination = outbox::forgetDestination,
            identity = identity,
            onFramePayload = { origin, plaintext ->
                handleOfflineLibraryPayload(origin, plaintext)
            },
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
            onEphemeralPayload = { origin, plaintext ->
                if (membershipSync?.accept(origin, plaintext) != true) {
                    handleEphemeralPayload(origin, plaintext)
                }
            },
            sendRelay = { frame, ingress ->
                sendMeshFrameToOthers(frame, ingress)
                val queued = outbox.containsFrame(frame)
                if (queued) emit("Relay frame remains durable until a transport can accept it")
                else emit("Relay frame could not be stored in the durable outbox")
                queued
            },
            sendEphemeralRelay = { frame, ingress -> sendMeshFrameToOthers(frame, ingress, durable = false) },
            resolveNextHops = routeRegistry::nextHopsFor,
            sendToPeer = ::sendMeshFrameToPeer,
            sendEphemeralToPeer = ::sendEphemeralMeshFrameToPeer,
            onRouteObserved = { path, ingress ->
                if (routeRegistry.observe(path, ingressPeer = ingress)) emit("Observed mesh route: ${path.joinToString(" → ")}")
            }
        )
        membershipSync = MeshMembershipSyncProtocol(
            groupId = crypto.groupId,
            localNodeId = nodeId,
            store = membershipStore,
            send = { destination, payload ->
                val activeRouter = router
                if (destination == null) activeRouter?.sendEphemeral(payload) == true
                else activeRouter?.sendEphemeralTo(destination, payload) == true
            },
            onEvent = ::emit
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
            { address, connected -> recordPeerConnection("BLE/GATT", address, connected) },
            { peer, payload -> router?.receive(payload, "BLE/$peer") },
            { state ->
                emit(state)
                handleTransportState(state)
            },
            { address, rssi -> linkQualityRegistry.recordRssi("BLE/$address", rssi) }
        )
        try {
            runCatching { transport?.start() }.onFailure { error ->
                emit("BLE unavailable on this device; continuing with local Wi-Fi transports: ${error.message}")
                runCatching { transport?.stop() }
                transport = null
            }
            wifiTransport = MeshWifiDirectTransport(
                this,
                { peer, payload -> router?.receive(payload, "P2P/$peer") },
                { state ->
                    emit(state)
                    if (state.contains("data channel ready")) handleTransportState("Wi-Fi Direct channel ready")
                },
                { address, connected -> recordPeerConnection("Wi-Fi Direct", address, connected) }
            )
            wifiAwareTransport = MeshWifiAwareTransport(
                this,
                passphrase,
                { peer, payload -> router?.receive(payload, "AWARE/$peer") },
                { state ->
                    emit(state)
                    if (state.contains("data channel ready")) handleTransportState("Wi-Fi Aware channel ready")
                },
                { address, connected -> recordPeerConnection("Wi-Fi Aware", address, connected) }
            )
            runCatching { wifiAwareTransport?.start() }
                .onFailure { emit("Wi-Fi Aware unavailable; other transports remain active: ${it.message}") }
            if (transport == null) startWifiDirectFallback("BLE could not initialize")
            emit("Mesh service started · BLE ${if (transport != null) "initialized" else "unavailable"} · Node $nodeId; local Wi-Fi fallback is available when supported")
            localMembershipJoin?.let { membershipSync?.publish(it) }
            announceOfflineLibrary()
        } catch (error: Exception) {
            emit(error.message ?: "Unable to start BLE")
            stopMesh()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized
    private fun handleTransportState(state: String) {
        if (state.contains("BLE advertising failed") ||
            state.contains("BLE scanning failed") ||
            state.contains("GATT service registration failed") ||
            state.contains("missing radio role")) {
            startWifiDirectFallback(state)
        }
        when {
            state.contains("channel ready") -> {
                if (!replayActive) beginOutboxReplay() else pumpOutboxReplay()
                retryPendingOfflineWalletTransfers()
            }
            state.contains("write queue drained") || state.contains("write queue available") ->
                pumpOutboxReplay()
        }
    }

    @Synchronized
    private fun startWifiDirectFallback(reason: String) {
        if (wifiDirectFallbackAttempted) return
        val wifi = wifiTransport ?: return
        wifiDirectFallbackAttempted = true
        runCatching { wifi.start() }
            .onSuccess { emit("Wi-Fi Direct fallback discovery started after: $reason") }
            .onFailure { emit("Wi-Fi Direct fallback unavailable: ${it.message}; Wi-Fi Aware/BLE may still be used") }
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
        emit("Queued $queued durable frames across available local transports; messages retire on group receipt")
    }

    @Synchronized
    private fun stopMesh() {
        val activeJoin = localMembershipJoin
        val membershipStore = groupMembershipStore
        val signingIdentity = deviceIdentity
        if (activeJoin != null && membershipStore != null && signingIdentity != null) {
            runCatching {
                val lastCounter = membershipStore.events(activeJoin.groupId)
                    .filter { it.memberId == nodeId }
                    .maxOfOrNull { it.counter } ?: 0L
                require(lastCounter < Long.MAX_VALUE) { "Membership event counter exhausted" }
                val leave = MeshMembershipEvent.create(
                    signingIdentity, activeJoin.groupId, lastCounter + 1L, MeshMembershipEvent.LEAVE
                )
                membershipStore.append(leave)
                membershipSync?.publish(leave)
            }.onFailure { emit("Could not record local group departure: ${it.message}") }
        }
        localMembershipJoin = null
        stopIpOverlay()
        stopService(Intent(this, MeshVpnService::class.java))
        liveVoiceCall?.end()
        liveVoiceCall = null
        fileTransfer?.cancelOutgoing()
        fileTransfer?.close()
        fileTransfer = null
        // Detach the router before closing sockets so late reader callbacks cannot
        // repopulate route observations while transports are shutting down.
        router = null
        membershipSync = null
        localMembershipJoin = null
        groupMembershipStore?.close()
        groupMembershipStore = null
        transport?.stop()
        transport = null
        wifiTransport?.stop()
        wifiTransport = null
        wifiAwareTransport?.stop()
        wifiAwareTransport = null
        peerRegistry.clear()
        routeRegistry.clear()
        replayFrames = emptyList()
        replayIndex = 0
        replayActive = false
        wifiDirectFallbackAttempted = false
        activeLibraryResponses.clear()
        pendingOfflineDownloadIds.clear()
        offlineLibrary.items().filter { it.pending }.forEach { pendingOfflineDownloadIds.add(it.id) }
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
        private const val WALLET_AUTO_RETRY_INTERVAL_MS = 30_000L
        private const val OFFLINE_PAYMENT_CHUNK_SIZE = 48
        private const val MAX_OFFLINE_PAYMENT_CHUNKS = 32
        private const val MAX_PENDING_OFFLINE_PAYMENTS = 16
        private const val MAX_LIBRARY_RESPONSES = 2
        private const val IP_ADDRESS_QUERY = "~IP1Q"
        private const val IP_ADDRESS_ANNOUNCE_INTERVAL_MS = 60_000L
    }
}


/**
 * Bind the transport-level chunk envelope to the signed voucher/acknowledgement identity.
 * The transport ID is not trusted merely because the payload is group-encrypted.
 */
internal fun validateOfflineWalletEnvelopeId(
    transferId: String,
    payload: String,
    isAcknowledgement: Boolean
) {
    require(transferId.length in 1..64) { "Invalid offline wallet transfer ID" }
    val json = org.json.JSONObject(payload)
    val payloadId = if (isAcknowledgement) json.getString("voucherId") else json.getString("id")
    require(payloadId == transferId) { "Offline wallet envelope ID does not match its signed payload" }
}
