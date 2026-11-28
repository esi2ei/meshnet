# Feature-preserving refactor contract

## Objective

Restructure MeshNet's internals without reducing the product. Existing working capabilities, partially implemented capabilities, user data, protocol interoperability and future roadmap commitments must remain visible and traceable throughout the refactor.

This document is a binding engineering rule for work on the completion branch. It does not claim that every listed capability is production-ready. It distinguishes implemented behavior from prototypes, partial implementations and planned work so that preservation is not confused with advertising an unfinished feature as complete.

**Baseline boundary for this revision:** PR #36 is based on `main` at `0fe421254c8b8e60ec0b03f064d5309bea278079`. The code refactor is being developed separately in Draft PR #29; files and checks that exist only on PR #29 are not part of `main` and must not be described as merged or enforced on the default branch.

## Non-negotiable rules

1. **No silent feature removal.** Do not delete, hide, disable, replace or materially narrow an existing user-facing capability as a side effect of refactoring. A proposed product-scope change requires an explicit decision recorded in the roadmap and release notes; it is not an engineering shortcut.
2. **Preserve behavior before changing structure.** Add characterization tests for the existing behavior first. Extract code behind narrow interfaces and adapters, then move one responsibility at a time. Avoid a big-bang rewrite.
3. **Keep the old path until the new path proves equivalent.** During a migration, retain a compatibility facade or adapter. Change the default only after automated regression checks pass; keep a rollback path until the migration is accepted.
4. **Preserve the dashboard and bridge contract.** Existing screens, controls, bridge commands, permissions, progress/error states and offline behavior remain available. Native internals may change without forcing a simultaneous UI rewrite.
5. **Preserve data and identity.** Database and file changes must be versioned, transactional and recoverable. Never silently discard messages, memberships, receipts, library content, wallet history, vouchers, pending transfers or identity material. A missing signing key must fail closed; it must not silently create a replacement identity for retained records.
6. **Preserve protocol interoperability.** Do not break protocol v6 peers as an incidental refactor. Protocol v7 or later changes must be additive, versioned and explicitly negotiated with downgrade/replay tests. Do not relabel shared-key encryption or local evidence as stronger security than it provides.
7. **Isolate optional capability failures.** A device without Wi-Fi Aware, a revoked permission, a lost radio or an unavailable Devnet endpoint must degrade only the affected path. It must not disable unrelated BLE, Wi-Fi Direct, local messaging, files, voice, VPN or offline dashboard functions.
8. **Keep future commitments traceable.** Design-only or not-yet-implemented capabilities remain in the roadmap with honest status. Do not mark an issue complete because a prototype, interface or design document exists.
9. **Prove the result.** Every moved capability needs named tests and an acceptance owner/evidence requirement. Emulator CI does not substitute for physical radio, OEM background, battery, privacy or independent-audit evidence.

## Capability inventory and preservation obligations

The table describes the current repository scope at the time this contract was added. The roadmap remains the detailed source of truth for implementation status and issue-level acceptance criteria.

