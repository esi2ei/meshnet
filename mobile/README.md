# MeshNet Android app

The Android APK now presents the same React/Vite dashboard as the web project. Gradle builds the dashboard and packages it into the APK under `web/`; the app serves it through AndroidX WebViewAssetLoader from the virtual `https://appassets.androidplatform.net/assets/web/index.html` origin; requests are fulfilled from the APK without contacting a server. A restricted JavaScript bridge connects the bundled UI to native Android BLE, Wi-Fi, messaging, file and voice APIs. The draggable topology graphic remains a simulator and is explicitly separate from actual phone-radio status.

The packaged dashboard is offline-first: relative asset URLs, local system fonts, no remote avatar images, blocked external navigation, and a Content Security Policy that denies network requests from the WebView. Optional Devnet wallet authorization remains a separate opt-in integration and is not required by local messaging or the offline wallet. No cloud messaging, telemetry, or automatic uploads are provided.

**Offline wallet (new prototype):** each installation creates a device-bound P-256 signing key in Android Keystore and derives a local wallet address from its public key. A sender can create a signed `MESH-LOCAL-UNIT` voucher addressed to another wallet; the voucher is chunked into encrypted mesh messages and the recipient verifies the signature and rejects replayed voucher IDs. This is an offline transfer receipt/prototype only: there is no issuance, authoritative balance ledger, global double-spend prevention, fiat/crypto backing, blockchain settlement, or guaranteed finality. Do not use it for money or valuable assets. Offline devices cannot independently agree on a globally spendable balance.

## Implemented

- Bundled React/Vite dashboard hosted from APK assets, with a local-only WebView origin and a narrow `@JavascriptInterface` bridge. The bridge exposes only the user actions needed by the dashboard; non-local WebView navigation and content access are denied.
- Native device controls in the original dashboard: start/stop BLE mesh, encrypted text queueing, file selection and export, voice-note recording/playback, opt-in live voice, Wi-Fi Aware and Wi-Fi Direct actions, an offline signed local-unit wallet transfer prototype, and optional Devnet wallet authorization.
- Persisted online-integration switch, defaulting to off. BLE and local peer-to-peer features work without enabling it. WebView content has no network connection permission through its Content Security Policy.
- BLE service UUID advertising, filtered scanning, GATT server/client connections, and reconnect discovery.
- User-controlled Wi-Fi Direct group creation, peer discovery, explicit MAC-address connection, and length-prefixed TCP frame transport; optional Wi-Fi Aware publish/subscribe discovery with PSK-protected TCP data-path negotiation on supported hardware. BLE remains active when Wi-Fi Direct is unavailable or its optional permission is denied; Wi-Fi Aware is the default high-throughput peer transport when available.
- Adaptive per-peer BLE congestion handling: queue admission tightens after write failures, exponential backoff is applied, and durable outbox replay resumes when the backoff expires.
- Ingress-aware relay suppression: frames are not immediately sent back to the peer they arrived from. Duplicate-path ranking prioritizes fewer hops and uses a bounded historical arrival-age estimate as a tie-breaker. This is still broadcast-style forwarding, not destination-aware or radio-link-quality routing.
- GATT frame fragmentation sized to the negotiated MTU, ordered per-peer writes, bounded write queues, bounded reassembly buffers, and reassembly expiry. Frames are capped at 640 bytes (64 chunks at the 23-byte default ATT MTU), with compact 128-bit node IDs to keep the maximum relay route within that budget.
- Protocol v4 AES-256-GCM encrypted text, receipt, and non-durable EPHEMERAL frames with authenticated frame type, origin, frame ID, timestamp, receipt target, TTL, and route history. Relays re-encrypt after updating route metadata. All participating phones must run the same protocol version; protocol upgrades clear incompatible queued ciphertext and replay state.
- Group-level application receipts: a sender retires its queued message when any member holding the shared group key receives it; receipt frames are themselves queued and relayed.
- Persistent SQLite outbox (6,000-frame cap, 24-hour retention) and persistent receiver replay-ID cache (bounded to 8,192 IDs).
- Streaming bounded-file transfer over the same encrypted group-routed message channel: 10 MiB maximum per file, 48-byte chunks, 16-chunk bounded send windows, receiver-driven contiguous checkpoints, compact durable resume metadata, batched file sync/checkpoint every eight received chunks, retries after lost windows, stable transfer IDs for identical content/name, up to two active incoming files, and streaming SHA-256 verification before a file is finalized in app-private storage. The sender file is streamed rather than loaded into one whole-file byte array. A terminal completion ACK is emitted only after digest verification, promotion to the final private file, and durable publication of its deduplication marker; intermediate window ACKs only confirm checkpointed chunks. The inbox retains at most 50 completed files / 100 MiB and removes the oldest completed file when the quota is reached. The UI reports acknowledged bytes, percentage and chunk progress, average acknowledged throughput, and inbound checkpoint progress. The app targets Android 16 (API 36); its root layout applies system-bar, display-cutout, and IME insets so interactive controls remain within safe areas on edge-to-edge displays. Use **Send file**, **Cancel outgoing file transfer**, and **Save received file** in the Android app.
- Short voice notes are recorded locally as AAC/M4A (up to 7 seconds) and sent through encrypted file transfer. A separate opt-in live group voice prototype uses 8 kHz mono AMR-NB packets at 20 ms intervals inside encrypted v4 EPHEMERAL frames, per-speaker jitter queues/decoders, and a bounded audio mixer. Live audio bypasses the durable outbox and has no delivery ACK; incoming calls do not open the microphone. Users must explicitly start or join, and microphone permission is requested only for recording or joining/starting a call. AMR-NB codec availability, echo behavior, latency, and audio quality have not been validated on physical devices.
- User-started Android connected-device foreground service with an ongoing notification.
- User-initiated Solana Mobile Wallet Adapter authorization/deauthorization for Solana Devnet. The app displays the authorized public address and persists the MWA authorization token only as AES-GCM ciphertext protected by an Android Keystore key; it does not construct, sign, or submit transactions.
- Runtime Bluetooth and notification permission requests for supported Android versions.

