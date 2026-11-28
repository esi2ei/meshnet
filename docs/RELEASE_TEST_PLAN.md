# MeshNet Release Test Plan

**Revision date:** 2026-11-28  
**Scope:** Android physical-device, radio, lifecycle, network and release-readiness validation  
**Current disposition:** All physical-device cases below are **Pending / Not Run**. This document defines reproducible procedures; it is not evidence that a test has been executed.

## Status policy

- **Passed** requires a dated result, the exact app commit/build, device model and OEM build, Android API level, topology, steps performed, logs and measured outcome.
- **Failed** requires the same evidence plus a reproducible failure description.
- **Pending / Not Run** means no qualifying execution evidence exists. Do not infer success from source review, an emulator, a successful APK build, or an unrelated workflow run.
- The listed physical-device cases normally do **not** block an ordinary PR merge solely because they cannot run on GitHub-hosted runners. They remain release-acceptance gates. A case becomes a merge blocker only when the affected change has a specific non-waivable security, legal or critical acceptance requirement.
- If a required hardware capability is absent on a test device, record **Unsupported / Not Run** for that device and test a supported device; do not mark the feature as passed.

## Test environment and evidence

For each run, record:

| Field | Required value |
|---|---|
| App source | Full 40-character Git commit SHA and APK SHA-256 |
| Device | Manufacturer, exact model and hardware revision |
| OS | Android version, API level, OEM build fingerprint |
| Topology | Number of devices, each device role, direct/relay links |
| Radio/network | BLE, Wi-Fi Direct, Wi-Fi Aware, Wi-Fi state, airplane-mode state, upstream network |
| Permissions | Granted, denied, revoked and re-granted permissions exercised |
| Power/lifecycle | Screen state, battery optimization state, foreground/background state |
| Duration | Start/end time, test duration and repetition count |
| Evidence | Redacted app/service logs, screenshots where useful, measured values |
| Result | Passed / Failed / Pending / Not Run / Unsupported, plus issue link |

Use test accounts and non-sensitive sample content. Do not use production signing material, private keys, real payment funds, or real user data. Any wallet flow in this plan must use the explicitly labelled Devnet/test environment only.

## Physical-device test cases

### HW-01 — BLE/GATT dual-role discovery and bidirectional messaging

- **Related issues:** #17, #19, #27, #31, #32
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** Android emulators and hosted runners do not provide representative physical Bluetooth controllers, antenna behavior, GATT concurrency, OEM radio scheduling or real nearby-device discovery.
- **Prerequisites:** Two physical Android devices with BLE support; compatible MeshNet builds and the same test group credential; Bluetooth enabled; runtime Nearby/Bluetooth permissions granted; both devices charged and logs enabled.
- **Procedure:**
  1. Install the same test build on both devices and record its commit SHA and APK checksums.
  2. Start MeshNet on both devices; grant only the permissions needed for the test.
  3. Confirm each device discovers the other and record discovery time and peer identity.
  4. Send uniquely identified text messages A to B and B to A; verify content, sender identity and duplicate behavior.
  5. Disable Bluetooth on one device during an active exchange, then re-enable it and observe recovery.
  6. Repeat with one device screen-off and with the app backgrounded under the documented foreground-service state.
  7. Repeat at least five connect/disconnect cycles and capture logs.
- **Pass criteria:** Both devices can discover and exchange messages in both directions; no message is reported delivered without the applicable receipt; retries do not create duplicate user-visible messages; link loss and reconnection are visible and do not corrupt queued data.
- **Evidence to attach:** Device matrix, discovery/reconnect timings, redacted logs and message IDs.

### HW-02 — Wi-Fi Direct group formation, connection and recovery

