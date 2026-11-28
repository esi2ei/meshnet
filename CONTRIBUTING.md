# Contributing to MeshNet

## Before opening a change

MeshNet is an offline-first prototype. Keep runtime features self-contained and make any internet use explicit, user initiated, and disabled by default. Never add private credentials, wallet secrets, group passphrases, keystores, signing keys, or deployment keypairs to source control.

## Local checks

From the repository root:

    npm install
    npm run lint
    npm run build

For Android packaging, install JDK 17, Android SDK Platform 37 and Gradle 9.3.1, then run:

    cd mobile
    gradle :app:assembleDebug
    gradle :app:connectedDebugAndroidTest

The Android Gradle `preBuild` task builds the React dashboard and packages it below `web/` in APK assets. The installed app must work without a hosted dashboard, external fonts, remote images, or cloud APIs.

## Change boundaries

- Keep the simulated topology clearly labeled as a simulator. Do not present simulated nodes or events as live radio observations.
- Keep the WebView bridge limited to the bundled local dashboard served by AndroidX WebViewAssetLoader; the virtual appassets origin is allowlisted and external navigation is blocked. Do not enable universal file access, mixed content, external navigation, or broad JavaScript interfaces.
- Keep online integrations opt-in. The current wallet path is Devnet authorization only; it is not a payment or escrow integration.
- Add or update tests when changing framing, encryption, persistence, file transfer, native bridge behavior, or build packaging.
- Do not claim physical Bluetooth/Wi-Fi interoperability based only on emulator tests.

## Pull requests

Explain the behavior change, the offline/online boundary, tests run, and any remaining hardware validation. Include screenshots only when they help review a UI change and remove device identifiers or private data.
