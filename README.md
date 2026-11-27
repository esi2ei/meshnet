# MeshNet

MeshNet is an offline-first peer-to-peer messaging and connectivity project with two distinct parts:

- **Web dashboard (src/)**: a React/Vite prototype. Its simulated nodes and topology are not connected to phone radios.
- **Native Android (mobile/)**: an early BLE/GATT prototype with encrypted text frames, TTL relay, MTU-aware frame fragmentation, a foreground service, a persistent SQLite outbox, persistent replay protection, and Android Keystore-protected group credentials.

## Web dashboard

Requirements: Node.js 20+.

    npm install
    npm run dev

The dashboard does not require a Gemini API key for its core UI. Do not put private API keys in client-side environment variables.

## Native Android

Requirements: JDK 17 and Android SDK Platform 35. See mobile/README.md.

Build locally without CI:

    cd mobile
    gradle assembleDebug

## Development status

This is still a prototype, not a production release. BLE writes and local queue acceptance are not end-to-end delivery acknowledgements. The shared group key is not Signal Protocol and has no per-device identity or forward secrecy. Large-file/voice transfer, Wi-Fi transports, iOS, and payment/escrow are not implemented. No Android build or physical-device validation has been performed for the latest changes.

The browser dashboard is a simulation and is not evidence of live radio connectivity.

Feature-branch pushes do not run GitHub Actions. Workflows remain available for main pushes, pull requests targeting main, and manual dispatch.
