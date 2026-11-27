# MeshNet release validation plan

This is the release checklist for the Android prototype. GitHub Actions runs on pull requests targeting main and can also be started manually. The dedicated release/trial-apk branch retains a debug APK artifact for seven days; ordinary pull-request runs do not upload APK artifacts. A successful emulator run verifies build and instrumented regression coverage only. It does not establish real-phone Bluetooth/Wi-Fi interoperability, Solana contract readiness, an independent security audit, or production fitness.

## 1. Release blockers that tests alone cannot close

Before calling MeshNet production-ready, resolve or explicitly remove these product claims/features from the release scope:

- **Cryptographic identity:** protocol v6 now adds a stable per-install P-256 signing identity stored in Android Keystore. The public-key fingerprint forms the node ID, and the origin signs the plaintext digest; receivers verify it after decrypting the shared AES-GCM group frame. This authenticates the enrolled origin key but does not provide per-device private sessions, forward secrecy, ratcheting, explicit trust approval/revocation, or secure group-key rotation. `vodozemac` is selected but not integrated.
- **Delivery semantics:** a group receipt confirms receipt by at least one group-key holder, not a named recipient, all group members, or offline devices.
- **Routing:** directed text frames carry an authenticated destination and use reverse next-hop candidates learned from received paths. The current Android app also contains signed ephemeral IPv4 overlay packets. The separate IP overlay neighbor cache is being hardened with address expiry and periodic announcements on the completion branch. Neither path has been validated across a three-phone physical radio topology; no reliable congestion-controlled IP transport is provided.
- **Payments:** the offline wallet signs and verifies provisional `MESH-LOCAL-UNIT` transfer receipts over the encrypted mesh. It is not a real-money wallet: there is no issuance, global balance ledger, double-spend prevention, or final settlement. Solana MWA supports Devnet authorization and a native SOL SystemProgram transfer builder that submits through the connected wallet's sign-and-send flow; no mainnet transfer, escrow transaction, token transfer, or authoritative app-side balance ledger exists. Devnet RPC confirmation read-back must be checked independently.
- **Escrow:** the Anchor source has not been built, tested, deployed, or independently audited. The program ID and initialization admin are now mandatory build inputs, but that does not constitute deployment or audit approval. Do not accept real funds.
- **Store identity and disclosure:** supply the actual product website/dApp identity URI, privacy policy, app listing, support contact, data-safety answers, and permission rationale for the distribution channel.
- **iOS:** remains deliberately deferred and is not part of this Android release candidate.

A messaging-only prototype release is a different scope from a secure production messenger or a service-payment marketplace. Do not describe the first as the latter.

## 2. Automated CI and APK artifact

The `MeshNet CI` workflow builds the Android debug APK, verifies its debug signature, runs `connectedDebugAndroidTest` on an API 36 emulator, and builds/typechecks the web app with Node 22 and 24. APK artifacts are retained for seven days on the dedicated trial branch or on explicit manual workflow runs; ordinary PR runs do not upload APK artifacts. The MeshNet 0.1.0 trial APK passed all these CI jobs. This is debug-signed, not a production release.

## 3. Local toolchain and build

Use JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.9.1, Kotlin Gradle Plugin 2.2.21, and Gradle 8.13 for the CI-equivalent build.

From `mobile/`:

    gradle --version
    gradle assembleDebug

Confirm the installed device/emulator is visible:

    adb devices -l

Run the local instrumentation suite on a connected Android phone or emulator:

    gradle connectedDebugAndroidTest

The tests under `app/src/androidTest` cover protocol and identity validation, route behavior, queue migration/persistence, receipt handling, encrypted credential storage, file-transfer resume/integrity/cancellation/quota/export behavior, wallet authorization storage, offline IP packet routing, DNS packet handling, neighbor-address expiry and HTTP portal access. Review any failures and fix them before proceeding. Do not substitute a successful build for device interoperability checks.

## 4. Two-phone BLE and messaging

On two Android 10+ BLE phones:

