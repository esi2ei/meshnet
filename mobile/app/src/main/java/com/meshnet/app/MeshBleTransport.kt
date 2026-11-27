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
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Android BLE discovery + GATT transport. Each phone advertises a GATT server
 * and connects as a GATT client to discovered peers. The application layer owns
 * encryption, deduplication and TTL-based relay.
 */
@SuppressLint("MissingPermission")
class MeshBleTransport(
    context: Context,
    private val onPeer: (String, Boolean) -> Unit,
    private val onPayload: (String, ByteArray) -> Unit,
    private val onState: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var server: BluetoothGattServer? = null
    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var running = false

    private val characteristic = BluetoothGattCharacteristic(
        CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && running) startRadio()
            else onState("GATT service registration failed: $status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onPeer(device.address, true)
                onState("GATT peer connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onPeer(device.address, false)
                onState("GATT peer disconnected: ${device.address}")
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
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid == CHARACTERISTIC_UUID && offset == 0) {
                onPayload(device.address, value.copyOf())
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                descriptor.value = value
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (seen.add(device.address)) {
                onState("Discovered BLE peer: ${device.address}")
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onState("BLE scan failed: $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            onState("BLE GATT service advertising")
        }
        override fun onStartFailure(errorCode: Int) {
            onState("BLE advertising failed: $errorCode")
        }
    }

    private fun clientCallback(address: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                clients.remove(address)
                seen.remove(address)
                onPeer(address, false)
                gatt.close()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                onPeer(address, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (!gatt.requestMtu(517)) gatt.discoverServices()
                } else gatt.discoverServices()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID)
            val remote = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (remote == null) {
                onState("Peer is missing the MeshNet GATT characteristic: $address")
                gatt.disconnect()
                return
            }
            if ((remote.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                onState("Peer does not expose a writable MeshNet channel: $address")
                gatt.disconnect()
                return
            }
            clients[address] = gatt
            onState("Mesh GATT channel ready: $address")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) onPayload(address, value.copyOf())
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) onPayload(address, characteristic.value ?: return)
        }
    }

    fun start() {
        if (running) return
        val bt = adapter ?: error("This device has no Bluetooth adapter")
        check(bt.isEnabled) { "Bluetooth is disabled" }
        running = true

        server = manager?.openGattServer(appContext, serverCallback)
            ?: error("Unable to open BLE GATT server")
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        characteristic.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(characteristic)
        check(server?.addService(service) == true) { "Unable to register MeshNet GATT service" }

        check(bt.bluetoothLeAdvertiser != null) { "BLE advertising is unsupported on this device" }
        check(bt.bluetoothLeScanner != null) { "BLE scanning is unsupported on this device" }
        onState("Registering MeshNet GATT service")
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
        bt.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bt.bluetoothLeScanner?.startScan(filters, scanSettings, scanCallback)
        onState("BLE discovery started")
    }

    private fun connect(device: BluetoothDevice) {
        if (clients.containsKey(device.address)) return
        val gatt = device.connectGatt(appContext, false, clientCallback(device), BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) clients[device.address] = gatt
    }

    fun sendToPeers(payload: ByteArray): Boolean {
        if (clients.isEmpty()) {
            onState("No connected BLE peers; message was not transmitted")
            return false
        }
        var accepted = false
        clients.entries.toList().forEach { (address, gatt) ->
            val remote = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            if (remote == null) {
                onState("Peer is not ready: $address")
                return@forEach
            }
            @Suppress("DEPRECATION")
            run {
                remote.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                remote.value = payload
                if (gatt.writeCharacteristic(remote)) accepted = true
                else onState("Write could not be queued: $address")
            }
        }
        return accepted
    }

    fun stop() {
        if (!running) return
        running = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        clients.values.forEach { it.disconnect(); it.close() }
        clients.clear()
        server?.close()
        server = null
        seen.clear()
        onState("BLE discovery stopped")
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000abce-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