| Capability | Current state in the repository | Preservation obligation |
|---|---|---|
| Android APK and offline dashboard | Implemented; React/Vite assets are bundled in the Android app and exposed through a local WebView bridge | Keep the installed app self-contained and retain existing workspace areas, controls, status and error feedback |
| BLE/GATT | Implemented; real radio behavior still requires device validation | Preserve discovery, GATT server/client roles, bidirectional exchange, reconnect and bounded transfer behavior |
| Wi-Fi Direct | Implemented and user-controlled; device/OEM behavior requires validation | Preserve explicit discovery/connect flows and do not make it an automatic prerequisite for BLE |
| Wi-Fi Aware | Optional implementation; hardware support and availability vary | Preserve it as an independent optional transport; recover when Android availability changes |
| Peer registry, routing and relay | Implemented in part; route evidence, convergence, relay recovery and physical multi-hop behavior remain incomplete | Preserve transport-qualified peers, TTL, bounded deduplication, destination-aware routing and honest distinction between direct links and route observations |
| Private IPv4 overlay, VPN, mesh DNS and local portal | Implemented foundation for the local 100.64.0.0/10 intranet | Preserve user-consented VPN, local addressing, .mesh resolution and published-file portal; do not imply public Internet gateway/NAT exists |
| Direct and group messaging | Partial; directed delivery and local membership/receipt primitives exist, while complete durable group delivery is unfinished | Preserve compose/send/history/queue behavior; finish durable membership reconciliation, snapshot/outbox/receipt integration and recipient-level delivery truthfully |
| Voice notes and live voice | Implemented paths with physical-device/audio validation still required | Keep the existing UI and audio use cases during transport/router refactoring; test permission, audio focus, interruption and reconnect |
| Offline library and file transfer | Partial; explicit publishing, content addressing, bounded resumable transfer and integrity checks exist; private ACL/search/recovery work remains | Preserve publish, discover/request, resume, digest verification, portal access and quotas; do not broaden visibility by accident |
| Local offline wallet | Prototype; provisional local units/vouchers are not money or a globally authoritative balance | Preserve existing history and pending evidence; fail closed on missing/corrupt keys; do not present vouchers as settled funds |
| Solana wallet integration | Optional Devnet/Mobile Wallet Adapter prototype; not mainnet settlement | Keep online integration separate and opt-in; preserve clear cluster/network confirmation and do not enable real-fund behavior by implication |
| Escrow, contracts and accounting | Prototype/design and partial local evidence; not production-deployed or audited | Keep source and roadmap traceable, but do not claim deployed escrow, receiver-measured billing or settlement |
| Governance, reputation and arbitration | Design-only / not implemented | Retain as explicit future roadmap capabilities; do not delete the designs or mark them implemented |
| Internet sharing, metering and marketplace | Not implemented; current VPN is a private intranet | Keep the goal on the roadmap behind legal, safety, consent, metering and abuse-control gates |
| iOS client | Not implemented and currently deferred | Keep the decision visible; do not block Android stability work or silently mark iOS delivered |

## Target architecture — staged, not a rewrite

The target is a set of independently testable responsibilities behind stable boundaries. It is not a requirement to create every module or move every class in one change.

```text
React/Vite dashboard (existing screens)
              |
      stable native bridge
              |
      application coordinator
              |
   +----------+-----------+------------------+
   |          |           |                  |
transport   messaging   identity/security   product capabilities
supervisor  & groups    sessions            files / voice / wallet
   |          |           |                  |
   +----------+-----------+------------------+
              |
   persistence / filesystem / Android Keystore
              |
 BLE / Wi-Fi Direct / Wi-Fi Aware / VPN / Android APIs
```

Recommended ownership boundaries:

- **Presentation:** React components render state and issue documented commands; they do not own radio or persistence state.
- **Application coordinator:** preserves existing bridge commands and orchestrates feature use cases; it must not absorb business logic.
- **Transport supervisor:** owns BLE, Wi-Fi Direct and Wi-Fi Aware lifecycle, availability, fallback, connection registration and resource policy.
- **Routing and messaging:** owns frame validation, routing, outbox, group state, durable synchronization, delivery receipts and retry rules.
- **Identity and security:** owns device identity, trust/enrollment, key lifecycle and session-protocol integration. It must not be conflated with routing metadata.
- **Product capabilities:** files/library, voice, VPN/DNS/portal and wallet/accounting remain separate domain responsibilities with explicit interfaces.
- **Infrastructure:** owns database transactions, filesystem access, Android Keystore and platform APIs.

### Persistence policy

