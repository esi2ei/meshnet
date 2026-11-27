# MeshNet offline mesh network: capabilities and completion gates

## What "offline" means in the current build

MeshNet is an offline-first, device-to-device application. Its Android data plane can use BLE/GATT, Wi-Fi Direct and Wi-Fi Aware to carry MeshNet frames between nearby devices. The router supports bounded multi-hop forwarding, destination-aware delivery, receipts and a durable message outbox. The application also has local file and voice features.

This is **not yet a general-purpose offline Internet**. The current transports carry MeshNet application frames; they do not create an IP network for arbitrary apps. There is no production VPN/TUN data plane, IP address allocation, DNS resolver, Internet gateway election, NAT/uplink sharing, generic TCP/UDP forwarding, or offline web content catalogue. When every node has no upstream connection, the global Internet is unreachable. The network can only deliver data and services that participating devices already hold or provide locally.

Do not describe the current release as providing Internet access without an Internet uplink.

## Implemented foundation

- BLE/GATT and Android Wi-Fi Direct / Wi-Fi Aware transports.
- Versioned, authenticated MeshNet frames with TTL and route metadata.
- Bounded reverse-route learning and peer-targeted delivery.
- Durable outbox and message receipts.
- File-transfer and voice paths exposed through the Android bridge.
- Live peer and observed-route telemetry in the local dashboard.

The above describes code paths, not a claim of production-grade security or multi-device interoperability. Physical-device tests across different Android vendors and radio combinations are still required.

## Blocking engineering gaps

### 1. Device identity and session security

The current AES-GCM design derives one group key from a shared passphrase. Every participant with that secret can decrypt group traffic and the protocol does not provide independently authenticated device identities, per-device revocation, ratcheting or forward secrecy. A group member can therefore impersonate another claimed origin. The UI must not label this as Signal-grade end-to-end encryption.

Before production messaging:
- establish a reviewed device identity and explicit trust/onboarding model;
- use a maintained, audited session protocol rather than custom cryptographic primitives;
- bind origin identity to verified device keys;
- support key rotation, device removal and replay protection;
- prove forward secrecy and recovery behavior with protocol tests.

### 2. General IP connectivity

A real Internet-like mesh requires a distinct packet plane, not just larger chat messages:
- Android VpnService/TUN integration with explicit user consent;
- stable node addressing, MTU-aware packet fragmentation/reassembly and authenticated packet envelopes;
- multi-hop IP routing, route expiry, congestion/back-pressure and loop prevention;
- DNS strategy for local names and optional upstream DNS;
- opt-in gateway advertisement/election, uplink policy, NAT/forwarding, metering and abuse controls;
- firewall boundaries so a gateway never silently exposes a phone or its LAN.

A node with no uplink can still provide a **local intranet** when content and services are replicated locally. It cannot provide access to arbitrary public Internet hosts.

### 3. Offline content and services

For a useful offline intranet, add:
- a local service directory with signed provider identity and expiry;
- content-addressed objects with integrity verification;
- storage quotas, eviction policy and user-controlled replication;
- resumable, chunked multi-hop transfer with retry and integrity checks;
- a local HTTP portal that exposes only locally available content;
- conflict-aware synchronization for mutable data.

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
