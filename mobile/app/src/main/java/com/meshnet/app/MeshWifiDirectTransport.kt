package com.meshnet.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-controlled Wi-Fi Direct data plane. Discovery never auto-connects to unknown peers;
 * the user explicitly selects a peer by its discovered device address.
 */
@SuppressLint("MissingPermission")
class MeshWifiDirectTransport(
    context: Context,
    private val onPayload: (String, ByteArray) -> Unit,
    private val onState: (String) -> Unit
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private val running = AtomicBoolean(false)
    private val serverStarting = AtomicBoolean(false)
    private val clientConnecting = AtomicBoolean(false)
    private val sockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var server: ServerSocket? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    onState(if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "Wi-Fi Direct enabled" else "Wi-Fi Direct disabled")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    fun start() {
        if (running.get()) return
        check(app.packageManager.hasSystemFeature("android.hardware.wifi.direct")) {
            "Wi-Fi Direct is not supported on this device"
        }
        val p2p = manager ?: error("Wi-Fi Direct service is unavailable")
        running.set(true)
        try {
            channel = p2p.initialize(app, app.mainLooper, null)
                ?: error("Unable to initialize Wi-Fi Direct")
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, filter)
            }
            registered = true
            discover()
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    fun discover() {
        val p2p = manager ?: return
        val ch = channel ?: return
        p2p.discoverPeers(ch, actionListener("Wi-Fi Direct discovery started"))
    }

    fun createGroup() {
        val p2p = manager ?: error("Wi-Fi Direct service is unavailable")
        val ch = channel ?: error("Wi-Fi Direct is not started")
        p2p.createGroup(ch, actionListener("Wi-Fi Direct group creation requested"))
    }

    fun connect(deviceAddress: String) {
        require(deviceAddress.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) {
            "Enter a valid Wi-Fi Direct device address"
        }
        val p2p = manager ?: error("Wi-Fi Direct service is unavailable")
        val ch = channel ?: error("Wi-Fi Direct is not started")
        val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        p2p.connect(ch, config, actionListener("Wi-Fi Direct connection requested for $deviceAddress"))
    }

    private fun actionListener(success: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { onState(success) }
        override fun onFailure(reason: Int) { onState("$success failed: reason $reason") }
    }

    private fun requestPeers() {
        val p2p = manager ?: return
        val ch = channel ?: return
        p2p.requestPeers(ch) { list ->
            list.deviceList.forEach { device ->
                onState("Wi-Fi Direct peer: ${device.deviceName} | ${device.deviceAddress} | ${device.status}")
            }
        }
    }

    private fun requestConnectionInfo() {
        val p2p = manager ?: return
        val ch = channel ?: return
        p2p.requestConnectionInfo(ch) { info ->
            if (running.get() && info.groupFormed) {
                if (info.isGroupOwner) startServer()
                else info.groupOwnerAddress?.let(::connectToOwner)
            }
        }
    }

    private fun startServer() {
        if (server != null || !serverStarting.compareAndSet(false, true)) return
        Thread({
            try {
                val listener = ServerSocket(PORT)
                server = listener
                onState("Wi-Fi Direct group-owner socket listening")
                while (running.get() && !listener.isClosed) {
                    val socket = listener.accept()
                    socket.tcpNoDelay = true
                    sockets.add(socket)
                    onState("Wi-Fi Direct data channel ready")
                    startReader(socket)
                }
            } catch (error: Exception) {
                if (running.get()) onState("Wi-Fi Direct server stopped: ${error.message}")
            } finally {
                serverStarting.set(false)
            }
        }, "meshnet-wifi-p2p-accept").apply { isDaemon = true; start() }
    }

    private fun connectToOwner(address: InetAddress) {
        if (sockets.any { !it.isClosed } || !clientConnecting.compareAndSet(false, true)) return
        Thread({
            var lastError: Exception? = null
            try {
                repeat(CONNECT_ATTEMPTS) { attempt ->
                    if (!running.get() || sockets.any { !it.isClosed }) return@repeat
                    try {
                        val socket = Socket()
                        socket.connect(java.net.InetSocketAddress(address, PORT), CONNECT_TIMEOUT_MS)
                        socket.tcpNoDelay = true
                        sockets.add(socket)
                        onState("Wi-Fi Direct data channel ready")
                        startReader(socket)
                        return@Thread
                    } catch (error: Exception) {
                        lastError = error
                        if (attempt + 1 < CONNECT_ATTEMPTS) Thread.sleep(RETRY_DELAY_MS)
                    }
                }
                if (running.get()) onState("Wi-Fi Direct socket connection failed: ${lastError?.message}")
            } catch (error: Exception) {
                if (running.get()) onState("Wi-Fi Direct socket connection failed: ${error.message}")
            } finally {
                clientConnecting.set(false)
            }
        }, "meshnet-wifi-p2p-connect").apply { isDaemon = true; start() }
    }

    private fun startReader(socket: Socket) {
        Thread({
            try {
                val input = DataInputStream(socket.getInputStream())
                while (running.get() && !socket.isClosed) {
                    val size = try { input.readInt() } catch (_: EOFException) { break }
                    if (size !in 1..MeshFrame.MAX_FRAME_BYTES) throw IllegalArgumentException("Invalid Wi-Fi frame length")
                    val frame = ByteArray(size)
                    input.readFully(frame)
                    onPayload(socket.inetAddress.hostAddress ?: "wifi-direct", frame)
                }
            } catch (error: Exception) {
                if (running.get()) onState("Wi-Fi Direct peer stream ended: ${error.message}")
            } finally {
                sockets.remove(socket)
                runCatching { socket.close() }
                onState("Wi-Fi Direct peer disconnected")
            }
        }, "meshnet-wifi-p2p-reader").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun sendToPeers(frame: ByteArray): Boolean {
        if (frame.isEmpty() || frame.size > MeshFrame.MAX_FRAME_BYTES) return false
        var sent = false
        sockets.toList().forEach { socket ->
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
            }
        }
        return sent
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { server?.close() }
        server = null
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
        val p2p = manager
        val ch = channel
        if (p2p != null && ch != null) runCatching { p2p.removeGroup(ch, actionListener("Wi-Fi Direct group removed")) }
        channel = null
        onState("Wi-Fi Direct stopped")
    }

    companion object {
        private const val PORT = 43871
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val CONNECT_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 500L
    }
}