- Do not add a second database or a JSON-backed shadow store to make the refactor appear modular.
- Keep existing SQLite-backed stores and their data intact while ownership boundaries are extracted.
- Before combining stores, document the invariants that require atomicity (for example message + recipient snapshot + outbox entry), schema ownership, encryption boundary and migration/rollback strategy.
- Evaluate a single transactional SQLite/Room-backed repository for data that truly must commit atomically. Room is an implementation option, not a reason to rewrite all stores or discard proven SQLite code.
- Use JSON for bridge/configuration/interchange data where appropriate, not as the authoritative message, wallet or membership database.
- Any schema consolidation must include versioned migrations, old-database fixtures, interrupted-migration recovery, idempotency and tests proving that records and identity bindings survive upgrades.

### Protocol and compatibility policy

- Keep protocol v6 parsing/behavior covered while extracting transport, router and persistence responsibilities.
- Introduce new message/session capabilities through explicit version negotiation and bounded compatibility adapters.
- A protocol upgrade must define old-peer behavior, unknown-field handling, maximum frame/chunk sizes, replay limits, downgrade rejection and recovery after partial synchronization.
- Keep group delivery fail-closed when recipient evidence is incomplete; do not replace a truthful pending/unknown state with a false delivered state.

## Current Android bridge contract

The native JavaScript bridge is a product surface, not disposable glue. The current audit found **31** `@JavascriptInterface` methods in `MainActivity.MeshNetBridge` and **31** corresponding declarations in the TypeScript `NativeBridge` type. The active React surface calls 30 of them; `getOfflineWalletAddress` remains a compatibility getter while the workspace currently reads this value from the runtime-status snapshot.

| Capability / UI area | Bridge methods | Native owner and data boundary | Existing regression evidence / remaining gap |
|---|---|---|---|
| Runtime status and dashboard | `getRuntimeStatus` | `MainActivity.runtimeStatusJson()`; aggregates service, transport, library, VPN and wallet state | `BundledDashboardAssetsTest` verifies bundled assets, not the complete runtime JSON contract; add a focused status/bridge contract test |
| Mesh lifecycle and credentials | `startMesh`, `stopMesh`, `forgetGroupCredentials` | `MainActivity` permission/command adapter; `MeshForegroundService` lifecycle; identity, secret, group and queue stores | Identity, secret, membership and queue tests exist; activity/service lifecycle and confirmation flow still need device coverage |
| Broadcast and directed messaging | `sendMessage`, `sendDirectMessage` | `MeshForegroundService` → `MeshRouter` → durable `MeshQueueStore` and receipt path | `MeshRouterReceiptTest`, `MeshQueueStoreTest`, frame/crypto tests; full group snapshot/outbox/receipt integration remains incomplete |
| Private IP network and portal | `startOfflineNetwork`, `stopOfflineNetwork`, `openOfflinePortal` | `MainActivity` handles VPN consent; `MeshVpnService` and service IP router own TUN/overlay; portal serves published library items | `MeshIpPacketRouterTest`, `MeshOfflinePortalServerTest`; permission revoke, service death and physical network handover remain device gates |
| Offline library | `selectOfflineContent`, `requestOfflineContent`, `removeOfflineContent` | `MainActivity` document picker; `MeshForegroundService` and `MeshOfflineLibrary`; bounded content-addressed store | `MeshOfflineLibraryTest`, `MeshFileTransferTest`; private ACL, revoke/unpublish and hardware relay tests remain open |
| General file transfer | `selectFile`, `cancelFileTransfer`, `saveReceivedFile` | `MainActivity` document picker/export; `MeshForegroundService` and `MeshFileTransfer` | `MeshFileTransferTest`; interrupted export, provider permissions and multi-device recovery need device tests |
| Voice notes | `toggleVoiceNote`, `playVoiceNote` | `MainActivity` owns recorder/player and local file selection | No dedicated automated audio lifecycle test was found; permission, focus, interruption and playback need physical-device coverage |
| Live group voice | `startLiveVoice`, `endLiveVoice` | `MainActivity` microphone permission; `MeshForegroundService` / `MeshLiveVoiceCall` | No dedicated automated audio-call test was found; multi-phone audio, backgrounding and reconnect remain physical-device gates |
| Wi-Fi Direct | `createWifiDirectGroup`, `discoverWifiDirect`, `connectWifiDirect` | `MainActivity` adapter → `MeshForegroundService` → `MeshWifiDirectTransport` | No focused Wi-Fi Direct unit test file was found; Android permission, discovery, connection and recovery need device tests |
| Wi-Fi Aware | `resumeWifiAware` | `MainActivity` adapter → `MeshForegroundService` → optional `MeshWifiAwareTransport` | No focused Wi-Fi Aware unit test file was found; support, availability changes and coexistence need device tests |
| Local offline wallet | `getOfflineWalletAddress`, `copyOfflineWalletAddress`, `sendOfflinePayment`, `retryPendingOfflinePayments` | `MainActivity` local clipboard/read actions; service sends signed vouchers; `MeshOfflineWalletStore` retains wallet evidence | `MeshOfflineWalletStoreTest`, `MeshOfflineWalletEnvelopeTest`; still provisional local units, not globally settled money |
| Optional online integration switch | `setOnlineIntegrationsEnabled` | `MainActivity` user preference; wallet connection remains disabled unless explicitly enabled | No dedicated preference test found; default-off, persistence and disable behavior need a focused test |
| Solana Devnet wallet | `connectWallet`, `sendSolanaDevnetTransfer`, `disconnectWallet` | `MainActivity` Mobile Wallet Adapter flow; encrypted `MeshWalletAuthorizationStore`; Devnet transfer implementation | `MeshWalletAuthorizationStoreTest`, `SolanaDevnetTransferTest`; real-wallet approval/restore behavior still needs device validation |