1. Install the same debug APK and set the same randomly generated, high-entropy group passphrase (at least 24 characters) on both devices.
2. Grant Bluetooth scan/connect/advertise and notification permissions. Verify denied permissions produce an actionable state and do not crash the service.
3. Start the mesh on both devices. Confirm each device exposes its BLE GATT server while also scanning/connecting as a GATT client; verify both devices can originate and receive messages. If BLE peripheral/advertising is unavailable on one phone, confirm the service remains alive and the Wi-Fi Direct/Aware fallback can carry traffic when that radio is supported. Confirm the service notification remains visible.
4. Send messages in both directions. Repeat while one phone is temporarily out of range, then return it to range and observe queued delivery and group-level receipt behavior.
5. Stop and restart the service and the app. Verify saved credentials can be reused; use **Forget saved group credentials** and confirm the passphrase is required again.
6. Repeat with different group passphrases. A phone in the wrong group must not display or accept another group's encrypted payload.

Remember: protocol v6 verifies the origin signing identity, but the group still shares one AES-GCM encryption key. These checks do not establish all-member delivery guarantees or security properties from a per-device ratchet.

## 5. Multi-hop and alternate transports

- **Three-phone BLE relay:** arrange phone A ↔ B ↔ C with A and C out of direct range. Send broadcast and directed messages in both directions. Verify B learns reverse routes, C receives the directed message, and the receipt returns by the learned route. Move phones and verify TTL/hop behavior and duplicate suppression.
- **Alternative routes and measured quality:** create two different paths to the same destination; record BLE RSSI and targeted-write success/failure/latency on each first hop. Verify the route score changes as samples change, stale metrics decay toward neutral after five minutes, and a disconnected peer is removed immediately. Confirm the router still respects hop distance, tries the next candidate after a failed write, and uses bounded flooding when no usable candidate remains. Record device models and measured RSSI/write latency. These local metrics are not end-to-end RTT or throughput.
- **Protocol upgrade:** validate the current protocol-version migration path from the supported prior database version(s). Confirm incompatible queued ciphertext and replay IDs are cleared safely, and confirm every group participant is updated before exchanging frames.
- **Wi-Fi Direct:** create a group on one phone, discover it on the second, connect using the discovered device address, transfer frames both ways, stop/recreate the group, and repeat after app restart.
- **Wi-Fi Aware:** use at least two devices confirmed to support Aware. Test discovery, PSK negotiation, TCP connection, simultaneous send/receive, peer loss, re-discovery, and switching to/from Wi-Fi Direct. Repeat with Aware unavailable and confirm BLE remains usable.
- **Radio coexistence:** run BLE while Wi-Fi Aware is active, then switch transports. Record device models, OS builds, RSSI/link state where available, and observed throughput/latency.

## 6. File transfer

Test with files of 1 byte, a few hundred bytes, exactly/around a chunk boundary, and the 10 MiB maximum:

- Verify SHA-256 of each exported file against the source.
- Test interrupted transfer, app process recreation, same-file re-selection/resume, and cancellation on the sender.
- After cancellation, confirm delayed queued manifests/chunks do not resurrect the partial transfer; retrying the same file should use a fresh transfer ID.
- Test receiver quota eviction at 50 files and 100 MiB, ensuring only the oldest completed MeshNet inbox items are removed.
- Modify a received file in app-private test data and confirm playback/export refuses it because its digest no longer matches.
- Export a received file with the service stopped. Verify file names with Unicode, long names, and extensions.
- Test a provider that grants persistent document URI permission and one that does not; reselect when access has been revoked.
- Confirm progress and average-throughput readings are monotonic/plausible; they describe acknowledged payload bytes, not guaranteed remote user consumption.

## 7. Voice and foreground-service behavior

