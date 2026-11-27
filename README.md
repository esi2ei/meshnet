# MeshNet

MeshNet is an offline-first peer-to-peer messaging and connectivity project with two distinct parts:

- **Web dashboard (src/)**: a React/Vite prototype. Its simulated nodes and topology are not connected to phone radios.
- **Native Android (mobile/)**: a prototype with BLE/GATT and user-controlled Wi-Fi Direct transports, encrypted text and bounded file/voice-note frames, TTL relay, adaptive BLE queue backoff, a foreground service, a persistent SQLite outbox with group-level receipts, replay protection, and Android Keystore-protected group credentials.

## Web dashboard

Requirements: Node.js 20+.

    npm install
    npm run dev

The dashboard does not require a Gemini API key for its core UI. Do not put private API keys in client-side environment variables.

## Native Android

Requirements: JDK 17 and Android SDK Platform 35. See [mobile/README.md](mobile/README.md).

Build locally without CI:

    cd mobile
    gradle assembleDebug

## Development status

This is a prototype, not a production release. BLE writes are not delivery acknowledgements. The current group receipt only confirms that at least one member sharing the group key received a message; it does not identify or authenticate a specific recipient. Receipt frames are best-effort and retired after a connected peer accepts them into its BLE write queue; messages remain queued until an authenticated group receipt arrives. The shared group key is not Signal Protocol and has no per-device identity or forward secrecy.

Resumable large-file transfer, Wi-Fi Aware, live voice streaming, iOS, and the Android wallet client are not implemented. An Apache-2.0 cryptography candidate (vodozemac) has been selected but is not integrated. A first-pass non-custodial Solana escrow program source is present under solana/; it is not built, tested, deployed, audited, or connected to a wallet. No Android build, instrumentation run, or physical-device validation has been performed for the latest changes.

See [mobile/SECURITY_AND_ESCROW.md](mobile/SECURITY_AND_ESCROW.md) for licensing rationale, the escrow design, activation gates, and remaining work.

The browser dashboard is a simulation and is not evidence of live radio connectivity.

Feature-branch pushes do not run GitHub Actions. Do not run CI for this branch unless explicitly requested.
