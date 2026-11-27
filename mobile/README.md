# MeshNet native Android prototype

The native Android target uses real Android BLE advertising, scanning, and GATT. The React/Vite dashboard remains a separate browser simulation and is not connected to phone radios.

## Implemented

- BLE service UUID advertising, filtered scanning, GATT server/client connections, and reconnect discovery.
- GATT frame fragmentation sized to the negotiated MTU, ordered per-peer writes, bounded write queues, bounded reassembly buffers, and reassembly expiry.
- AES-256-GCM encrypted text and receipt frames with authenticated frame type, origin, frame ID, timestamp, and receipt target; TTL-based multi-hop relay.
- Group-level application receipts: a sender retires its queued message when any authenticated member of the shared group receives it; receipt frames are themselves queued and relayed.
- Persistent SQLite outbox (500-frame cap, 24-hour retention) and persistent receiver replay-ID cache (bounded to 4,096 IDs).
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

## Delivery and security boundaries

- “Queued” means accepted into this device's encrypted-frame outbox. A successful GATT write only means the BLE stack accepted a chunk; it does **not** prove remote application delivery. The current receipt is a group-level acknowledgement: any member with the shared group key can generate it, and it does not prove delivery to a named recipient. Messages remain queued until a valid receipt arrives or the 24-hour expiry is reached. Receipts are queued and replayed; persistent receiver deduplication reduces duplicate display.
- The outbox and received-ID cache are local SQLite data. Group credentials are encrypted using an Android Keystore key; losing/resetting that key makes the saved credential unreadable and requires entering the group passphrase again.
- The shared passphrase is a temporary group-key bootstrap, not Signal Protocol. There is no per-device identity authentication, forward secrecy, or protection from a malicious member of the shared group. Group key changes clear the local queue; there is no secure multi-device key rotation protocol.
- GATT chunking here transports one bounded MeshFrame. It is **not** large-file transfer, resumable file transfer, or voice streaming.
- File/voice transfer, Wi-Fi Aware, Wi-Fi Direct, hotspot metering, adaptive routing/congestion control, iOS, Solana/USDC, and escrow are not implemented.
- Android may stop a foreground service under exceptional resource or policy conditions. BLE peripheral advertising and concurrent GATT connections vary by chipset and vendor.
- No Android build or physical-device test has been performed for these commits. Do not use this prototype for sensitive or financial traffic before independent security review and device testing.

Feature-branch pushes do not trigger GitHub Actions. No CI run is requested or required for this branch update.
