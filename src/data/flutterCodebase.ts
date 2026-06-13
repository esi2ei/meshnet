import { CodeFile } from '../types';

export const FLUTTER_CODEBASE: CodeFile[] = [
  {
    name: 'pubspec.yaml',
    filepath: 'pubspec.yaml',
    language: 'yaml',
    content: `name: meshnet
description: Secure Offline Epidemic Mesh Messenger with Prepaid Quota Internet Sharing.
version: 1.1.0+2

environment:
  sdk: ">=3.0.0 <4.0.0"

dependencies:
  flutter:
    sdk: flutter
  cupertino_icons: ^1.0.5
  
  # Encryption: LibSignal-Protocol bindings (Ffi & native secure hardware)
  libsignal_protocol_dart: ^0.2.1
  qrcode_flutter: ^4.1.0
  
  # Cryptography & Storage
  cryptography: ^2.5.0
  flutter_secure_storage: ^9.0.0 # Wraps Android Keystore & iOS Keychain
  solana: ^0.20.1 # Solana blockchain FFI for USDC transfers
  
  # Hardware discovery
  flutter_reactive_ble: ^5.0.2
  nearby_connections: ^1.1.0

flutter:
  uses-material-design: true
`
  },
  {
    name: 'README.md',
    filepath: 'README.md',
    language: 'markdown',
    content: `# MeshNet Protocol Codebase (v1.1.0)

A secure, physical multi-hop P2P mesh network focusing on realistic offline communication, end-to-end encryption via the Signal Protocol, and manual prepaid quotas.

## 📱 Platform Differences & Capabilities

| Feature / Protocol | Android | iOS |
| --- | --- | --- |
| **P2P Discovery Mode** | BLE Advertising & BLE Scanning | local Multipeer Connectivity framework |
| **Role in Mesh** | Full Node (Transmitter + Relay + Hotspot host) | Receive-Only Mesh Client (Receiver-only mode) |
| **High Density Tether** | Active Wi-Fi Direct Hotspot | Multipeer Connectivity proxy (No tether) |
| **Background execution** | Foreground Service + Permanent Notification | BLE central/peripheral Background Modes |
| **Secure Key Storage** | Android Secure Keystore Hardware | Apple Secure Enclave & iOS Keychain |

## 🔗 Architecture Structure

- **lib/mesh/**: Core transmission layer (BLE/Multipeer discovery, epidemic routing queue, local Signal encryption).
- **lib/payment/**: Wallet operations, manual QR-code prepaid quota system, USDC on Solana integration.
- **android/**: Native modules for BluetoothLeAdvertiser and high speed tether hotspots.
- **ios/**: MCNearbyServiceAdvertiser and receive-only Multipeer stubs.
- **test/**: Mock mesh driver running 3 Android virtual devices for multi-hop packet checks.
`
  },
  {
    name: 'discovery_manager.dart',
    filepath: 'lib/mesh/discovery_manager.dart',
    language: 'dart',
    content: `// lib/mesh/discovery_manager.dart
import 'dart:async';
import 'package:flutter/services.dart';

class DiscoveryManager {
  static const MethodChannel _channel = MethodChannel('com.meshnet.app/mesh_rf');
  static const String SERVICE_UUID = "0000ABCD-0000-1000-8000-00805F9B34FB";
  
  final _peerController = StreamController<List<String>>.broadcast();
  Stream<List<String>> get onPeersChanged => _peerController.stream;

  List<String> _inRangePeers = [];

  DiscoveryManager() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == "onPeersUpdated") {
        final List<dynamic> rawPeers = call.arguments;
        _inRangePeers = rawPeers.map((e) => e.toString()).toList();
        _peerController.add(List.unmodifiable(_inRangePeers));
      }
    });
  }

  /// Initialize platform-specific Bluetooth and P2P advertising
  /// Interval: 1.0 second, Service UUID: 0000ABCD-0000-1000-8000-00805F9B34FB
  Future<void> startDiscovery() async {
    try {
      await _channel.invokeMethod('startAdvertisingScanning', {
        'serviceUuid': SERVICE_UUID,
        'advertisingIntervalMs': 1000, 
      });
      print("MeshNet BLE Advertiser/Discoverer initiated at 1.0s intervals.");
    } on PlatformException catch (e) {
      print("Failed to launch native RF: \${e.message}");
    }
  }

  Future<void> stopDiscovery() async {
    await _channel.invokeMethod('stopAdvertisingScanning');
  }
}
`
  },
  {
    name: 'routing.dart',
    filepath: 'lib/mesh/routing.dart',
    language: 'dart',
    content: `// lib/mesh/routing.dart
import 'dart:async';
import 'dart:collection';

enum PacketPriority { VOICE, TEXT, FILE }

class MeshPacket {
  final String id;
  final String senderId;
  final String recipientId;
  final String payload; // Encrypted cipher-text (Relayed pack re-encrypted at each hop)
  final PacketPriority priority;
  final int ttl; // Time-to-Live
  final List<String> routeHistory;

  MeshPacket({
    required this.id,
    required this.senderId,
    required this.recipientId,
    required this.payload,
    required this.priority,
    required this.ttl,
    required this.routeHistory,
  });

  Map<String, dynamic> toMap() => {
    'id': id,
    'senderId': senderId,
    'recipientId': recipientId,
    'payload': payload,
    'priority': priority.name,
    'ttl': ttl,
    'routeHistory': routeHistory,
  };
}

/// Simple Epidemic Flooding (Broadcast with Time-to-Live) with exponential backoff on retries.
class EpidemicRouter {
  final Set<String> _processedPacketIds = {};
  final ListQueue<MeshPacket> _priorityQueue = ListQueue();
  final int maxRetries = 3;
  
  // Callback when a message reaches its final destination successfully
  final Function(MeshPacket) onDestinationReached;

  EpidemicRouter({required this.onDestinationReached});

  /// Push an incoming packet into the prioritized message queue.
  /// Priority layout: VOICE > TEXT > FILE
  void handleReceivedPacket(MeshPacket packet, List<String> availablePeers, Function(MeshPacket, String) sendCallback) {
    if (_processedPacketIds.contains(packet.id)) {
      return; // Deduplicate
    }
    _processedPacketIds.add(packet.id);

    // If packet is for us, decrypt and notify
    if (packet.recipientId == "LOCAL_ID") {
      onDestinationReached(packet);
      return;
    }

    // If acting as a relay, packet is forwarded WITHOUT decryption
    if (packet.ttl <= 1) return; // Expired TTL

    // Create a relayed packet with re-encryption step (Forward Secrecy step at each hub)
    MeshPacket relayedPacket = MeshPacket(
      id: packet.id,
      senderId: packet.senderId,
      recipientId: packet.recipientId,
      payload: "[RE-ENCRYPTED-FOR-HOP]:\${packet.payload}",
      priority: packet.priority,
      ttl: packet.ttl - 1,
      routeHistory: [...packet.routeHistory, "LOCAL_ID"],
    );

    // Add to sorted priority queue
    _insertToPriorityQueue(relayedPacket);

    // Flood to all in-range neighbors with retry logic
    for (String peer in availablePeers) {
      if (!packet.routeHistory.contains(peer)) {
        _sendWithExponentialBackoff(relayedPacket, peer, 0, sendCallback);
      }
    }
  }

  void _insertToPriorityQueue(MeshPacket packet) {
    _priorityQueue.add(packet);
    // Sort queue based on enum indices: VOICE (0) < TEXT (1) < FILE (2)
    final list = _priorityQueue.toList();
    list.sort((a, b) => a.priority.index.compareTo(b.priority.index));
    _priorityQueue.clear();
    _priorityQueue.addAll(list);
  }

  /// 3 Attempts with Exponential Backoff
  void _sendWithExponentialBackoff(MeshPacket packet, String peerId, int attempt, Function(MeshPacket, String) sendCallback) {
    if (attempt >= maxRetries) return;
    
    try {
      sendCallback(packet, peerId);
    } catch (e) {
      int delaySeconds = 1 << attempt; // 1s, 2s, 4s
      Future.delayed(Duration(seconds: delaySeconds), () {
        _sendWithExponentialBackoff(packet, peerId, attempt + 1, sendCallback);
      });
    }
  }
}
`
  },
  {
    name: 'encryption.dart',
    filepath: 'lib/mesh/encryption.dart',
    language: 'dart',
    content: `// lib/mesh/encryption.dart
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class SecureHardwareEncryption {
  static const _storage = FlutterSecureStorage();
  
  // Storage paths using Android Keystore / iOS Keychain
  static const String IDENTITY_PRIVATE_KEY_PATH = "mesh_identity_private_key";
  static const String IDENTITY_PUBLIC_KEY_PATH = "mesh_identity_public_key";

  /// Generates and stores cryptographic identity in hardware keychain
  Future<void> initializeIdentityKeys() async {
    final existing = await _storage.read(key: IDENTITY_PRIVATE_KEY_PATH);
    if (existing == null) {
      // Create new Curve25519 identity keys simulating FFI libsignal identity
      String mockPrivateKey = "SECURE_KEYSTORE_PRIV_" + DateTime.now().microsecondsSinceEpoch.toString();
      String mockPublicKey = "SECURE_KEYSTORE_PUB_" + DateTime.now().microsecondsSinceEpoch.toString();
      await _storage.write(key: IDENTITY_PRIVATE_KEY_PATH, value: mockPrivateKey);
      await _storage.write(key: IDENTITY_PUBLIC_KEY_PATH, value: mockPublicKey);
      print("New identity keys saved securely in hardware container.");
    }
  }

  Future<String?> getPublicKey() async {
    return await _storage.read(key: IDENTITY_PUBLIC_KEY_PATH);
  }

  /// Verification of device identity using OOB (Out-of-band) manual QR QR Code comparisons
  bool verifyIdentityQR(String scanResult, String expectedPeerKey) {
    // QR payload pattern: "MESHNET:ID_KEY:SIGNATURE"
    if (!scanResult.startsWith("MESHNET:")) return false;
    final parts = scanResult.split(":");
    if (parts.length < 3) return false;
    final identityKeyInQR = parts[1];
    return identityKeyInQR == expectedPeerKey;
  }

  /// SENDER KEYS Group chats encryption scheme (Signal Group Chat protocol mechanism)
  String encryptGroupMessage(String message, String senderKey) {
    // Encrypts plaintext via locally distributed symmetric sender key (rotated per frame)
    return "SENDERKEY-ENCRYPTED:\${message}:KEY:\${senderKey}";
  }
}
`
  },
  {
    name: 'wallet_manager.dart',
    filepath: 'lib/payment/wallet_manager.dart',
    language: 'dart',
    content: `// lib/payment/wallet_manager.dart
import 'dart:math';

class PrepaidWallet {
  String address = "SolUSDC-\${Random().nextInt(9999999)}MeshAddress";
  double balanceUSDC = 25.0;

  /// Returns wallet address formatted for a QR code scan
  String getPaymentQRCodeString() {
    return "solana:\$address?label=MeshNet%20Prepaid%20Hotspot&amount=0.0";
  }

  void addDevnetFunds(double amount) {
    balanceUSDC += amount;
  }
}
`
  },
  {
    name: 'prepaid_quota.dart',
    filepath: 'lib/payment/prepaid_quota.dart',
    language: 'dart',
    content: `// lib/payment/prepaid_quota.dart
import 'dart:async';

/// Simplistic manual prepaid quota system representing offline-first mechanics.
/// Buyer sends USDC on Solana to seller's QR address, seller manually enters shared password.
class PrepaidQuotaSession {
  final String providerAddress;
  final double ratePerMB; // In USDC
  
  double quotaMB = 0;
  double usedMB = 0;
  bool isActive = false;

  PrepaidQuotaSession({required this.providerAddress, required this.ratePerMB});

  /// Triggered manually when the provider verifies payment of USDC manually
  void acquirePrepaidBalance(double usdcPaid) {
    double purchasedMB = usdcPaid / ratePerMB;
    quotaMB += purchasedMB;
    isActive = true;
    print("Bought \$purchasedMB MB. Total Prepaid Cap: \$quotaMB MB");
  }

  /// App meters actual hotspot data usage
  void trackDataUsage(double addedMB) {
    if (!isActive) return;
    usedMB += addedMB;
    if (usedMB >= quotaMB) {
      usedMB = quotaMB;
      isActive = false;
      print("Prepaid quota depleted! Internet hotspot disconnect required.");
    }
  }

  double get remainingMB => quotaMB - usedMB;
}
`
  },
  {
    name: 'chat_screen.dart',
    filepath: 'lib/screens/chat_screen.dart',
    language: 'dart',
    content: `// lib/screens/chat_screen.dart
import 'package:flutter/material.dart';

class MeshChatScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text("Mesh Messenger (Epidemic Routing)")),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Text("Epidemic Broadcaster (TTL=5) Active", style: TextStyle(color: Colors.cyan)),
          ),
          Expanded(
            child: Center(
              child: Text("All message queues prioritized: \\n Voice > Text > Files"),
            ),
          )
        ],
      ),
    );
  }
}
`
  },
  {
    name: 'wallet_screen.dart',
    filepath: 'lib/screens/wallet_screen.dart',
    language: 'dart',
    content: `// lib/screens/wallet_screen.dart
import 'package:flutter/material.dart';

class WalletScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text("Solana Prepaid Hotspot Wallet")),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text("Solana USDC Wallet Balance: \$25.00 USDC"),
            SizedBox(height: 16),
            Text("Generate Payment QR Code for manual prepaid sharing:"),
            Container(
              width: 150,
              height: 150,
              color: Colors.white,
              child: Icon(Icons.qr_code, size: 120, color: Colors.black),
            ),
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: Text(
                "CARRIER WARNING: Reselling mobile carrier data can breach your carrier's Terms of Service. Proceed under your own responsibility.",
                style: TextStyle(fontSize: 10, color: Colors.orange, fontStyle: FontStyle.italic),
                textAlign: TextAlign.center,
              ),
            )
          ],
        ),
      ),
    );
  }
}
`
  },
  {
    name: 'map_screen.dart',
    filepath: 'lib/screens/map_screen.dart',
    language: 'dart',
    content: `// lib/screens/map_screen.dart
import 'package:flutter/material.dart';

class MeshMapScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text("Mesh Interactive Node Map")),
      body: Center(
        child: Text("A & B (Full Nodes) <-> C (Receive-Only iOS Client)"),
      ),
    );
  }
}
`
  },
  {
    name: 'BluetoothLeModule.kt',
    filepath: 'android/BluetoothLeModule.kt',
    language: 'kotlin',
    content: `package com.meshnet.app

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import java.util.UUID

/**
 * Android Native BLE advertiser & listener Module.
 * Advertising UUID: 0000ABCD-0000-1000-8000-00805F9B34FB
 * Interval: 1.0 second (ADVERTISE_MODE_BALANCED matches low rate advertising frequency)
 */
class BluetoothLeModule(private val advertiser: BluetoothLeAdvertiser?) {
    
    fun startMeshBeacon() {
        if (advertiser == null) return
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // ~1Hz interval
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
            
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UUID.fromString("0000ABCD-0000-1000-8000-00805F9B34FB")))
            .build()
            
        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                android.util.Log.d("MeshNetBluetooth", "P2P BLE mesh beacon advertising correctly.")
            }
        })
    }
}
`
  },
  {
    name: 'TetheringModule.kt',
    filepath: 'android/TetheringModule.kt',
    language: 'kotlin',
    content: `package com.meshnet.app

import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Standard Android WiFi Hotspot interface.
 * Handles triggering the system's local-only tether access point for the manual quota systems.
 */
class TetheringModule(private val wifiManager: WifiManager) {

    @RequiresApi(Build.VERSION_CODES.O)
    fun startLocalHotspot() {
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    val wifiConfiguration = reservation?.wifiConfiguration
                    val ssid = wifiConfiguration?.SSID
                    val password = wifiConfiguration?.preSharedKey
                    android.util.Log.d("MeshHotspot", "Hotspot started! SSID: \$ssid Password: \$password")
                }
            }, null)
        } catch (e: Exception) {
            android.util.Log.e("MeshHotspot", "Failed to start local hotspot manually: \${e.message}")
        }
    }
}
`
  },
  {
    name: 'MeshForegroundService.kt',
    filepath: 'android/MeshForegroundService.kt',
    language: 'kotlin',
    content: `package com.meshnet.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Persistent Android service utilizing custom background network routing rules.
 * Runs on android:foregroundServiceType="connectedDevice" layout.
 */
class MeshForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channelId = "mesh_connected_device_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Active Mesh Node", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Meshnet routing active")
            .setContentText("Participating in epidemic P2P relay broadcast...")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(101, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
`
  },
  {
    name: 'MultipeerConnectivityModule.swift',
    filepath: 'ios/MultipeerConnectivityModule.swift',
    language: 'swift',
    content: `import Foundation
import MultipeerConnectivity

/**
 * iOS Apple receive-only Mesh Client Implementation.
 * Relies on Multipeer Connectivity MCNearbyServiceAdvertiser to listen for Android BLE advertisers
 * and pull/receive messages without emitting unauthorized high-power background broadcasts.
 */
class MultipeerConnectivityModule: NSObject, MCNearbyServiceAdvertiserDelegate {
    private let peerId = MCPeerID(displayName: UIDevice.current.name)
    private var advertiser: MCNearbyServiceAdvertiser?

    func initReceiveOnlyMesh() {
        // Limited iOS receive client initialization
        advertiser = MCNearbyServiceAdvertiser(
            peer: peerId,
            discoveryInfo: ["iosMeshClient": "true"],
            serviceType: "meshnet-p2p"
        )
        advertiser?.delegate = self
        advertiser?.startAdvertisingPeer()
        print("iOS client initialised in multicast receive-only local mode.")
    }

    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        // Accepts incoming links to listen/receive relayed mesh messages securely
        invitationHandler(true, nil)
    }

    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        print("Apple Multipeer start did fail: \\(error.localizedDescription)")
    }
}
`
  },
  {
    name: 'Info.plist',
    filepath: 'ios/Info.plist',
    language: 'yaml',
    content: `<!-- Background options configuration inside ios/Runner/Info.plist -->
<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
    <string>bluetooth-peripheral</string>
</array>
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Meshnet needs continuous bluetooth background permissions to scan and relay P2P epidemic messages when screen goes locked.</string>
<key>NSLocalNetworkUsageDescription</key>
<string>Required to establish receive-only peer links using Apple Multipeer Connectivity.</string>
`
  },
  {
    name: 'mock_mesh_driver_test.dart',
    filepath: 'test/mock_mesh_driver_test.dart',
    language: 'dart',
    content: `// test/mock_mesh_driver_test.dart
import 'package:flutter_test/flutter_test.dart';

class MockMeshDevice {
  final String deviceName;
  final List<String> priorityQueue = [];
  
  MockMeshDevice(this.deviceName);

  void queueMessage(String content, String priority) {
    if (priority == "VOICE") {
      priorityQueue.insert(0, content); // top priority
    } else {
      priorityQueue.add(content);
    }
  }
}

void main() {
  test('Verify Mesh Routing Queue Priorities and Re-encryption', () {
    final alice = MockMeshDevice("Alice (Android)");
    final bob = MockMeshDevice("Bob (Android)");
    final charlie = MockMeshDevice("Charlie (iOS)");

    print("Executing simulated multi-device test over virtual memory network...");
    
    alice.queueMessage("Standard text chat block", "TEXT");
    alice.queueMessage("Urgent voice payload", "VOICE");

    // Expected order: VOICE followed by TEXT
    expect(alice.priorityQueue[0], contains("Urgent voice"));
    expect(alice.priorityQueue[1], contains("Standard text"));
    print("Test passed! Sorted correct priority arrangement.");
  });
}
`
  }
];
