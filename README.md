# MeshNet

MeshNet is an offline-first peer-to-peer messaging and connectivity prototype. The Android app packages the React/Vite dashboard inside the APK and connects its local UI to native Android radio and file APIs.

## App architecture

- **One dashboard UI:** `src/` contains the React/Vite application. Android builds it and bundles the resulting static files into `mobile/app/src/main/assets` at build time (generated under Gradle's build directory); the APK does not need a hosted website.
- **Native Android runtime:** `mobile/` provides BLE/GATT, optional Wi-Fi Aware, user-controlled Wi-Fi Direct, AES-GCM group-encrypted messages, bounded resumable file transfer, voice notes, opt-in live group voice, TTL relay, persistent SQLite queues, and Android Keystore-protected local credentials. Each BLE-capable node opens a GATT server while also scanning and connecting as a GATT client, so the same phone can send and receive. BLE advertiser and scanner startup are tracked independently; if BLE cannot provide both roles, the service attempts Wi-Fi Direct discovery while Wi-Fi Aware is also attempted when supported. Each device can therefore originate and receive over whichever mutually supported local radio is available.
- **Live topology, destination-aware routing and event log:** the dashboard graph is populated from ready MeshNet GATT channels plus established Wi-Fi Direct and Wi-Fi Aware data-channel connections; discovery results are never counted as connected peers. A shared native peer registry tracks connection and disconnection by transport and address. The dashboard separates direct radio links from authenticated observed frame paths. Directed text frames can use learned reverse next-hop routes and up to three alternatives; unknown destinations are flooded across other connected peers for route discovery. Route observations are time-bounded, are not proof of current links, and do not bind a route to an individual cryptographic identity. The event log displays native service/transport events, not fabricated blockchain activity. A browser preview cannot inspect phone radios; the packaged APK connects through a local-only bridge.
- **Bundled source viewer:** Vite imports the checked-out frontend, Android, instrumentation-test, Rust/Anchor, Gradle, workflow, script, configuration and documentation text files at build time. The viewer is read-only and packaged with the APK; it does not fetch source code from GitHub or another remote host.

## Repository organization

The native service owns transport lifecycle and live peer state; the dashboard is a presentation/control layer. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module map, peer lifecycle, source-browser scope and engineering rules. Legacy virtual-device simulation code is isolated under `test/fixtures/` and is not used by the Android runtime or treated as a real transport test.

## Offline-first behavior

The installed APK loads bundled assets through AndroidX WebViewAssetLoader at the virtual `https://appassets.androidplatform.net/assets/` origin; requests are served from the APK and do not reach the network. The Vite build uses relative asset URLs, remote fonts and avatar downloads have been removed, external WebView navigation is blocked, and the dashboard's Content Security Policy denies network connections. Bluetooth, local Wi-Fi peer transport, local message queues, file transfer and voice features do not require a cloud server.

Optional online integrations are disabled by default and can be enabled in the device-runtime settings. The opt-in online flow supports Solana Mobile Wallet Adapter authorization and user-approved native SOL transfers on Devnet through the connected wallet. It requires internet and wallet approval, is separate from the offline core, and does not send mainnet funds or operate the escrow program. Enabling this setting does not turn on cloud messaging, analytics, telemetry or automatic uploads.

## Web development

Requirements: Node.js 22+. The GitHub Actions workflow is currently manual-only; no CI is run automatically while this branch is being completed.

    npm install
    npm run dev

The browser dashboard can preview the interface, but native Bluetooth and Wi-Fi controls and live peer telemetry are available only inside the packaged Android APK.

## Native Android build

Requirements: JDK 17, Android SDK Platform 36, Gradle 8.11.1, and Node.js/npm. The Gradle `preBuild` task runs `npm run build` and packages the generated dashboard assets automatically.

    npm install
    cd mobile
    gradle assembleDebug

The debug APK is written to `mobile/app/build/outputs/apk/debug/app-debug.apk`. See [mobile/README.md](mobile/README.md) for Windows helper instructions, instrumentation tests, release signing and known limitations.

## Public-repository and security notes

- Never commit wallet secrets, group passphrases, API keys, keystores, signing passwords, or deployment keypairs. Release signing credentials must be stored outside the checkout and supplied through private CI secrets or a local environment.
- Debug APKs are test artifacts, not production releases. The manual-only CI workflow can verify their signature and run emulator instrumentation tests when explicitly dispatched; an emulator cannot validate physical Bluetooth/Wi-Fi interoperability.
- The prototype is not yet a production secure messenger: group AES-GCM is not Signal Protocol, there is no authenticated per-device identity, forward secrecy or secure group-key rotation, and group receipts do not identify a named recipient. Destination routing is based on reverse paths observed in shared-key-authenticated frames; unknown destinations use flooding and route freshness does not establish current reachability.
- The offline wallet prototype now has a durable sender journal and a signed recipient-acknowledgement path: the recipient verifies and persists the voucher before signing an acknowledgement, and the sender verifies that acknowledgement against the queued voucher. The signed voucher is retained in the sender's private journal and can be retried unchanged after interruption or a lost acknowledgement. It still uses provisional `MESH-LOCAL-UNIT` receipts only; there is no issuance, spendable balance, authoritative ledger, global double-spend protection or final settlement, so it is not a real-money or cryptocurrency wallet. The Solana escrow program is not built, tested, deployed, audited or integrated with Android transactions. Do not use this prototype to accept or transfer real funds.
- Review [SECURITY.md](SECURITY.md) before reporting security issues, and see [mobile/SECURITY_AND_ESCROW.md](mobile/SECURITY_AND_ESCROW.md) for technical boundaries.

The GitHub Actions workflow is retained for a future owner-requested validation, but is manual-only (`workflow_dispatch`). It is intentionally not triggered by pushes or pull requests while this branch is being completed. No CI run is implied by source changes or commits.
