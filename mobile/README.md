# MeshNet native Android prototype

The native Android target uses real Android BLE advertising, scanning, and GATT. The React/Vite dashboard remains a separate browser simulation and is not connected to phone radios.

## Implemented

- BLE service UUID advertising, filtered scanning, GATT server/client connections, and reconnect discovery.
- GATT frame fragmentation sized to the negotiated MTU, ordered per-peer writes, bounded write queues, bounded reassembly buffers, and reassembly expiry. Frames are capped at 640 bytes (64 chunks at the 23-byte default ATT MTU), with compact 128-bit node IDs to keep the maximum relay route within that budget.
- Protocol v3 AES-256-GCM encrypted text and receipt frames with authenticated frame type, origin, frame ID, timestamp, receipt target, TTL, and route history; relays re-encrypt after updating route metadata. All participating phones must run the same protocol version.
- Group-level application receipts: a sender retires its queued message when any member holding the shared group key receives it; receipt frames are themselves queued and relayed.
- Persistent SQLite outbox (500-frame cap, 24-hour retention) and persistent receiver replay-ID cache (bounded to 4,096 IDs).
- Encrypted small-file transfer over the same group-routed message channel: 16 KiB maximum, 48-byte chunks, out-of-order chunk buffering, bounded concurrent transfers, and SHA-256 integrity verification before files are saved in app-private storage. Use **Send small file** in the Android app.
- User-started Android connected-device foreground service with an ongoing notification.
- Group passphrase encrypted at rest with an Android Keystore AES-GCM key; it can be reused after process recreation and explicitly forgotten from the app.
- Runtime Bluetooth and notification permission requests for supported Android versions.

## Build locally (no CI)

Requirements: JDK 17, Android SDK Platform 35, and Gradle 8.9 or a compatible Gradle installation. From this directory:

    gradle assembleDebug

Instrumented protocol and SQLite tests are included under `app/src/androidTest`. Run them locally on a connected Android device or emulator with:

    gradle connectedDebugAndroidTest

These commands are local developer instructions; no GitHub Actions workflow is triggered by feature-branch pushes.

Install the debug APK on at least two BLE-capable Android phones. On each phone, enter the same high-entropy passphrase (at least 24 characters), grant Bluetooth and notification permissions, and start the mesh. A blank passphrase field reuses the locally saved credential. Use “Forget saved group credentials” to delete it from that device.

For a multi-hop check, use three phones with each device within BLE range of at least one neighbor, then move the origin and destination out of direct range while keeping the relay in range. Verify the receiver log shows the message and hop count.

## Security and delivery boundaries

- “Queued” means accepted into this device's encrypted-frame outbox. A successful GATT write only means the BLE stack accepted a chunk; it does **not** prove remote application delivery. The current receipt is a group-level acknowledgement: any member with the shared group key can generate it, and it does not prove delivery to a named recipient. Messages remain queued until a valid receipt arrives, the 24-hour expiry is reached, or the 500-entry capacity forces eviction of the oldest frame; capacity eviction can lose an unacknowledged message. Receipt frames have no ACK-of-ACK protocol: after at least one connected peer accepts a receipt into its BLE write queue, the local receipt is retired to prevent the outbox filling with unacknowledgeable receipts. If that BLE write later fails, a duplicate message can cause the receiver to regenerate the receipt; this is best-effort relay, not a delivery guarantee.
- Upgrading from protocol v1 or v2 clears the old outbox and replay cache. Protocol v3 authenticates TTL and route metadata; relays re-encrypt the already-authenticated plaintext for each updated route. Update all participating phones together. This still does not authenticate a device identity: every group-key holder can forge a group origin and receipt. The outbox and received-ID cache are local SQLite data. Group credentials are encrypted using an Android Keystore key; losing/resetting that key makes the saved credential unreadable and requires entering the group passphrase again.
- The shared passphrase is a temporary group-key bootstrap, not Signal Protocol. There is no per-device identity authentication, forward secrecy, or protection from a malicious member of the shared group. Group key changes clear the local queue; there is no secure multi-device key rotation protocol.
- File transfer is currently limited to 16 KiB and is not yet resumable across process restarts or interrupted sends. Partial transfers remain in private app storage until manually cleaned by a future maintenance task. It is not a large-file transport or voice streaming.
- Wi-Fi Aware, Wi-Fi Direct, hotspot metering, adaptive routing/congestion control, voice recording/streaming, iOS, and the Android wallet client are not implemented. A separate Solana escrow program source is being added but is not deployed or audited.
- Android may stop a foreground service under exceptional resource or policy conditions. BLE peripheral advertising and concurrent GATT connections vary by chipset and vendor.
- The added Android instrumentation tests have **not** been executed. No Android build or physical-device test has been performed for these commits. Do not use this prototype for sensitive or financial traffic before independent security review and device testing.

## Remaining implementation plan

See [SECURITY_AND_ESCROW.md](SECURITY_AND_ESCROW.md) for the Apache-2.0 cryptography candidate, escrow design decision, integration gates, and explicit items that cannot safely be enabled without a deployed and reviewed program.
