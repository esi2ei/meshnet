package com.meshnet.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64

/**
 * Optional Wi-Fi Aware peer-to-peer transport. It uses discovery messages only for
 * connection negotiation and carries MeshNet frames over a TCP data path.
 * The group passphrase is used only as a Wi-Fi Aware PSK; frame confidentiality remains
 * the responsibility of the MeshNet application crypto layer.
 */
class MeshWifiAwareTransport(
    context: Context,
    groupPassphrase: String,
    private val onPayload: (String, ByteArray) -> Unit,
    private val onState: (String) -> Unit,
    private val onPeerConnection: (String, Boolean) -> Unit = { _, _ -> }
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val serverStarting = AtomicBoolean(false)
    private val serverRequests = ConcurrentHashMap.newKeySet<PeerHandle>()
    private val clientRequests = ConcurrentHashMap.newKeySet<PeerHandle>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val callbacks = CopyOnWriteArrayList<ConnectivityManager.NetworkCallback>()
    private val psk = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(groupPassphrase.toByteArray(Charsets.UTF_8)),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    @Volatile private var awareSession: WifiAwareSession? = null
    @Volatile private var publishSession: PublishDiscoverySession? = null
    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < 29) {
            running.set(false)
            onState("Wi-Fi Aware high-speed data paths require Android 10 or later; BLE/Wi-Fi Direct remain available")
            return
        }
        if (!app.packageManager.hasSystemFeature("android.hardware.wifi.aware")) {
            running.set(false)
            onState("Wi-Fi Aware is not supported on this device; BLE/Wi-Fi Direct remain available")
            return
        }
        val aware = manager
        if (aware == null || !aware.isAvailable) {
            running.set(false)
            onState("Wi-Fi Aware is currently unavailable; BLE/Wi-Fi Direct remain available")
            return
        }
        try {
            aware.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (!running.get()) {
                        session.close()
                        return
                    }
                    awareSession = session
                    startDiscovery(session)
                }

                override fun onAttachFailed() {
                    if (running.get()) onState("Wi-Fi Aware attach failed; other transports remain active")
                }
            }, handler)
        } catch (error: Exception) {
            running.set(false)
            onState("Wi-Fi Aware could not start: ${error.message}")
        }
    }

    private fun startDiscovery(session: WifiAwareSession) {
        try {
            session.publish(
                PublishConfig.Builder().setServiceName(SERVICE_NAME).build(),
                object : DiscoverySessionCallback() {
                    override fun onPublishStarted(session: PublishDiscoverySession) {
                        if (!running.get()) session.close()
                        else {
                            publishSession = session
                            onState("Wi-Fi Aware publisher ready")
                        }
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        if (message.contentEquals(HELLO)) establishPublisherPath(peerHandle)
                    }

                    override fun onSessionConfigFailed() {
                        onState("Wi-Fi Aware publish configuration failed")
                    }
                },
                handler
            )
            session.subscribe(
                SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build(),
                object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                        if (!running.get()) session.close()
                        else {
                            subscribeSession = session
                            onState("Wi-Fi Aware discovery active")
                        }
                    }

                    override fun onServiceDiscovered(
                        peerHandle: PeerHandle,
                        serviceSpecificInfo: ByteArray,
                        matchFilter: List<ByteArray>
                    ) {
                        if (running.get()) {
                            subscribeSession?.sendMessage(peerHandle, HELLO_ID, HELLO)
                        }
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        if (message.contentEquals(READY)) establishSubscriberPath(peerHandle)
                    }

                    override fun onSessionConfigFailed() {
                        onState("Wi-Fi Aware subscribe configuration failed")
                    }
                },
                handler
            )
        } catch (error: Exception) {
            onState("Wi-Fi Aware discovery failed: ${error.message}")
        }
    }

    @Synchronized
    private fun establishPublisherPath(peer: PeerHandle) {
        if (!running.get() || !serverRequests.add(peer)) return
        try {
            var listener = server
            if (listener == null) {
                if (!serverStarting.compareAndSet(false, true)) {
                    serverRequests.remove(peer)
                    return
                }
                listener = ServerSocket(0)
                server = listener
                startAcceptLoop(listener)
            }
            val discovery = publishSession ?: throw IllegalStateException("Wi-Fi Aware publisher is not ready")
            val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peer)
                .setPskPassphrase(psk)
                .setPort(listener.localPort)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (running.get()) publishSession?.sendMessage(peer, READY_ID, READY)
                }

                override fun onLost(network: Network) {
                    serverRequests.remove(peer)
                    onState("Wi-Fi Aware peer data path lost")
                }

                override fun onUnavailable() {
                    serverRequests.remove(peer)
                    onState("Wi-Fi Aware publisher data path unavailable")
                }
            }
            callbacks.add(callback)
            connectivity.requestNetwork(request, callback)
            onState("Wi-Fi Aware data path requested for discovered peer")
        } catch (error: Exception) {
            serverStarting.set(false)
            serverRequests.remove(peer)
            onState("Wi-Fi Aware publisher connection failed: ${error.message}")
        }
    }

    private fun establishSubscriberPath(peer: PeerHandle) {
        if (!running.get() || !clientRequests.add(peer)) return
        try {
            val discovery = subscribeSession ?: throw IllegalStateException("Wi-Fi Aware subscriber is not ready")
            val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peer)
                .setPskPassphrase(psk)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()
            val connectionStarted = AtomicBoolean(false)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!connectionStarted.compareAndSet(false, true)) return
                    val info = caps.transportInfo as? WifiAwareNetworkInfo
                        ?: run { connectionStarted.set(false); return }
                    val address = info.peerIpv6Addr
                        ?: run { connectionStarted.set(false); return }
                    val port = info.port
                    if (port !in 1..65535) {
                        connectionStarted.set(false)
                        return
                    }
                    connect(network, address, port, connectionStarted)
                }

                override fun onLost(network: Network) {
                    clientRequests.remove(peer)
                    connectionStarted.set(false)
                    onState("Wi-Fi Aware subscriber data path lost")
                }

                override fun onUnavailable() {
                    clientRequests.remove(peer)
                    onState("Wi-Fi Aware subscriber data path unavailable")
                }
            }
            callbacks.add(callback)
            connectivity.requestNetwork(request, callback)
            onState("Wi-Fi Aware subscriber data path requested")
        } catch (error: Exception) {
            clientRequests.remove(peer)
            onState("Wi-Fi Aware subscriber connection failed: ${error.message}")
        }
    }

    private fun startAcceptLoop(listener: ServerSocket) {
        Thread({
            try {
                while (running.get() && !listener.isClosed) {
                    val socket = listener.accept()
                    socket.tcpNoDelay = true
                    sockets.add(socket)
                    onPeerConnection(socket.inetAddress.hostAddress ?: "unknown", true)
                    onState("Wi-Fi Aware data channel ready")
                    startReader(socket)
                }
            } catch (error: Exception) {
                if (running.get()) onState("Wi-Fi Aware accept loop stopped: ${error.message}")
            } finally {
                serverStarting.set(false)
            }
        }, "meshnet-wifi-aware-accept").apply { isDaemon = true; start() }
    }

    private fun connect(
        network: Network,
        address: java.net.Inet6Address,
        port: Int,
        connectionStarted: AtomicBoolean
    ) {
        Thread({
            try {
                if (!running.get()) {
                    connectionStarted.set(false)
                    return@Thread
                }
                val socket = network.socketFactory.createSocket()
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                sockets.add(socket)
                onPeerConnection(socket.inetAddress.hostAddress ?: "unknown", true)
                onState("Wi-Fi Aware data channel ready")
                startReader(socket)
            } catch (error: Exception) {
                connectionStarted.set(false)
                if (running.get()) onState("Wi-Fi Aware socket connection failed: ${error.message}")
            }
        }, "meshnet-wifi-aware-connect").apply { isDaemon = true; start() }
    }

    private fun startReader(socket: Socket) {
        Thread({
            try {
                val input = DataInputStream(socket.getInputStream())
                while (running.get() && !socket.isClosed) {
                    val size = try { input.readInt() } catch (_: EOFException) { break }
                    if (size !in 1..MeshFrame.MAX_FRAME_BYTES) {
                        throw IllegalArgumentException("Invalid Wi-Fi Aware frame length")
                    }
                    val frame = ByteArray(size)
                    input.readFully(frame)
                    onPayload(socket.inetAddress.hostAddress ?: "wifi-aware", frame)
                }
            } catch (error: Exception) {
                if (running.get()) onState("Wi-Fi Aware peer stream ended: ${error.message}")
            } finally {
                sockets.remove(socket)
                runCatching { socket.close() }
                onPeerConnection(socket.inetAddress.hostAddress ?: "unknown", false)
                onState("Wi-Fi Aware peer disconnected")
            }
        }, "meshnet-wifi-aware-reader").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun sendToPeers(frame: ByteArray, excludePeer: String? = null, onlyPeer: String? = null): Boolean {
        if (frame.isEmpty() || frame.size > MeshFrame.MAX_FRAME_BYTES) return false
        var sent = false
        sockets.toList().filter { it.inetAddress.hostAddress != excludePeer && (onlyPeer == null || it.inetAddress.hostAddress == onlyPeer) }.forEach { socket ->
            try {
                synchronized(socket) {
                    val output = DataOutputStream(socket.getOutputStream())
                    output.writeInt(frame.size)
                    output.write(frame)
                    output.flush()
                }
                sent = true
            } catch (_: Exception) {
                sockets.remove(socket)
                runCatching { socket.close() }
                onPeerConnection(socket.inetAddress.hostAddress ?: "unknown", false)
            }
        }
        return sent
    }

    fun sendToPeer(frame: ByteArray, address: String): Boolean = sendToPeers(frame, onlyPeer = address)

    fun stop() {
        if (!running.getAndSet(false)) return
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { server?.close() }
        server = null
        callbacks.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callbacks.clear()
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        publishSession = null
        subscribeSession = null
        runCatching { awareSession?.close() }
        awareSession = null
        serverRequests.clear()
        clientRequests.clear()
        onState("Wi-Fi Aware stopped")
    }

    companion object {
        private const val SERVICE_NAME = "meshnet-v1"
        private const val HELLO_ID = 4101
        private const val READY_ID = 4102
        private val HELLO = "MESHNET_HELLO_V1".toByteArray(Charsets.UTF_8)
        private val READY = "MESHNET_READY_V1".toByteArray(Charsets.UTF_8)
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}
