package com.meshnet.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE discovery + GATT transport with bounded application-level frame fragmentation.
 * Writes are serialized per peer; full frames are reassembled before reaching the router.
 */
@SuppressLint("MissingPermission")
class MeshBleTransport(
    context: Context,
    private val onPeer: (String, Boolean) -> Unit,
    private val onPayload: (String, ByteArray) -> Unit,
    private val onState: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val retryHandler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var server: BluetoothGattServer? = null
    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val negotiatedMtu = ConcurrentHashMap<String, Int>()
    private val writeQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val writing = ConcurrentHashMap.newKeySet<String>()
    private val assemblies = ConcurrentHashMap<String, Assembly>()
    private data class PeerHealth(
        var successes: Int = 0,
        var failures: Int = 0,
        var smoothedWriteMs: Double = 0.0,
        var lastWriteStartedAt: Long = 0L,
        var backoffUntil: Long = 0L
    )
    private val peerHealth = ConcurrentHashMap<String, PeerHealth>()
    @Volatile private var running = false

    private data class Assembly(
        val count: Int,
        val chunks: Array<ByteArray?>,
        var totalBytes: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val characteristic = BluetoothGattCharacteristic(
        CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && running) startRadio()
            else onState("GATT service registration failed: $status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                onPeer(device.address, true)
                onState("GATT peer connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                onPeer(device.address, false)
                onState("GATT peer disconnected: ${device.address} (status $status)")
                assemblies.keys.removeAll { it.startsWith("${device.address}|") }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val valid = characteristic.uuid == CHARACTERISTIC_UUID &&
                !preparedWrite && offset == 0 && acceptChunk(device.address, value)
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                    offset, null
                )
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.address == adapter?.address) return
            if (seen.add(device.address)) {
                onState("Discovered BLE peer: ${device.address}")
                connect(device)
            }
        }
        override fun onScanFailed(errorCode: Int) { onState("BLE scan failed: $errorCode") }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            onState("BLE GATT service advertising")
        }
        override fun onStartFailure(errorCode: Int) { onState("BLE advertising failed: $errorCode") }
    }

    private fun clientCallback(address: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeClient(address, gatt)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                onPeer(address, true)
                if (!gatt.requestMtu(MAX_MTU)) gatt.discoverServices()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu[address] = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onState("GATT service discovery failed: $address (status $status)")
                closeClient(address, gatt)
                return
            }
            val remote = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            if (remote == null ||
                (remote.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                onState("Peer is missing a writable MeshNet GATT channel: $address")
                closeClient(address, gatt)
                return
            }
            clients[address] = gatt
            onState("Mesh GATT channel ready: $address (MTU ${negotiatedMtu[address] ?: DEFAULT_MTU})")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) { completeWrite(address, status) }
    }

    fun start() {
        if (running) return
        val bt = adapter ?: error("This device has no Bluetooth adapter")
        check(bt.isEnabled) { "Bluetooth is disabled" }
        check(bt.bluetoothLeAdvertiser != null) { "BLE advertising is unsupported on this device" }
        check(bt.bluetoothLeScanner != null) { "BLE scanning is unsupported on this device" }
        running = true
        try {
            server = manager?.openGattServer(appContext, serverCallback)
                ?: error("Unable to open BLE GATT server")
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            if (characteristic.getDescriptor(CCCD_UUID) == null) {
                characteristic.addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }
            service.addCharacteristic(characteristic)
            check(server?.addService(service) == true) { "Unable to register MeshNet GATT service" }
            onState("Registering MeshNet GATT service")
        } catch (error: Exception) {
            running = false
            server?.close()
            server = null
            throw error
        }
    }

    private fun startRadio() {
        val bt = adapter ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        try {
            bt.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
            val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            bt.bluetoothLeScanner?.startScan(filters, scanSettings, scanCallback)
            onState("BLE discovery started")
        } catch (error: Exception) {
            onState("Unable to start BLE advertising or scanning: ${error.message}")
        }
    }

    private fun closeClient(address: String, gatt: BluetoothGatt) {
        val clientRemoved = clients.remove(address, gatt)
        val wasSeen = seen.remove(address)
        val wasKnown = clientRemoved || wasSeen
        negotiatedMtu.remove(address)
        writeQueues.remove(address)
        writing.remove(address)
        if (wasKnown) onPeer(address, false)
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun connect(device: BluetoothDevice) {
        if (clients.containsKey(device.address)) return
        try {
            val gatt = device.connectGatt(appContext, false, clientCallback(device.address), BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) clients[device.address] = gatt
            else {
                seen.remove(device.address)
                onState("Unable to create GATT connection: ${device.address}")
            }
        } catch (error: Exception) {
            seen.remove(device.address)
            onState("GATT connection failed: ${error.message}")
        }
    }

    fun sendToPeers(payload: ByteArray, excludePeer: String? = null): Boolean {
        if (payload.isEmpty() || payload.size > MeshFrame.MAX_FRAME_BYTES) {
            onState("Rejected oversized MeshNet frame")
            return false
        }
        var queuedAny = false
        clients.entries.toList().filter { it.key != excludePeer }.forEach { (address, gatt) ->
            val health = peerHealth.computeIfAbsent(address) { PeerHealth() }
            val now = System.currentTimeMillis()
            if (now < synchronized(health) { health.backoffUntil }) {
                onState("BLE peer temporarily backed off after write failures: $address")
                return@forEach
            }
            val mtu = (negotiatedMtu[address] ?: DEFAULT_MTU).coerceIn(DEFAULT_MTU, MAX_MTU)
            val dataSize = mtu - ATT_OVERHEAD - HEADER_BYTES
            val count = (payload.size + dataSize - 1) / dataSize
            if (count !in 1..MAX_CHUNKS) {
                onState("Frame cannot be fragmented for peer $address")
                return@forEach
            }
            val transferId = java.security.SecureRandom().nextInt()
            val queue = writeQueues.computeIfAbsent(address) { ArrayDeque() }
            var queuedForPeer = false
            synchronized(queue) {
                val failureCount = synchronized(health) { health.failures }
                val adaptiveLimit = adaptiveQueueLimit(failureCount, count)
                if (queue.size + count > adaptiveLimit) {
                    onState("BLE write queue is full for peer $address")
                    return@synchronized
                }
                for (index in 0 until count) {
                    val start = index * dataSize
                    val end = minOf(payload.size, start + dataSize)
                    queue.addLast(
                        ByteBuffer.allocate(HEADER_BYTES + end - start)
                            .putShort(MAGIC).putInt(transferId)
                            .putShort(index.toShort()).putShort(count.toShort())
                            .put(payload, start, end - start).array()
                    )
                }
                queuedForPeer = true
            }
            if (queuedForPeer) {
                queuedAny = true
                if (writing.add(address)) writeNext(address, gatt)
            }
        }
        if (!queuedAny) onState("No BLE peer accepted the frame for transmission")
        return queuedAny
    }

    private fun writeNext(address: String, gatt: BluetoothGatt) {
        val queue = writeQueues[address] ?: run { writing.remove(address); return }
        val chunk = synchronized(queue) { queue.peekFirst() }
        if (chunk == null) {
            writing.remove(address)
            return
        }
        val remote = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
        if (remote == null) {
            completeWrite(address, BluetoothGatt.GATT_FAILURE)
            return
        }
        peerHealth.computeIfAbsent(address) { PeerHealth() }.let { health ->
            synchronized(health) { health.lastWriteStartedAt = System.currentTimeMillis() }
        }
        @Suppress("DEPRECATION")
        run {
            remote.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            remote.value = chunk
            if (!gatt.writeCharacteristic(remote)) completeWrite(address, BluetoothGatt.GATT_FAILURE)
        }
    }

    private fun completeWrite(address: String, status: Int) {
        val queue = writeQueues[address]
        if (status != BluetoothGatt.GATT_SUCCESS) {
            queue?.let { synchronized(it) { it.clear() } }
            writing.remove(address)
            val health = peerHealth.computeIfAbsent(address) { PeerHealth() }
            val delay = synchronized(health) {
                health.failures = (health.failures + 1).coerceAtMost(5)
                health.backoffUntil = System.currentTimeMillis() + (250L shl health.failures.coerceAtMost(4))
                health.backoffUntil - System.currentTimeMillis()
            }
            onState("BLE peer write failed: $address status=$status; adaptive backoff ${delay}ms")
            retryHandler.postDelayed({
                if (running) onState("BLE write queue available: $address after adaptive backoff")
            }, delay)
            return
        }
        peerHealth.computeIfAbsent(address) { PeerHealth() }.let { health ->
            synchronized(health) {
                val elapsed = (System.currentTimeMillis() - health.lastWriteStartedAt).coerceAtLeast(0L).toDouble()
                health.smoothedWriteMs = if (health.smoothedWriteMs == 0.0) elapsed else health.smoothedWriteMs * 0.8 + elapsed * 0.2
                health.successes++
                if (health.successes >= 8) {
                    health.failures = 0
                    health.backoffUntil = 0L
                    health.successes = 0
                }
            }
        }
        val hasNext = queue?.let {
            synchronized(it) {
                if (it.isNotEmpty()) it.removeFirst()
                it.isNotEmpty()
            }
        } ?: false
        if (hasNext) clients[address]?.let { writeNext(address, it) }
        else {
            writeQueues.remove(address)
            writing.remove(address)
            onState("BLE write queue drained: $address")
        }
    }

    private fun acceptChunk(address: String, bytes: ByteArray): Boolean {
        if (bytes.size <= HEADER_BYTES) return false
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.short != MAGIC) return false
        val transferId = buffer.int
        val index = buffer.short.toInt() and 0xffff
        val count = buffer.short.toInt() and 0xffff
        if (count !in 1..MAX_CHUNKS || index !in 0 until count) return false
        val key = "$address|$transferId"
        val now = System.currentTimeMillis()
        assemblies.entries.removeAll { now - it.value.createdAt > ASSEMBLY_TTL_MS }
        if (!assemblies.containsKey(key) && assemblies.size >= MAX_ASSEMBLIES) {
            assemblies.entries.minByOrNull { it.value.createdAt }?.let { assemblies.remove(it.key) }
        }
        val existing = assemblies[key]
        val assembly = existing ?: run {
            val fresh = Assembly(count, arrayOfNulls(count))
            assemblies.putIfAbsent(key, fresh) ?: fresh
        }
        if (assembly.count != count) return false
        synchronized(assembly) {
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            val previous = assembly.chunks[index]
            if (previous != null) {
                if (!previous.contentEquals(chunk)) {
                    assemblies.remove(key)
                    return false
                }
                return true
            }
            assembly.totalBytes += chunk.size
            if (assembly.totalBytes > MeshFrame.MAX_FRAME_BYTES) {
                assemblies.remove(key)
                return false
            }
            assembly.chunks[index] = chunk
            if (assembly.chunks.all { it != null }) {
                val complete = ByteArray(assembly.totalBytes)
                var offset = 0
                assembly.chunks.forEach { part ->
                    val bytesPart = part ?: return false
                    bytesPart.copyInto(complete, offset)
                    offset += bytesPart.size
                }
                assemblies.remove(key)
                onPayload(address, complete)
            }
        }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        retryHandler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        clients.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        clients.clear()
        negotiatedMtu.clear()
        peerHealth.clear()
        writeQueues.clear()
        writing.clear()
        assemblies.clear()
        server?.close()
        server = null
        seen.clear()
        onState("BLE discovery stopped")
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000abce-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23
        private const val MAX_MTU = 517
        private const val ATT_OVERHEAD = 3
        private const val HEADER_BYTES = 10
        private const val MAGIC: Short = 0x4d4e
        private const val MAX_CHUNKS = 64
        private const val MAX_QUEUED_CHUNKS = 256
        private const val MAX_ASSEMBLIES = 32
        private const val ASSEMBLY_TTL_MS = 30_000L

        /**
         * Keep backoff pressure on the total queue while always allowing one whole frame.
         * At the default ATT MTU a maximum-size frame requires 64 chunks; clamping the
         * admission limit to 32 after repeated failures otherwise permanently starves it.
         */
        internal fun adaptiveQueueLimit(failures: Int, frameChunkCount: Int): Int {
            require(frameChunkCount in 1..MAX_CHUNKS) { "Invalid BLE frame chunk count" }
            return (MAX_QUEUED_CHUNKS shr failures.coerceIn(0, 3))
                .coerceAtLeast(frameChunkCount)
        }
    }
}
