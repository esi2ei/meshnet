# MeshNet

MeshNet is an offline-first peer-to-peer messaging and connectivity project with two distinct parts:

- **Web dashboard (src/)**: a React/Vite UI prototype. Its simulated nodes and topology are not connected to phone radios.
- **Native Android (mobile/)**: an early BLE/GATT implementation with encrypted text frames, TTL relay, a user-started connected-device foreground service, and a bounded persistent SQLite outbox.

## Web dashboard

Requirements: Node.js 20+.

    npm install
    npm run dev

The dashboard does not require a Gemini API key for its core UI. Do not put private API keys in client-side environment variables.

## Native Android

Requirements: JDK 17 and Android SDK Platform 35. See mobile/README.md.

Build locally (without CI):

    cd mobile
    gradle assembleDebug

## Development status and limitations

This remains a prototype, not a production release. The persistent outbox has no end-to-end delivery acknowledgements and retains frames for bounded retry/expiry. The shared passphrase is not Signal Protocol and provides neither per-device identity nor forward secrecy. Large file/voice transfer, fragmentation/resume, Wi-Fi transports, iOS, and real payment/escrow are not implemented. No physical-device validation or local Android build has been performed as part of this change.

The browser dashboard is a simulation and is not evidence of live radio connectivity.

Feature-branch pushes do not run GitHub Actions. Workflows remain available for main pushes, pull requests targeting main, and manual dispatch.
