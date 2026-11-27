# MeshNet native Android prototype

This is a native Android BLE/GATT prototype. The React/Vite dashboard is a separate browser simulation and is not connected to phone radios.

## Implemented in this branch

- BLE service UUID advertising, filtered scanning, GATT server and GATT client.
- AES-256-GCM encrypted text frames with authenticated metadata and TTL relay.
- Android runtime BLE permissions for Android 12+ and legacy Android versions.
- A user-started connected-device foreground service, with an ongoing notification while the mesh is active.
- A bounded SQLite outbox (up to 500 frames, 24-hour retention) that survives app process restarts and replays queued frames when a peer GATT channel becomes ready.
- Duplicate suppression in the running process and bounded storage to limit unbounded disk growth.

## Build locally (no CI required)

Requirements: JDK 17 and Android SDK Platform 35. From this directory run:

    gradle assembleDebug

Install the debug APK on at least two Android phones that support BLE peripheral advertising. Open MeshNet on both phones, enter the same high-entropy passphrase (at least 24 characters) on each, grant Bluetooth permissions, and tap Start BLE mesh.

For a multi-hop check, use three phones with each device within BLE range of at least one neighbor, then move the origin and destination out of direct range while keeping the relay in range. Verify the receiver log shows the message and hop count.

## Delivery semantics and important limitations

- The outbox records locally accepted frames and retries them after a peer channel becomes ready. It does **not** prove remote delivery: there is no end-to-end acknowledgement protocol. Frames remain until their 24-hour expiry, so duplicates may be replayed after receiver process restarts.
- The current duplicate cache is in-memory. Persistent receiver-side deduplication is not implemented.
- Android foreground-service execution is user initiated and requires the ongoing notification. Android versions and vendors can still stop services under exceptional resource or policy conditions.
- The shared passphrase is a temporary group-key bootstrap. It is not Signal Protocol, per-device identity authentication, or forward secrecy. Use a high-entropy random passphrase and exchange it out of band.
- The BLE transport currently sends a single small frame per GATT write. Large-file transfer, fragmentation/resume, voice, congestion control, and adaptive routing are not implemented.
- Android Wi-Fi Aware / Wi-Fi Direct, hotspot metering, iOS, Solana/USDC, and escrow are not implemented.
- BLE peripheral advertising and concurrent GATT connections vary by chipset and Android vendor. Physical-device testing is required.
- Do not use for sensitive or financial traffic until the protocol has undergone independent security review.

Feature-branch pushes do not trigger GitHub Actions. No CI run is requested or required for this branch update.