- **Related issues:** #17, #27, #31
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** Real Wi-Fi Direct group negotiation, owner/client behavior, vendor supplicant implementation, radio coexistence and OEM permission handling cannot be validated by a hosted emulator.
- **Prerequisites:** Two or more physical Android devices with Wi-Fi Direct support; Wi-Fi enabled; required Nearby Wi-Fi permissions; compatible app builds and test credentials.
- **Procedure:**
  1. Record Wi-Fi Direct support and OS/OEM build for each device.
  2. On the designated group owner, start the app and create a Wi-Fi Direct group.
  3. On the peer, discover the group and initiate connection using the app's user-facing control.
  4. Verify peer state, route state and bidirectional test messaging.
  5. Remove the group or toggle Wi-Fi on one device; verify the app reports loss rather than stale connectivity.
  6. Re-form the group and confirm recovery without duplicate peers or messages.
- **Pass criteria:** Supported devices form and reconnect to the group; permissions and unsupported states are explicit; no unrelated BLE or local dashboard capability is disabled by a Wi-Fi Direct failure.
- **Evidence to attach:** Support flags, connection logs, before/after peer and route state, and recovery time.

### HW-03 — Wi-Fi Aware availability, peer path and fallback

- **Related issues:** #17, #27, #31
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** Wi-Fi Aware depends on device chipset, firmware, Android release, nearby-permission behavior and OEM service availability.
- **Prerequisites:** Two physical devices whose hardware and OS report Wi-Fi Aware support; compatible builds; Wi-Fi enabled; required runtime permissions granted.
- **Procedure:**
  1. Verify and record Wi-Fi Aware support on both devices before starting MeshNet.
  2. Start MeshNet and invoke the documented Wi-Fi Aware resume/discovery path.
  3. Confirm peer discovery and send a bidirectional test message.
  4. Toggle Wi-Fi or revoke the relevant permission and observe capability state.
  5. Restore permission/radio and verify recovery.
  6. On a device without Aware support, confirm the app retains other available transport paths and reports Aware as unavailable.
- **Pass criteria:** Aware is used only when supported and available; loss of Aware does not disable BLE or unrelated features; unavailable state is reported honestly.
- **Evidence to attach:** Hardware support results, permission prompts, service logs and transport-state transitions.

### HW-04 — Three-device multi-hop routing and relay-loss recovery

- **Related issues:** #17, #19, #31, #32
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** The scenario requires three independent physical radios and real concurrent links; emulator networking does not represent RF contention, range, interference or link asymmetry.
- **Prerequisites:** Three physical Android devices A, B and C; compatible builds and test group; a topology in which A and C cannot communicate directly but both can communicate with B; logs on all devices.
- **Procedure:**
  1. Place A and C outside direct radio range and B within range of both.
  2. Start MeshNet on all devices and record discovered links and selected routes.
  3. Send uniquely identified messages A to C and C to A through B; capture ingress and forwarding logs.
  4. Repeat with duplicate frames and delayed/reordered delivery where the UI or test harness permits.
  5. Disable B's radio or stop B's service during delivery.
  6. Observe route invalidation and retry behavior; restore B or establish an alternate supported path.
  7. Verify message IDs, duplicate suppression, TTL/loop bounds and queued/pending state.
- **Pass criteria:** A-to-C traffic uses B only while a valid route exists; relay loss is visible; no infinite forwarding loop occurs; a message is not marked delivered solely because a relay accepted it; recovery does not corrupt or silently discard queued messages.
- **Evidence to attach:** Topology diagram, per-device logs, route transitions, message IDs, delivery state and timing.

### HW-05 — VPN/TUN overlay, DNS and underlying-network handover

- **Related issues:** #17, #27, #31, #32
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** Host runners cannot reproduce Android VPN consent/revocation, physical routing tables, OEM network selection, captive portals, private DNS behavior or mobile-to-Wi-Fi handover.
- **Prerequisites:** Two or three physical Android devices; MeshNet test group; VPN consent available; a known test host/service reachable only through the private mesh; optional Wi-Fi and cellular networks for handover.
- **Procedure:**
  1. Start the mesh and explicitly approve the Android VPN request.
  2. Record assigned overlay addresses and route observations.
  3. Test the documented local portal and a mesh-local DNS name.
  4. Exercise supported TCP and UDP traffic; record packet loss and DNS answers.
  5. Switch underlying connectivity between Wi-Fi and cellular, then test a captive-portal or temporarily unavailable upstream network when safe.
  6. Revoke VPN consent and verify traffic stops using the overlay; grant consent again and check recovery.
  7. Stop and restart the service and verify stale routes are removed.
