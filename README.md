# MeshNet

MeshNet is an offline-first peer-to-peer messaging and connectivity prototype. The Android app packages the React/Vite dashboard inside the APK and connects its local UI to native Android radio and file APIs.

## App architecture

- **One dashboard UI:** `src/` contains the React/Vite application. Android builds it and bundles the resulting static files into `mobile/app/src/main/assets` at build time (generated under Gradle's build directory); the APK does not need a hosted website.
- **Native Android runtime:** `mobile/` provides BLE/GATT, optional Wi-Fi Aware, user-controlled Wi-Fi Direct, AES-GCM group-encrypted messages, bounded resumable file transfer, voice notes, opt-in live group voice, TTL relay, persistent SQLite queues, and Android Keystore-protected local credentials. Each BLE-capable node opens a GATT server while also scanning and connecting as a GATT client, so the same phone can send and receive. BLE advertiser and scanner startup are tracked independently; if BLE cannot provide both roles, the service attempts Wi-Fi Direct discovery while Wi-Fi Aware is also attempted when supported. Each device can therefore originate and receive over whichever mutually supported local radio is available.
- **Live topology, destination-aware routing and event log:** the dashboard graph is populated from ready MeshNet GATT channels plus established Wi-Fi Direct and Wi-Fi Aware data-channel connections; discovery results are never counted as connected peers. A shared native peer registry tracks connection and disconnection by transport and address. The dashboard separates established radio links from signed-origin observations and reverse next-hop candidates; mutable frame hop metadata is not treated as an authenticated path. Directed text frames can use learned reverse next-hop routes and up to three alternatives; unknown destinations are flooded across other connected peers for route discovery. These observations are time-bounded and do not prove a direct radio link or a complete multi-hop path. The event log displays native service/transport events, not fabricated blockchain activity. A browser preview cannot inspect phone radios; the packaged APK connects through a local-only bridge.
- **Bundled source viewer:** Vite imports the checked-out frontend, Android, instrumentation-test, Rust/Anchor, Gradle, workflow, script, configuration and documentation text files at build time. The viewer is read-only and packaged with the APK; it does not fetch source code from GitHub or another remote host.

## Repository organization

## Feature-preserving development

MeshNet is being refactored without reducing its feature scope. Existing dashboard, radio, messaging, voice, library, VPN and wallet paths must remain traceable while their internals are improved; prototype and planned capabilities remain clearly labeled rather than advertised as complete. See [the feature-preserving refactor contract](docs/FEATURE_PRESERVATION_PLAN.md) and [the development roadmap](docs/DEVELOPMENT_ROADMAP.md) for the capability inventory, staged migration rules and acceptance gates.

The native service owns transport lifecycle and live peer state; the dashboard is a presentation/control layer. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module map, peer lifecycle, source-browser scope and engineering rules. See [docs/OFFLINE_MESH_NETWORK.md](docs/OFFLINE_MESH_NETWORK.md) for the actual offline-network boundary and the engineering gates needed for a usable local intranet. Legacy virtual-device simulation code is isolated under `test/fixtures/` and is not used by the Android runtime or treated as a real transport test.

## Offline-first behavior

The installed APK loads bundled assets through AndroidX WebViewAssetLoader at the virtual `https://appassets.androidplatform.net/assets/` origin; requests are served from the APK and do not reach the network. The Vite build uses relative asset URLs, remote fonts and avatar downloads have been removed, external WebView navigation is blocked, and the dashboard's Content Security Policy denies network connections. Bluetooth, local Wi-Fi peer transport, local message queues, file transfer, voice features, and the explicitly published content-addressed offline library do not require a cloud server. MeshNet now also has an opt-in split-tunnel IPv4 overlay (100.64.0.0/10), mesh DNS names, and a local HTTP library portal. **Offline-first does not mean public Internet access without an uplink**: this overlay connects participating MeshNet devices and services; it does not provide NAT, an Internet gateway, IPv6, or public web access without an upstream connection. See the offline mesh scope document.

Optional online integrations are disabled by default and can be enabled in the device-runtime settings. The opt-in online flow supports Solana Mobile Wallet Adapter authorization and user-approved native SOL transfers on Devnet through the connected wallet. It requires internet and wallet approval, is separate from the offline core, and does not send mainnet funds or operate the escrow program. Enabling this setting does not turn on cloud messaging, analytics, telemetry or automatic uploads.

## Web development

Requirements: Node.js 22.12+. Pull requests targeting `main` run the web typecheck and production build on Node 22, then build and verify the Android debug APK and execute the focused Android API 36 instrumentation suite. Manual workflow dispatch can run the complete Android instrumentation suite and retain a downloadable debug APK and test reports.

    npm install
    npm run dev

The browser dashboard can preview the interface, but native Bluetooth and Wi-Fi controls and live peer telemetry are available only inside the packaged Android APK.

## Native Android build

Requirements: Node.js 22.12+, JDK 17, Android SDK Platform 36, and Gradle 8.13. The Gradle `preBuild` task runs `npm run build` and packages the generated dashboard assets automatically.

    npm install
    cd mobile
    gradle assembleDebug

The debug APK is written to `mobile/app/build/outputs/apk/debug/app-debug.apk`. See [mobile/README.md](mobile/README.md) for Windows helper instructions, instrumentation tests, release signing and known limitations.

## Public-repository and security notes

- Never commit wallet secrets, group passphrases, API keys, keystores, signing passwords, or deployment keypairs. Release signing credentials must be stored outside the checkout and supplied through private CI secrets or a local environment.
- Debug APKs are test artifacts, not production releases. Pull-request CI builds and signature-verifies the debug APK and runs focused emulator instrumentation; the complete Android suite and downloadable artifacts are available through explicit manual dispatch (and APK publication is additionally restricted for the repository-owned trial-APK PR). Emulator checks cannot validate physical Bluetooth/Wi-Fi interoperability.
- The prototype is not yet a production secure messenger: protocol v6 binds each node ID to a per-install P-256 signing key in Android Keystore and verifies device-origin signatures and signed plaintext digests. Group AES-GCM still uses a shared key, not Signal Protocol; there is no per-device session encryption, forward secrecy, ratcheting, owner-controlled enrollment/revocation or secure group-key rotation. Directed receipts are accepted only when the authenticated frame origin matches the stored destination. Broadcast ACKs fail closed until protocol-v7 immutable-snapshot and per-member receipt integration is complete. The router learns reverse next-hop candidates from a verified origin and its actual ingress peer; mutable shared-key hop metadata is not treated as path proof. Unknown destinations use bounded flooding, and route freshness does not prove that a cached next hop is currently reachable.
- The offline wallet prototype now has a durable sender journal and a signed recipient-acknowledgement path: the recipient verifies and persists the voucher before signing an acknowledgement, and the sender verifies that acknowledgement against the queued voucher. The signed voucher is retained in the sender's private journal and can be retried unchanged after interruption or a lost acknowledgement. It still uses provisional `MESH-LOCAL-UNIT` receipts only; there is no issuance, spendable balance, authoritative ledger, global double-spend protection or final settlement, so it is not a real-money or cryptocurrency wallet. The Solana escrow program is not built, tested, deployed, audited or integrated with Android transactions. Do not use this prototype to accept or transfer real funds.
- Review [SECURITY.md](SECURITY.md) before reporting security issues, and see [mobile/SECURITY_AND_ESCROW.md](mobile/SECURITY_AND_ESCROW.md) for technical boundaries.

GitHub Actions runs Node 22.12+ web typecheck/build plus focused Android API 36 instrumentation and debug-APK build/signature verification on pull requests targeting `main`; it does not run on ordinary pushes. The complete Android suite is optional through manual workflow dispatch. Successful routine PR runs retain the tested web bundle for one day so Android packaging can reuse the exact build; they do not retain APK or test-report artifacts. Failure diagnostics and manual-run test reports are retained for three days, and explicitly requested debug APKs for seven days. Physical-device radio interoperability still requires separate testing.
