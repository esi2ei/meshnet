# MeshNet offline mesh network: capabilities and completion gates

## What "offline" means in the current build

MeshNet is an offline-first, device-to-device application. Its Android data plane can use BLE/GATT, Wi-Fi Direct and Wi-Fi Aware to carry MeshNet frames between nearby devices. The router supports bounded multi-hop forwarding, destination-aware delivery, receipts and a durable message outbox. The application also has local file and voice features.

MeshNet now includes an **opt-in local IPv4 intranet** in addition to its application-frame transports. The Android app uses VpnService/TUN, deterministic per-device addresses in 100.64.0.0/10, bounded IPv4 fragmentation/reassembly over signed ephemeral MeshNet frames, a local DNS resolver for node-fingerprint mesh names, and a small HTTP portal that exposes only files explicitly published to the offline library.

This is still **not public Internet access**. The VPN captures only 100.64.0.0/10; ordinary Internet traffic is not redirected through the mesh. When no node has an upstream connection, public Internet hosts remain unreachable. Gateway election, NAT/uplink sharing, IPv6, and a general-purpose service directory remain out of scope.

## Implemented foundation

- BLE/GATT and Android Wi-Fi Direct / Wi-Fi Aware transports.
- Versioned, authenticated MeshNet frames with TTL and route metadata.
- Bounded reverse-route learning and peer-targeted delivery.
- Durable outbox and message receipts.
- File-transfer and voice paths exposed through the Android bridge.
- Live peer and observed-route telemetry in the local dashboard.
- An opt-in Android VPN/TUN data plane for the private 100.64.0.0/10 IPv4 overlay.
- Deterministic device addresses, signed ephemeral multi-hop packet delivery, and bounded IPv4 fragment reassembly.
- Mesh DNS for node-fingerprint .mesh names, with a reserved virtual DNS service address.
- A bounded HTTP portal bound to the local overlay IP; it serves only explicitly published content-addressed library files.

The above describes code paths, not a claim of production-grade security or multi-device interoperability. Physical-device tests across different Android vendors and radio combinations are still required.

## Blocking engineering gaps

### 1. Device identity and session security

The current AES-GCM design still derives one group key from a shared passphrase, so every group member can decrypt group traffic. Protocol v6 now adds a stable Android Keystore P-256 signing identity per installation: the public-key fingerprint is the node ID, and each origin signs the immutable payload digest and message identity. Relays preserve that origin signature while re-encrypting route metadata. This prevents a group member from forging another enrolled node's origin, but it does not provide explicit device approval/revocation, a ratchet, forward secrecy or protection from a compromised group key. Do not describe this as Signal-grade end-to-end encryption.

Before production messaging:
- add an explicit trust/onboarding model with QR/fingerprint verification and device approval;
- use a maintained, audited session protocol rather than custom cryptographic primitives;
- support device revocation, group-key rotation, ratcheting and forward secrecy;
- prove recovery, multi-device replacement and downgrade resistance with protocol tests;
- obtain an independent cryptographic review of the custom frame-signature integration.

### 2. Remaining IP-network and gateway work

The first local IPv4 overlay is implemented in the Android branch:
- users explicitly grant Android VPN permission before VpnService establishes the TUN;
- each device gets a deterministic 100.64.0.0/10 address derived from its signed node identity;
- IPv4 packets are fragmented into bounded, signed ephemeral frames, routed to the destination node, and reassembled before delivery to its TUN;
- the virtual DNS endpoint resolves node-fingerprint .mesh names using current mesh address announcements;
- non-mesh DNS lookups are forwarded through a protected UDP socket to the underlying network's DNS server when an uplink exists; with no uplink they return SERVFAIL;
- public application traffic is deliberately excluded from the VPN route and continues over the device's ordinary network.

This is a best-effort IPv4 overlay, not a fully validated Internet-like network. DNS forwarding is a bounded UDP path; large DNS replies that require TCP fallback and private-DNS-over-TLS upstreams are not yet handled by the mesh resolver. Physical-device multi-hop testing, route expiry, congestion/back-pressure, loss/retry behavior, IPv6, and interoperability measurements remain outstanding. Gateway advertisement/election, explicit user-controlled uplink sharing, NAT/forwarding, metering and abuse controls are not implemented.

### 3. Offline content and services

The Android app now includes a first usable offline-library slice:
- users explicitly publish files into a private, content-addressed local library;
- SHA-256 IDs and metadata advertisements let nearby mesh members discover published files;
- a member can request an advertised item; the existing resumable chunk-transfer protocol carries the bytes;
- completed downloads are promoted into the library only after the requested content ID, size and SHA-256 digest match;
- local storage is capped at 100 MiB, each item at 10 MiB, local entries at 32, and remote advertisements expire after seven days;
- files are not shared automatically: publication is an explicit user action.

The HTTP portal now serves files already present in the local library through the overlay address and DNS name. It is intentionally read-only and does not expose arbitrary local files or a general service directory. Remaining content work includes signed service/provider metadata, conflict-aware synchronization, efficient unicast chunk delivery and physical multi-hop transfer validation.

### 4. Operational reliability

- Test at least three physical Android devices in a multi-hop topology, including relay loss and reconnection.
- Measure goodput, latency, radio duty cycle, battery drain and queue growth.
- Exercise malformed frames, slow peers, simultaneous connections, partial reads and route churn.
- Verify permissions, foreground-service lifecycle, background restrictions and OEM-specific Wi-Fi behavior.
- Run release signing, dependency/security review and an independent cryptographic audit before production distribution.

## Acceptance criteria for an offline intranet milestone

1. Three or more devices can discover and form a multi-hop network without a router or mobile-data connection.
2. A node can publish a local service/content object and another node can discover, verify, fetch and cache it across at least one relay.
3. Transfers resume after a relay disconnects and do not corrupt or duplicate the delivered object.
4. Routing, connection count, memory, queues and radio retries remain bounded under malicious or failing peers.
5. UI clearly separates local-only services from optional uplink/gateway services.
6. Instrumented physical-device evidence is recorded for the supported Android versions and chipsets.

## Current release boundary

Until the gaps above are implemented and validated, MeshNet should be presented as an offline-first mesh messaging and local file/voice transport prototype, not as a replacement for public Internet, a production secure messenger, or a settled offline payment network.