- Record/play a voice note (up to seven seconds), transfer it, stop the mesh service, and play the received note from the private inbox.
- Start a live group call explicitly. Join from another phone only after the invitation appears. Verify microphone permission is requested only after explicit start/join; reject an invitation without activating the microphone.
- Test two or more concurrent speakers, mute/stop, invitation expiry, remote end, lock-screen/background behavior, phone calls/audio-focus loss and reacquisition, Bluetooth headset routing, echo, packet loss, and battery use. Confirm focus loss ends the local call, sends the end signal when possible, and restores the previous audio mode and speaker route.
- Confirm microphone foreground-service promotion is permitted only from the visible user-initiated flow on the supported Android versions. Check that stop/revoke paths restore audio mode and release resources.
- Record the exact device model, OEM build, AMR-NB codec availability, measured latency, and observed failures.

## 8. Solana Devnet wallet transfer

- Enable optional online integrations and connect a compatible MWA wallet explicitly on Devnet.
- Send a small test amount to a controlled Devnet recipient. Confirm the wallet displays the exact fee payer, recipient, and lamport amount before approval.
- Verify the returned signature against Devnet RPC, confirm the transaction reaches `confirmed` or `finalized`, and independently inspect the recipient balance/transaction.
- Reject malformed Base58 recipients, zero/negative amounts, more than nine decimal places, stale authorization, wallet-account changes, and RPC failures. Never switch this flow to mainnet implicitly.
- This is a native SOL Devnet transfer, not the offline local-unit wallet or the undeployed escrow program.

## 9. Wallet and escrow safety

- Create two independent offline wallet identities and verify that each recipient address is stable across process recreation.
- With internet disabled, send a signed local-unit transfer between two phones; verify chunk reassembly, recipient binding, signature verification, duplicate rejection, and durable receipt persistence. Interrupt the receiver process after only part of the voucher chunks arrive, restart it, deliver the remaining chunks, and verify reassembly completes from persisted chunks. Also interrupt after the final chunk but before receipt processing; on restart, the complete envelope must be processed and acknowledged. Verify the recipient signs an acknowledgement only after the receipt is committed, and the sender verifies the recipient key/signature and exact voucher ID, recipient, and amount before marking the outgoing record `RECEIVED`. Repeat with reordered/lost chunks and after restarting both apps; a missing acknowledgement must remain unconfirmed, never settled. Use the dashboard retry action and verify it resends the exact persisted signed voucher (same voucher ID), the receiver does not create a second receipt, and the receiver reissues a signed acknowledgement that moves the sender's journal to `RECEIVED`.
- Modify the amount, sender public key, recipient, and signature in a captured voucher; every altered voucher must be rejected. Verify non-recipient group members do not import the voucher.
- Confirm the UI never displays local-unit receipts as fiat, cryptocurrency, spendable balance, or blockchain settlement. Do not use real money or valuable assets; offline double-spend prevention is not solved.
- Test MWA authorize, process recreation, restore, deauthorize, and local credential cleanup with a compatible Devnet wallet.
- Verify the displayed address against the wallet. Confirm the app does not imply that a payment was made or that funds are held in escrow.
- Do not use real USDC or advertise payment/escrow functionality until the program has a reproducible build, actual program/admin key ownership, Devnet deployment, adversarial tests, external audit, upgrade-authority policy, emergency plan, and client transaction verification.
- Keep program keypairs, wallet files, seed phrases, and signing passwords outside the repository.

## 10. Release signing and package verification

Create the release keystore in a private location outside this repository. Configure these environment variables locally or via a secrets manager:

- `MESHNET_RELEASE_STORE_FILE`
- `MESHNET_RELEASE_STORE_PASSWORD`
- `MESHNET_RELEASE_KEY_ALIAS`
- `MESHNET_RELEASE_KEY_PASSWORD`

Then build the Play/store package locally:

    gradle bundleRelease

The Gradle release gate must fail when signing data is missing or the keystore path is not an existing absolute path. Verify the resulting AAB's signer certificate and preserve the keystore/password backup securely. Never commit the keystore or secrets. Increment `versionCode` and set the final `versionName` only after the product owner chooses the release version.

## 11. Final release decision

Release only after the required tests are green, all manual results are recorded, store/legal disclosures are complete, and every production blocker above is either implemented and independently reviewed or explicitly removed from the app's advertised scope. A passing test suite does not replace a cryptographic review, a smart-contract audit, or real-device interoperability validation.