- **Pass criteria:** Only the private overlay is represented as implemented; consent and revocation are respected; DNS and route failures are visible; no claim of public Internet gateway/NAT is made; unrelated local mesh features remain usable.
- **Evidence to attach:** VPN status, route/DNS output, packet-drop counters, redacted logs and handover timings.

### HW-06 — Android lifecycle, background restrictions and permission revocation

- **Related issues:** #17, #27, #31
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** Background execution limits, Doze, foreground-service policy, notification permission, process management and battery optimization vary by Android version and OEM.
- **Prerequisites:** Physical devices representing supported Android API levels (minimum API 26 and current supported API); at least two OEM families where available; battery optimization settings accessible; test credentials.
- **Procedure:**
  1. Record model, Android API, OEM build and battery-optimization settings.
  2. Start MeshNet through the normal user flow; verify notification and foreground-service state.
  3. Lock the screen, leave the app backgrounded, and observe discovery/connection/message state for a fixed 30-minute window.
  4. Repeat with the OS battery optimization enabled and disabled; record the exact setting.
  5. Deny, revoke and re-grant Bluetooth/Nearby, microphone and notification permissions as applicable.
  6. Force-stop the app, separately simulate process death where available, then relaunch and verify restoration of durable records and truthful capability state.
  7. Repeat after reboot; do not assume automatic startup unless the platform explicitly permits it.
- **Pass criteria:** The app does not claim always-on behavior; foreground-service requirements and missing permissions are visible; state recovers or degrades according to documented Android/OEM constraints; durable records are not silently reset.
- **Evidence to attach:** Notification state, permission history, OS/OEM settings, lifecycle logs and exact observation intervals.

### HW-07 — Interrupted file transfer, resume and integrity

- **Related issues:** #19, #22, #27, #31
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** Real radio interruption, filesystem providers, storage pressure, background process death and large-file behavior require physical devices and device storage.
- **Prerequisites:** Two physical devices; test files with known SHA-256 digests at small, medium and large sizes; sufficient free storage; one supported mesh path.
- **Procedure:**
  1. Calculate and record the SHA-256 digest and size of each source test file.
  2. Transfer each file from A to B and verify the resulting size and digest.
  3. Start a larger transfer and interrupt the link mid-transfer.
  4. Reconnect and request/resume the same transfer using the normal UI.
  5. Repeat with process death, screen-off and temporary storage pressure where safe.
  6. Attempt a duplicate request and inspect quota/error handling.
  7. Confirm incomplete or corrupt content is not promoted as a verified completed file.
- **Pass criteria:** Completed files match the original digest and size; interrupted transfers resume or fail explicitly; corrupt/partial content is not exposed as complete; quota limits are enforced without silent data loss.
- **Evidence to attach:** Source/destination hashes, sizes, transfer logs, interruption point, resume behavior and storage state.

### HW-08 — Voice-note and live-voice audio lifecycle

- **Related issues:** #19, #27, #31
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** Microphone hardware, audio focus, Bluetooth headsets, acoustic echo, audio routing, incoming-call interruptions and OEM foreground-service behavior need real devices.
- **Prerequisites:** Two physical Android devices; microphone permission; quiet test environment; optional wired/Bluetooth headset; compatible build.
- **Procedure:**
  1. Record and send a voice note A to B; play it on B and verify duration and intelligibility.
  2. Repeat B to A.
  3. Establish a live voice session using the supported flow.
  4. Lock the screen, background the app and test a supported audio route change.
  5. Exercise microphone permission denial/revocation and an incoming call or other audio-focus interruption.
  6. End the session and confirm microphone/foreground-service state is released.
