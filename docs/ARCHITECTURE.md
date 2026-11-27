# MeshNet architecture and repository map

## System boundaries

MeshNet is an Android-first, offline-first peer-to-peer prototype. The browser dashboard is a local control surface; it is not a network service and does not simulate radio state.

```text
React/Vite dashboard (src/)
        |
        | local JavaScript bridge
        v
MainActivity + WebViewAssetLoader
        |
        | bound foreground-service API
        v
MeshForegroundService
  |-- MeshPeerRegistry       live, established transport connections
  |-- MeshRouteRegistry      bounded, authenticated route observations
  |-- MeshBleTransport       BLE/GATT
  |-- MeshWifiDirectTransport user-started Wi-Fi Direct
  |-- MeshWifiAwareTransport optional Wi-Fi Aware
  |-- MeshRouter              encrypted frames, relay and receipts
  |-- MeshQueueStore          durable outbox and replay protection
  |-- MeshFileTransfer        bounded resumable file transfer
  |-- MeshLiveVoiceCall       opt-in ephemeral group audio
  |-- wallet stores/transfers local-unit journal and optional Devnet MWA
        |
        v
Android platform APIs, SQLite, Keystore and local radio sockets
```

The APK serves Vite output from its own assets through AndroidX WebViewAssetLoader. The dashboard must not require a hosted web page, remote source endpoint, CDN font, avatar service, or cloud messaging backend for local mesh operation.

## Repository layout

| Path | Responsibility |
|---|---|
| `src/App.tsx` | Dashboard composition and native runtime polling |
| `src/components/PhoneWorkspace.tsx` | User-facing native service controls |
| `src/components/MeshSimulator.tsx` | Direct radio links and observed multi-hop frame routes |
| `src/components/SystemInfo.tsx` | Native service and transport event history |
| `src/components/CodeExporter.tsx` | Read-only local source browser populated at Vite build time |
| `mobile/app/src/main/java/com/meshnet/app/` | Android activity, foreground service, transports, protocol and storage |
| `mobile/app/src/androidTest/` | Android instrumentation and storage/protocol tests |
| `mobile/app/src/main/AndroidManifest.xml` | Android permissions, components and foreground-service declarations |
| `mobile/app/build.gradle.kts` | Android build, dashboard bundling and release-signing gate |
| `solana/programs/meshnet-escrow/` | Anchor escrow program source; not production-deployed or Android-integrated |
| `test/fixtures/` | Legacy, explicitly non-production simulation fixtures |
| `docs/` | Architecture and maintenance documentation |
| `.github/workflows/` | Manual-only validation workflow |

## Live peer lifecycle

A peer is inserted into `MeshPeerRegistry` only after a transport establishes its data socket or GATT connection callback fires. Discovery results, Wi-Fi P2P scan results, Aware service advertisements, and log text do not count as connected peers.

The peer registry key combines transport and remote address so the same IP/address observed on two transports remains distinct. Disconnect callbacks remove only the matching transport peer. Stopping the mesh clears the registry.

The router separately reports a route observation only after a frame is decoded and authenticated, and only when the current node is not already in the frame's hop history. The observed path is the group-key-authenticated hop list plus this receiving node. `MeshRouteRegistry` validates node IDs and loop-free paths, caps history at 64 paths, and expires observations after 30 minutes. The dashboard renders those observed frame paths in a separate multi-hop graph. It does not merge them with the direct-link graph or claim that a previously observed hop is currently online.

Current topology sources:
- BLE/GATT: the remote MeshNet service and writable characteristic must be discovered before the client-side peer is registered; server-side peers are registered on accepted GATT connections.
- Wi-Fi Direct: accepted/connected TCP data sockets after P2P group negotiation.
- Wi-Fi Aware: accepted/connected TCP data sockets after Aware network negotiation.

These are direct links to the remote socket endpoint. They are not proof of end-to-end reachability, route quality, or a stable device identity. A transport may reconnect with a different IP address.

## Source browser scope

The source browser imports text files from the repository checkout during Vite build. It includes frontend, Android, instrumentation tests, Rust/Anchor, Gradle, project configuration, workflow, scripts, and Markdown documentation. It excludes generated outputs, dependency caches, IDE state, and binary assets. Paths are displayed relative to the repository root.

The viewer is a read-only snapshot of the source at build time. It is not an editor, does not fetch GitHub at runtime, and does not replace the compiler or tests. If new source extensions are introduced, update the allowlist in `src/components/CodeExporter.tsx`.

## Engineering rules

1. Keep platform I/O in native Android classes; React components should call only the narrow bridge exposed by `MainActivity`.
2. Keep peer membership event-driven. Never create a peer from a discovery result or parse arbitrary human-readable log strings to infer connection state.
3. Keep transport identity explicit. A connection on BLE, Wi-Fi Direct, and Wi-Fi Aware must not overwrite another transport's connection record.
4. Keep discovery separate from connection. Wi-Fi Direct remains user-controlled; Wi-Fi Aware remains optional and hardware-dependent.
5. Keep persistent data bounded and private. Queue, replay, transfer, and wallet stores must preserve their existing quotas and validation.
6. Keep experimental code clearly separated from production runtime. Simulation fixtures are not Android transports, cryptographic implementations, or acceptance tests.
7. Do not claim release readiness from static review. Builds, instrumentation, radio interoperability, security review, wallet behavior, and physical-device checks are separate validation gates.

## Known boundaries

- The direct graph shows established transport links; the separate route graph shows only authenticated paths that actually reached this node. Neither is a complete network map or a live route-quality calculation.
- Mesh forwarding is still broadcast-style, not destination-aware or link-quality-aware routing.
- Group AES-GCM is not Signal Protocol and does not provide per-device identity or forward secrecy.
- Offline local-unit vouchers are not money, a spendable ledger, or a settlement system.
- The Anchor escrow source is not deployed, audited, or integrated into the Android wallet flow.
- Physical BLE/Wi-Fi, file interruption, live-audio quality, and OEM background behavior require multi-device validation.

See [the Android guide](../mobile/README.md), [release test plan](../mobile/RELEASE_TEST_PLAN.md), and [security and escrow notes](../mobile/SECURITY_AND_ESCROW.md) before release work.