### Automated preservation guard

**Default-branch status at the PR #36 base:** `scripts/check-bridge-contract.mjs` is not present on `main` at `0fe421254c8b8e60ec0b03f064d5309bea278079`, and the default-branch workflow does not yet enforce this check. Do not assume accidental bridge removals are currently blocked on `main`.

**Draft PR #29 only (not yet merged):** head `c41e48dfbc89c63619776baf1ba6c4503bee7259` adds `scripts/check-bridge-contract.mjs`, which stores the reviewed 31-method contract and checks that Kotlin, TypeScript and active UI command references remain aligned. Its Node 22 CI job runs the check before typecheck. Exact-head workflow run #237 completed successfully on 2026-11-28: the bridge guard, Node 22 typecheck and production build, Android debug APK assembly/signature verification, and 197 focused API 36 instrumentation tests all passed. The full Android instrumentation suite remains a separate manual workflow_dispatch option and was not run.

The guard protects the bridge surface against accidental deletion or rename; it does **not** prove that an action's full runtime behavior works. Feature-level tests and physical-device evidence remain mandatory. The guard should be considered part of the default-branch controls only after PR #29 is accepted and merged.

## Execution sequence

### FP-00 — Freeze the capability baseline

- [x] Record implemented, partial, prototype and planned capabilities in the roadmap and this contract.
- [ ] Add/confirm a capability-to-screen-to-bridge-to-native-owner-to-store-to-test mapping for every exposed user action.
- [ ] Capture baseline behavior with focused characterization tests before moving each area.
- [ ] Record unsupported hardware, permissions and external-service conditions as capability-specific degraded states.

### FP-01 — Stabilize boundaries without changing behavior
### First behavior-preserving code slice — dashboard runtime status

- [ ] Extract the JSON snapshot builder from `MainActivity` into `MeshRuntimeStatusSerializer`; the activity should continue gathering the same service/store values and calling the existing bridge method.
- [ ] Preserve all existing JSON field names, explicit-null behavior, false/zero values and empty-array behavior.
- [ ] Add focused tests for the complete payload and unavailable optional capabilities.
- [ ] Add the new test to the focused API 36 instrumentation target.
- [ ] Accept this slice only after the bridge guard, Android build and focused instrumentation test pass on the exact branch head.

