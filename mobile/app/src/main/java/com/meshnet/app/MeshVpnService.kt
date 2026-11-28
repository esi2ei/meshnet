package com.meshnet.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean


/** Accept only a DNS response for the outstanding transaction identifier. */
internal fun isMatchingDnsResponse(query: ByteArray, response: ByteArray): Boolean {
    if (query.size < 12 || response.size < 12) return false
    if (query[0] != response[0] || query[1] != response[1]) return false
    val flags = response[2].toInt() and 0xff
    return (flags and 0x80) != 0
}

/**
 * Rate-limited, cumulative diagnostics for packets the mesh did not fully accept.
 *
 * This makes the existing best-effort IP path observable; it does not queue or retry packets.
 */
internal class MeshVpnPacketDropReporter(
    private val nowMillis: () -> Long,
    private val onReport: (Long) -> Unit,
    private val reportIntervalMillis: Long = DEFAULT_REPORT_INTERVAL_MS
) {
    private var droppedPackets = 0L
    private var lastReportAt: Long? = null

    init {
        require(reportIntervalMillis > 0L) { "Report interval must be positive" }
    }

    @Synchronized
    fun recordDrop(): Long {
        droppedPackets += 1
        val now = nowMillis()
        val last = lastReportAt
        if (last == null || now - last >= reportIntervalMillis) {
            lastReportAt = now
            onReport(droppedPackets)
        }
        return droppedPackets
    }

    companion object {
        const val DEFAULT_REPORT_INTERVAL_MS = 5_000L
    }
}

/**
 * User-consented split-tunnel VPN for the MeshNet private IPv4 overlay.
 * Only 100.64.0.0/10 is routed into this TUN; public Internet traffic is untouched.
 */
class MeshVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private val outputLock = Any()
    private var meshService: MeshForegroundService? = null
    private var bound = false
    private var tun: ParcelFileDescriptor? = null
    private var input: ParcelFileDescriptor.AutoCloseInputStream? = null
    private var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var reader: Thread? = null

    private val meshConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? MeshForegroundService.LocalBinder)?.service()
            if (service == null) {
                fail("Mesh service binding failed")
                return
            }
            meshService = service
            establishTunnel(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fail("Mesh service disconnected; offline IP network stopped")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (!bound) {
                bound = bindService(
                    Intent(this, MeshForegroundService::class.java),
                    meshConnection,
                    Context.BIND_AUTO_CREATE
                )
                if (!bound) fail("Could not bind to the running MeshNet service")
            }
        } catch (error: Exception) {
            fail("Unable to start offline IP network: ${error.message}")
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MeshNet offline network", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notification = builder.setContentTitle("MeshNet offline network")
            .setContentText("Private IPv4 mesh is active; public Internet routing is unchanged")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun establishTunnel(service: MeshForegroundService) {
        if (!service.isRunning()) {
            fail("Start MeshNet radio mesh before enabling the offline IP network")
            return
        }
        val nodeId = service.localNodeIdValue()
        if (nodeId.isNullOrBlank()) {
            fail("Mesh device identity is not ready")
            return
        }
        val localIp = MeshIpAddress.forNodeId(nodeId)
        try {
            val descriptor = Builder()
                .setSession("MeshNet offline IPv4 mesh")
                .setMtu(TUN_MTU)
                .addAddress(localIp, 32)
                .addRoute(OVERLAY_NETWORK, OVERLAY_PREFIX)
                .addDnsServer(MeshIpPacketRouter.DNS_SERVICE_IP)
                .addSearchDomain("mesh")
                .establish() ?: throw IOException("Android did not establish the VPN interface")

            tun = descriptor
            // The TUN must be established first so Android recognizes this as an active VPN
            // before the systemExempted foreground-service type is requested.
            startAsForeground()
            input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
            output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
            service.setIpPacketListener(::writePacket)
            service.setIpDnsForwarder(::forwardDnsQuery)
            service.startIpOverlay(localIp)
            running.set(true)
            reader = Thread({ readPackets(service) }, "meshnet-ip-tun-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            fail("Offline IP interface failed: ${error.message}")
        }
    }

    /**
     * Forward non-mesh DNS lookups over the underlying physical network, outside the VPN.
     * With no uplink this returns null and the mesh resolver returns SERVFAIL; .mesh names
     * are always resolved locally and never leave the device.
     */
    private fun forwardDnsQuery(query: ByteArray): ByteArray? {
        if (query.size !in 12..MAX_DNS_QUERY_BYTES) return null
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val physicalNetwork = connectivity.allNetworks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: return null
        val dnsServer = connectivity.getLinkProperties(physicalNetwork)?.dnsServers?.firstOrNull()
            ?: return null
        return runCatching {
            DatagramSocket().use { socket ->
                if (!protect(socket)) return null
                physicalNetwork.bindSocket(socket)
                socket.soTimeout = DNS_TIMEOUT_MS
                socket.connect(InetSocketAddress(dnsServer, DNS_PORT))
                socket.send(DatagramPacket(query, query.size))
                val buffer = ByteArray(MAX_DNS_RESPONSE_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val payload = response.data.copyOf(response.length)
                payload.takeIf { isMatchingDnsResponse(query, it) }
            }
        }.getOrNull()
    }

    private fun readPackets(service: MeshForegroundService) {
        val stream = input ?: return
        val buffer = ByteArray(MeshIpPacketRouter.MAX_PACKET_BYTES)
        val dropReporter = MeshVpnPacketDropReporter(
            nowMillis = SystemClock::elapsedRealtime,
            onReport = { droppedPackets ->
                service.reportExternalEvent(
                    "Offline IP overlay: $droppedPackets outgoing packet(s) were not fully accepted by the mesh; delivery is not confirmed. Check the route and retry the transfer."
                )
            }
        )
        try {
            while (running.get()) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (!service.sendIpPacket(buffer.copyOf(count))) {
                    dropReporter.recordDrop()
                }
            }
        } catch (_: IOException) {
            if (running.get()) fail("Offline IP tunnel read failed")
        }
    }

    private fun writePacket(packet: ByteArray) {
        synchronized(outputLock) {
            val stream = output ?: return
            try {
                stream.write(packet)
                stream.flush()
            } catch (_: IOException) {
                if (running.get()) fail("Offline IP tunnel delivery failed")
            }
        }
    }

    private fun fail(message: String) {
        meshService?.let { service ->
            runCatching { service.setIpPacketListener(null) }
            runCatching { service.setIpDnsForwarder(null) }
            runCatching { service.stopIpOverlay() }
        }
        meshService?.reportExternalEvent(message)
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        running.set(false)
        meshService?.let { service ->
            runCatching { service.setIpPacketListener(null) }
            runCatching { service.setIpDnsForwarder(null) }
            runCatching { service.stopIpOverlay() }
        }
        meshService = null
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { tun?.close() }
        input = null
        output = null
        tun = null
        reader?.interrupt()
        reader = null
        if (bound) {
            runCatching { unbindService(meshConnection) }
            bound = false
        }
    }

    override fun onRevoke() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.meshnet.app.action.START_OFFLINE_IP"
        const val ACTION_STOP = "com.meshnet.app.action.STOP_OFFLINE_IP"
        private const val CHANNEL_ID = "meshnet_offline_ip"
        private const val NOTIFICATION_ID = 4217
        private const val TUN_MTU = 1500
        private const val OVERLAY_NETWORK = "100.64.0.0"
        private const val OVERLAY_PREFIX = 10
        private const val DNS_PORT = 53
        private const val DNS_TIMEOUT_MS = 750
        private const val MAX_DNS_QUERY_BYTES = 1_472
        private const val MAX_DNS_RESPONSE_BYTES = 4_096
    }
}