## Build locally (no CI)

Requirements: Node.js/npm 22+, JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.9.1, Kotlin Gradle Plugin 2.2.21, and Gradle 8.11.1 or a compatible newer Gradle installation. From the repository root, install the web dependencies, then build Android. The Android Gradle preBuild task runs the Vite production build and bundles its output:

Google Play has required new apps and updates to target Android 16 (API 36) or higher since August 31, 2026. This branch is configured for API 36; confirm API-level behavior and device compatibility in your manual test pass.

    npm install
    cd mobile
    gradle :app:assembleDebug

On Windows PowerShell, run the helper from the repository root. It checks Node.js 22+, npm, JDK/Gradle/Android SDK prerequisites, installs dashboard dependencies without writing a lockfile, verifies the APK signature when `apksigner` is installed, and copies the result to `mobile/dist/MeshNet-debug.apk`:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\mobile\Build-DebugApk.ps1

Instrumented protocol and SQLite tests are included under `app/src/androidTest`. Run them locally on a connected Android device or emulator with:

    gradle connectedDebugAndroidTest

The Gradle build runs the web production build as part of preBuild; these commands do not invoke GitHub Actions. The PowerShell helper accepts `-RunConnectedTests` to run the instrumentation suite after creating the debug APK. A successful debug APK build does not certify physical-device interoperability or production security.

Install the debug APK on at least two BLE-capable Android phones. On each phone, enter the same high-entropy passphrase (at least 24 characters), grant Bluetooth and notification permissions, and start the mesh. A blank passphrase field reuses the locally saved credential. Use “Forget saved group credentials” to delete it from that device.