- **Pass criteria:** Permission and foreground-service state are correct; users can end the session; interruption/failure is visible; no silent recording or continued microphone use remains after call termination.
- **Evidence to attach:** Device/headset details, permission state, audio-route logs and redacted observations. Do not upload recordings containing personal speech.

### HW-09 — Physical performance, resource and battery baseline

- **Related issues:** #17, #19, #22, #27, #31, #32
- **Status:** **Pending / Not Run**
- **Why CI cannot establish this:** Shared hosted-runner resource measurements and emulated radios are not representative of device CPU, memory, power consumption, thermal throttling or radio duty cycle.
- **Prerequisites:** At least two representative physical device classes; repeatable one-hop and three-node topologies; fixed test duration and payload set; battery and system metric collection.
- **Procedure:**
  1. Record idle baseline CPU, memory and battery state for 10 minutes.
  2. Run a fixed-rate messaging workload and then a fixed-size file transfer.
  3. Repeat with screen-off/background state and a three-node relay topology.
  4. Record CPU, peak memory, battery delta, queue depth, packet drops, throughput and p50/p95 message latency.
  5. Repeat each scenario at least three times and record environmental differences.
  6. Compare only like-for-like device/build/topology runs; do not invent thresholds after seeing results.
- **Pass criteria:** No unbounded queue/thread/resource growth; all drops and backpressure are observable; reproducible baselines and numeric acceptance limits are agreed before declaring optimization complete.
- **Evidence to attach:** Raw measurements, scripts/tools used, device model, temperature/power conditions, repeated-run summary.

## GitHub-capable automation not run in the current review

### CI-01 — Full Android API 36 instrumentation suite

- **Class:** Group A — GitHub-capable automated test
- **Status:** **Pending / Not Run** in this review; do not confuse with the 197 focused tests that passed on PR #29 head c41e48dfbc89c63619776baf1ba6c4503bee7259.
- **Why not run here:** The connected GitHub actions available to this review can read workflow runs and logs but do not expose a workflow-dispatch operation. The repository workflow has a manual workflow_dispatch input for this full suite.
- **Prerequisites:** A branch with the intended code (for the current completion changes, work/offline-mesh-completion at the recorded head); GitHub Actions permission to dispatch workflows; available hosted Android emulator runner.
- **Manual execution:** Open the repository's Actions tab → select **MeshNet CI** → **Run workflow** → choose work/offline-mesh-completion → set run_android_instrumentation=true → start the run. Verify the run checks out the expected SHA, that the full :app:connectedDebugAndroidTest task completes, and that the workflow concludes successfully. Save the run URL and test report.
- **Acceptance:** Full suite completes with zero failed tests and zero errors on the exact tested SHA. A successful focused subset is not a substitute.
- **Important distinction:** This is executable on GitHub and is not a physical-device test. Its current Not Run status is a tooling limitation of this review session, not evidence of test failure.

## External release gates (not CI success criteria)

- **SEC-01 — Independent cryptographic protocol review:** **Pending / Not Run**. Requires a documented threat model, exact source commit, protocol specification, test vectors and an independent reviewer. Related issues #18, #31 and #32. Do not market shared group-key AES-GCM as per-device end-to-end encryption or forward secrecy.
- **REL-01 — Production signing and release artifact validation:** **Pending / Not Run**. Requires owner-controlled signing material stored outside the repository, a signed release AAB, checksum/provenance, release rollback plan and store/privacy/legal decisions. Related issue #28. A debug APK is not a release artifact.
- **PAY-01 — Financial and carrier/legal review:** **Pending / Not Run**. Required before enabling real-money settlement, paid routing, Internet sharing or escrow. Local vouchers and Devnet transfers are not settlement evidence. Related issues #23–#26.

## Current recorded status

As of 2026-11-28, the repository review confirmed successful focused API 36 CI tests on PR #29 head c41e48dfbc89c63619776baf1ba6c4503bee7259, but no qualifying physical-device execution evidence was available. Therefore **HW-01 through HW-09, CI-01, SEC-01, REL-01 and PAY-01 remain Pending / Not Run** until individual evidence is attached and reviewed.
