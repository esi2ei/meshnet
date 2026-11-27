# MeshNet native Android prototype

This is the first executable native Android target. It is separate from the existing React/Vite dashboard, which remains a browser-side concept/simulation UI.

## Implemented in this branch

- BLE service UUID advertising and filtered scanning.
- GATT server + GATT client connections to discovered MeshNet peers.
- Encrypted text frames using AES-256-GCM with authenticated metadata.
- TTL-based relay and duplicate suppression while the process is running.
- Runtime Bluetooth permission requests for Android 12+ and legacy Android versions.

## Build locally (no CI required)

Requirements: JDK 17 and Android SDK Platform 35. From this directory run:

    gradle assembleDebug

Install the debug APK on at least two Android phones that support BLE peripheral advertising. Open MeshNet on both phones, enter the exact same high-entropy passphrase (at least 24 characters) on each, grant Bluetooth permissions, and tap Start BLE mesh. Send a short text message from one device.

For a multi-hop check, use three phones with each device within BLE range of at least one neighbor, then move the origin and destination out of direct range while keeping the relay in range. Verify the receiver log shows the message and hop count.

## Important limitations — not production-ready

- The shared passphrase is a temporary group-key bootstrap. It must be exchanged out-of-band and is not Signal Protocol, per-device identity authentication, or forward secrecy.
- The relay queue and duplicate cache are in-memory; they do not survive process death yet.
- This first transport sends one small frame per GATT write. Large files, voice, fragmentation, resume, congestion control, and adaptive routing are not implemented.
- BLE background operation is not implemented; the prototype stops when the Activity is destroyed. No foreground service is declared.
- Android Wi-Fi Aware / Wi-Fi Direct, hotspot metering, Solana/USDC, and iOS are not implemented by this Android target.
- BLE peripheral advertising and concurrent GATT connections vary by chipset and Android vendor. Physical-device testing is required.
- Do not use for sensitive or financial traffic until the protocol has undergone independent security review.

Feature-branch pushes do not trigger GitHub Actions. CI remains configured for main pushes, pull requests targeting main, and manual dispatch.
