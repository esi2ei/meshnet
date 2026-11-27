# MeshNet release validation plan

This is a release checklist for the Android prototype. GitHub Actions is configured to build the debug APK and run the instrumentation suite on an API 36 emulator for the designated validation branch. Until a successful run is recorded, do not assume those automated checks have passed. Emulator success does not establish real-phone Bluetooth/Wi-Fi interoperability, Solana contract readiness, independent security audit, or production fitness.

## 1. Release blockers that tests alone cannot close

Before calling MeshNet production-ready, resolve or explicitly remove these product claims/features from the release scope:

- **Cryptographic identity:** the current app still uses one shared AES-GCM group passphrase. It has no authenticated per-device identity, forward secrecy, signed prekeys, secure group key rotation, or device revocation. `vodozemac` is selected but not integrated.
- **Delivery semantics:** a group receipt confirms receipt by at least one group-key holder, not a named recipient, all group members, or offline devices.
- **Routing:** relay is broadcast/flooding with TTL and duplicate-route scoring. It is not destination-aware unicast and does not select routes from measured radio metrics.
- **Payments:** wallet support only authorizes/deauthorizes a Solana Devnet wallet. No transaction builder, signing, submission, balance verification, or escrow UI exists.
- **Escrow:** the Anchor source has not been built, tested, deployed, or independently audited. The program ID and initialization admin are now mandatory build inputs, but that does not constitute deployment or audit approval. Do not accept real funds.
- **Store identity and disclosure:** supply the actual product website/dApp identity URI, privacy policy, app listing, support contact, data-safety answers, and permission rationale for the distribution channel.
- **iOS:** remains deliberately deferred and is not part of this Android release candidate.

A messaging-only prototype release is a different scope from a secure production messenger or a service-payment marketplace. Do not describe the first as the latter.

## 2. Automated CI and APK artifact

The `MeshNet CI` workflow builds the Android debug APK, verifies its debug signature, runs `connectedDebugAndroidTest` on an API 36 emulator, and uploads the APK as a 14-day workflow artifact. A green run verifies build and instrumented regression coverage in the emulator only; it is not a physical-radio or security-audit result.

## 3. Local toolchain and build

Use JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.9.1, Kotlin Gradle Plugin 2.2.21, and Gradle 8.11.1 or a compatible newer Gradle.

From `mobile/`:

    gradle --version
    gradle assembleDebug

Confirm the installed device/emulator is visible:

    adb devices -l

Run the local instrumentation suite on a connected Android phone or emulator:

    gradle connectedDebugAndroidTest

The tests under `app/src/androidTest` cover protocol/frame validation, queue persistence, receipt handling, encrypted credential storage, file-transfer resume/integrity/cancellation/quota/export behavior, and wallet authorization storage. Review any failures and fix them before proceeding. Do not substitute a successful build for device interoperability checks.

## 4. Two-phone BLE and messaging

On two Android 10+ BLE phones:

1. Install the same debug APK and set the same randomly generated, high-entropy group passphrase (at least 24 characters) on both devices.
2. Grant Bluetooth scan/connect/advertise and notification permissions. Verify denied permissions produce an actionable state and do not crash the service.
3. Start the mesh on both devices. Confirm each discovers/connects to the other and that the service notification remains visible.
4. Send messages in both directions. Repeat while one phone is temporarily out of range, then return it to range and observe queued delivery and group-level receipt behavior.
5. Stop and restart the service and the app. Verify saved credentials can be reused; use **Forget saved group credentials** and confirm the passphrase is required again.
6. Repeat with different group passphrases. A phone in the wrong group must not display or accept another group's encrypted payload.

Remember: these checks do not establish authenticated per-device identities or all-member delivery guarantees.

## 5. Multi-hop and alternate transports

- **Three-phone BLE relay:** arrange phone A ↔ B ↔ C with A and C out of direct range. Send in both directions, then move phones and verify TTL/hop behavior and duplicate suppression. Repeat with a peer disconnecting during fragmentation.
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
- Test two or more concurrent speakers, mute/stop, invitation expiry, remote end, lock-screen/background behavior, phone calls/audio focus, Bluetooth headset routing, echo, packet loss, and battery use.
- Confirm microphone foreground-service promotion is permitted only from the visible user-initiated flow on the supported Android versions. Check that stop/revoke paths restore audio mode and release resources.
- Record the exact device model, OEM build, AMR-NB codec availability, measured latency, and observed failures.

## 8. Wallet and escrow safety

- Test MWA authorize, process recreation, restore, deauthorize, and local credential cleanup with a compatible Devnet wallet.
- Verify the displayed address against the wallet. Confirm the app does not imply that a payment was made or that funds are held in escrow.
- Do not use real USDC or advertise payment/escrow functionality until the program has a reproducible build, actual program/admin key ownership, Devnet deployment, adversarial tests, external audit, upgrade-authority policy, emergency plan, and client transaction verification.
- Keep program keypairs, wallet files, seed phrases, and signing passwords outside the repository.

## 9. Release signing and package verification

Create the release keystore in a private location outside this repository. Configure these environment variables locally or via a secrets manager:

- `MESHNET_RELEASE_STORE_FILE`
- `MESHNET_RELEASE_STORE_PASSWORD`
- `MESHNET_RELEASE_KEY_ALIAS`
- `MESHNET_RELEASE_KEY_PASSWORD`

Then build the Play/store package locally:

    gradle bundleRelease

The Gradle release gate must fail when signing data is missing or the keystore path is not an existing absolute path. Verify the resulting AAB's signer certificate and preserve the keystore/password backup securely. Never commit the keystore or secrets. Increment `versionCode` and set the final `versionName` only after the product owner chooses the release version.

## 10. Final release decision

Release only after the required tests are green, all manual results are recorded, store/legal disclosures are complete, and every production blocker above is either implemented and independently reviewed or explicitly removed from the app's advertised scope. A passing test suite does not replace a cryptographic review, a smart-contract audit, or real-device interoperability validation.