For Wi-Fi Direct, start the mesh on both devices. On one device choose **Create Wi-Fi Direct group**. On the other choose **Discover Wi-Fi Direct peers**, copy the discovered device address from the log, enter it, and choose **Connect to Wi-Fi Direct peer**. The group owner hosts the local TCP socket; the client connects to it. Wi-Fi Direct is user-triggered and does not auto-connect to discovered devices. Wi-Fi Aware discovery starts automatically on Android 10+ when supported. To avoid radio coexistence conflicts, choosing Wi-Fi Direct stops Wi-Fi Aware; use “Switch back to Wi-Fi Aware” to stop Wi-Fi Direct and resume Aware discovery.

For a multi-hop BLE check, use three phones with each device within BLE range of at least one neighbor, then move the origin and destination out of direct range while keeping the relay in range. Verify the receiver log shows the message and hop count.



## GitHub Actions validation and APK artifact

The repository workflow is currently manual-only (`workflow_dispatch`). Pushes and pull requests do not start CI on this branch. Do not dispatch it until the owner explicitly requests validation. When intentionally run, the Android job:

1. Builds the unsigned-by-owner, debug-signed APK with JDK 17, Gradle 8.11.1, and Android SDK Platform 36.
2. Verifies the APK signature and prints its SHA-256 checksum.
3. Runs the instrumentation suite on an Android 16 (API 36) Google APIs emulator.
4. Uploads `app/build/outputs/apk/debug/app-debug.apk` as `meshnet-debug-apk-<commit-sha>` for 14 days.

Download the APK from the successful workflow run's **Artifacts** section. This is a debug APK for installation and testing, not a Play Store release. GitHub's emulator cannot validate BLE/Wi-Fi interoperability on physical radios, OEM behavior, battery use, or live audio quality. A production release still requires the separate security, signing, legal, and device-validation gates below.

## Release validation

See [RELEASE_TEST_PLAN.md](RELEASE_TEST_PLAN.md) for the manual build, instrumentation, multi-phone BLE/Wi-Fi, file, voice, wallet, and release-signing checklist. Passing tests alone does not close the separately documented cryptography, routing, payment, or escrow production blockers.

## Release signing gate

Debug builds do not require signing secrets. Release packaging and signing tasks (`assembleRelease`, `bundleRelease`, and their package/sign tasks) depend on a fail-closed validation step. Supply these values through the local shell environment or a secrets manager; do not store them in Gradle files, committed properties files, or source control:

- `MESHNET_RELEASE_STORE_FILE`: absolute path to the release keystore.
- `MESHNET_RELEASE_STORE_PASSWORD`: keystore password.
- `MESHNET_RELEASE_KEY_ALIAS`: signing key alias.
- `MESHNET_RELEASE_KEY_PASSWORD`: signing key password.

Create and back up the keystore securely outside the repository. Use `keytool -genkeypair` to create an RSA signing key in a private directory, then set the four environment variables before running `gradle bundleRelease`. Release packaging stops with an actionable error when any variable is missing or the keystore path does not exist. Never commit or share the signing key or passwords.

## Security and delivery boundaries