**Status at the PR #36 `main` baseline:** the extraction and its focused tests are not present on `main`; `MainActivity.runtimeStatusJson()` remains the implementation. The serializer, tests and bridge guard are present only on Draft PR #29 (head `c41e48dfbc89c63619776baf1ba6c4503bee7259`). Exact-head run #237 subsequently completed successfully: 197 focused API 36 tests passed, alongside the bridge guard, Node 22 checks, APK build and signature verification. The full Android suite was not run. Keep the checklist open because these files remain on Draft PR #29 and are not yet part of main.

This slice deliberately does not move radio, messaging, wallet, file or voice ownership yet. It creates a tested boundary around the dashboard status contract before larger extractions.


- [ ] Document bridge commands and response/error contracts; add contract tests.
- [ ] Introduce narrow Kotlin interfaces/facades around existing service responsibilities.
- [ ] Keep the existing foreground-service entry points as a compatibility facade while internal owners are extracted.
- [ ] Move one responsibility at a time; no broad service rewrite or mass file move.
- [ ] Keep UI routes and controls unchanged unless an independently reviewed UX task requires a change.

### FP-02 — Extract transport supervision

- [ ] Separate transport lifecycle/availability from routing and product logic.
- [ ] Preserve BLE, Wi-Fi Direct and Wi-Fi Aware as independent paths.
- [ ] Add Wi-Fi Aware availability-change recovery and permission/lifecycle tests.
- [ ] Add adaptive BLE scan/advertise policy only with measured behavior and a safe active-connection mode.
- [ ] Verify fallback and reconnect on real devices before changing default transport policy.

### FP-03 — Strengthen durable messaging and security

- [ ] Integrate message creation, recipient snapshot and outbox persistence atomically where required.
- [ ] Make membership synchronization resumable across process death.
- [ ] Integrate validated receipt frames with router, durable stores and UI delivery states.
- [ ] Design device enrollment, verification, revocation and secure group-key lifecycle.
- [ ] Evaluate an audited session protocol and prove Android integration before adoption; do not ship custom cryptography as a shortcut.

### FP-04 — Preserve and complete product capabilities

- [ ] Keep file publishing, resumable transfer, voice, VPN, DNS and portal functional while their owners are extracted.
- [ ] Add explicit private/group access controls and revoke/unpublish semantics to the library.
- [ ] Keep wallet and Devnet functionality isolated from offline networking and guard all real-fund behavior.
- [ ] Retain governance, reputation, marketplace, Internet sharing, contracts/accounting and arbitration in the roadmap with prerequisites and honest status.

### FP-05 — Data migration and release proof

- [ ] Add versioned migration tests from representative prior databases and interrupted migration states.
- [ ] Run end-to-end regression scenarios for all baseline capabilities, including negative/permission-denied paths.
- [ ] Verify physical-device radio, background, battery, audio and file interruption behavior.
- [ ] Publish a capability-diff report showing no unexplained loss, no newly misleading UI claims and all remaining gates.
- [ ] Keep production/security/legal/store release gates separate from code-complete status.

## Definition of done for any refactor slice

A slice is accepted only when all applicable items are true:

- The capability and its current status are identified in the inventory.
- Existing user-visible behavior and bridge contract are preserved, or a separately approved product change is documented.
- Automated characterization and regression tests pass for the moved behavior.
- Data/schema changes have upgrade, rollback/recovery and no-data-loss evidence.
- Protocol changes remain compatible or have an explicit, tested version-negotiation plan.
- Unavailable permissions, radios and online dependencies fail locally without disabling unrelated capabilities.
- UI copy accurately describes what is implemented and what remains partial.
- Physical-device and external-audit requirements are marked pending until evidence exists.

**Merge rule:** no feature-preserving refactor slice is complete merely because it compiles. Keep PRs in Draft while any required code-level acceptance check is missing; keep physical-device, independent-audit and release gates explicitly open until evidence is recorded.
