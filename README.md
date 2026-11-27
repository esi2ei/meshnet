# MeshNet

MeshNet is an offline-first peer-to-peer messaging and connectivity prototype with two distinct parts:

- **Web dashboard (`src/`)**: a React/Vite simulation. Its simulated nodes and topology are not connected to phone radios.
- **Native Android (`mobile/`)**: source for BLE/GATT, optional Wi-Fi Aware, and user-controlled Wi-Fi Direct transports; AES-GCM encrypted text, group receipts, bounded resumable file transfer, voice notes, opt-in live group voice, TTL relay, adaptive BLE queue backoff, a foreground service, persistent SQLite queues, and Android Keystore-protected group and wallet authorization data.

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

## Development and security status

This repository is a prototype, not a production release. Source implementation is ahead of validation: no Android build, instrumentation tests, Rust/Anchor build, deployment, independent audit, or physical-device interoperability validation has been performed for the current branch.

Important boundaries:

- BLE writes are not application-delivery acknowledgements. The current receipt is group-level and only indicates that some member holding the shared group key received the message; it does not identify or authenticate a specific recipient.
- The current shared AES-GCM group key is not Signal Protocol. There is no per-device identity authentication, forward secrecy, or secure multi-device key rotation. `vodozemac` is a selected Apache-2.0 candidate, not an integrated runtime dependency.
- File transfer is bounded to 10 MiB per file with resumable checkpoints; it is not an unlimited file-transfer service.
- Live group voice is an opt-in, best-effort source implementation using encrypted ephemeral frames. It has no individual recipient authorization or delivery guarantee and has not been validated for codec compatibility, echo, latency, battery use, or Android foreground-service behavior on physical devices.
- The Android wallet code supports user-authorized Solana Mobile Wallet Adapter authorization/deauthorization and encrypted persistence of the authorization token. It does not construct, sign, or submit transactions.
- A first-pass non-custodial Solana escrow program source exists under `solana/`. It is not built, tested, deployed, audited, or integrated with the Android wallet. Its program ID and initialization admin remain placeholders; it must not be used to accept real funds.

See [mobile/README.md](mobile/README.md) and [mobile/SECURITY_AND_ESCROW.md](mobile/SECURITY_AND_ESCROW.md) for implementation details, known limitations, and activation gates.

The browser dashboard is a simulation and is not evidence of live radio connectivity.

Feature-branch pushes do not run GitHub Actions. Do not run CI for this branch unless explicitly requested.