- “Queued” means accepted into this device's encrypted-frame outbox. A successful GATT write only means the BLE stack accepted a chunk; it does **not** prove remote application delivery. The current receipt is a group-level acknowledgement: any member with the shared group key can generate it, and it does not prove delivery to a named recipient. Messages remain queued until a valid receipt arrives, the 24-hour expiry is reached, or the 6,000-entry capacity forces eviction of the oldest frame; capacity eviction can lose an unacknowledged message. Receipt frames have no ACK-of-ACK protocol: after at least one connected peer accepts a receipt into its BLE write queue, the local receipt is retired to prevent the outbox filling with unacknowledgeable receipts. If that BLE write later fails, a duplicate message can cause the receiver to regenerate the receipt; this is best-effort relay, not a delivery guarantee.
- Protocol v4 authenticates TTL and route metadata; relays re-encrypt already-authenticated plaintext for each updated route. All participating phones must be upgraded together. The migration clears incompatible older queued ciphertext and replay IDs. This still does not authenticate device identities: every group-key holder can forge a group origin, message, or receipt. The outbox and received-ID cache are local SQLite data. Group credentials are encrypted using an Android Keystore key; losing/resetting that key makes the saved credential unreadable and requires entering the group passphrase again.
- The shared passphrase is a temporary group-key bootstrap, not Signal Protocol. vodozemac has been selected as an Apache-2.0 candidate but is not integrated into the Android runtime. There is no per-device identity authentication, forward secrecy, or protection from a malicious member of the shared group. Group key changes clear the local queue; there is no secure multi-device key rotation protocol.
- File transfer is limited to 10 MiB per file and two concurrent inbound transfers. The sender reads the selected source in bounded streaming passes and sends a 16-chunk window at a time. The receiver syncs data and writes a compact checkpoint before acknowledging each eight-chunk batch. The terminal ACK is withheld until SHA-256 verification succeeds, the verified file is renamed to its final private destination, and its durable completion/deduplication marker is published. Interrupted sends can be resumed by reselecting the same file with the same filename; the sender reuses its transfer ID and the receiver resumes from persisted checkpoints, including after process recreation. The Android document picker read grant is persisted when the provider offers a persistable permission; reselect the file if its provider revokes access. **Cancel outgoing file transfer** interrupts local sender workers, rotates the sender's persisted transfer ID for a later retry, and sends a best-effort authenticated cancellation command. Receivers persist a 24-hour cancellation tombstone so delayed manifests already queued in an outbox cannot recreate a cancelled partial transfer. Chunks already queued in the sender outbox cannot be recalled and may remain until acknowledged or expired. Chunks received before their manifest are ignored and must be retransmitted by the sender. Partial receiver files expire after 24 hours. Completed files and their deduplication markers are retained in a bounded inbox of 50 files / 100 MiB; oldest completed items are removed when new files need space. **Save received file** exports a completed inbox item through Android's document picker, including when the mesh service is stopped. This is capped, resumable streaming—not an unlimited transfer service.
- Voice notes are locally recorded AAC/M4A clips capped at seven seconds and transferred as encrypted files; the app can play the latest received M4A note. Live voice is a separate best-effort group feature: it uses a shared group key, broadcasts audio to group members, has no individual recipient authorization, no retransmission/ACK, and may lose audio under congestion or radio loss. Incoming invitations expire after five minutes, stale speaker decoders are released after inactivity, and up to eight active speaker streams are mixed.
- hotspot metering, destination-aware adaptive multi-hop next-hop selection based on measured radio/link metrics, files above the 10 MiB product cap, production-grade group/per-device cryptography, Solana on-chain transaction construction/signing/submission, escrow UI/transaction integration, and iOS are not implemented. Wi-Fi Direct, Wi-Fi Aware, live voice, wallet authorization, and file transfer are source-level prototypes and have not been validated on physical devices. The Anchor escrow program is not compiled, tested, deployed, or audited.
- Android may stop a foreground service under exceptional resource or policy conditions. BLE peripheral advertising and concurrent GATT connections vary by chipset and vendor.
- GitHub Actions builds and signature-verifies the debug APK, then runs the Android instrumentation suite on an API 36 emulator. Consult the latest workflow run for the authoritative result. Emulator tests do not validate physical Bluetooth/Wi-Fi radios, OEM behavior, battery use, or live audio quality. Do not use this prototype for sensitive or financial traffic before independent security review and physical-device testing.



**Group-delivery boundary:** message receipts and file-transfer checkpoints currently confirm receipt by a group member, not a quorum across every previously seen device. MeshNet does not yet maintain an authenticated membership roster or per-recipient delivery ledger, so this must not be treated as guaranteed delivery to every offline group member.

## Remaining implementation plan

See [SECURITY_AND_ESCROW.md](SECURITY_AND_ESCROW.md) for the Apache-2.0 cryptography candidate, escrow design decision, integration gates, and explicit items that cannot safely be enabled without a deployed and reviewed program.
