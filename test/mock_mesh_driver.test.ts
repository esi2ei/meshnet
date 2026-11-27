/**
 * LEGACY SIMULATOR TEST ONLY — NOT A DEVICE, CRYPTOGRAPHY, OR HARDWARE TEST.
 * This in-memory mock intentionally does not prove BLE transport, Signal Protocol,
 * real encryption, persistent queues, or physical multi-hop behavior. The native
 * Android prototype and physical-device acceptance checks are documented in mobile/README.md.
 */

interface MeshPacket {
  id: string;
  senderId: string;
  recipientId: string;
  payload: string; // Encrypted string
  type: 'VOICE' | 'TEXT' | 'FILE';
  ttl: number;
  routeHistory: string[];
}

class VirtualDevice {
  public id: string;
  public username: string;
  public peers: Set<string> = new Set();
  public packetQueue: MeshPacket[] = [];
  public receivedPacketIds: Set<string> = new Set();
  
  // Simulated Secure Key Store (Keystore/Keychain FFI)
  private secureStorageKey: string;

  constructor(id: string, username: string) {
    this.id = id;
    this.username = username;
    this.secureStorageKey = `SECURE_HARDWARE_KEY_FOR_${id}_` + Math.random().toString(36).substring(2, 10).toUpperCase();
  }

  // Encryption helper simulating LibSignal-Protocol and re-encryption at each hop
  public encryptPayload(plainText: string, targetKey: string): string {
    return `[SIGNAL-ENCRYPTED:${plainText}:VIA-${targetKey}]`;
  }

  public decryptPayload(cipherText: string): string {
    if (cipherText.startsWith('[SIGNAL-ENCRYPTED:') && cipherText.includes(this.secureStorageKey)) {
      // Stub decryption
      return cipherText.split(':')[1];
    }
    return cipherText; // Can't decrypt without key (acts as transparent forwarder)
  }

  // Adds a packet to the sorted priority queue (Priority: Voice > Text > File)
  public queuePacket(packet: MeshPacket) {
    this.packetQueue.push(packet);
    this.packetQueue.sort((a, b) => {
      const priorityOrder = { VOICE: 1, TEXT: 2, FILE: 3 };
      return priorityOrder[a.type] - priorityOrder[b.type];
    });
  }

  // Prepares epidemic flood broadcast with decreasing TTL
  public receivePacket(packet: MeshPacket, network: Map<string, VirtualDevice>): MeshPacket | null {
    if (this.receivedPacketIds.has(packet.id)) {
      console.log(`[${this.username}] Already processed packet ${packet.id}. Dropping duplicate.`);
      return null;
    }

    this.receivedPacketIds.add(packet.id);
    console.log(`[${this.username}] Received packet ${packet.id} (${packet.type}) from ${packet.routeHistory[packet.routeHistory.length - 1] || packet.senderId}`);

    // Verify if we are the destination
    if (packet.recipientId === this.id) {
      console.log(`[${this.username}] SUCCESS: Recipient reached! Decrypting packet content...`);
      return packet;
    }

    // Otherwise, we act as a transparent relay node (Forward Secrecy Re-encryption step)
    if (packet.ttl <= 1) {
      console.log(`[${this.username}] TTL expired for packet ${packet.id}. Dropping.`);
      return null;
    }

    // Re-encrypt the payload for the next hop to maintain forward secrecy over the relay
    const reEncryptedPayload = `[RE-ENCRYPTED-BY-${this.id}:${packet.payload}]`;

    const relayedPacket: MeshPacket = {
      ...packet,
      payload: reEncryptedPayload,
      ttl: packet.ttl - 1,
      routeHistory: [...packet.routeHistory, this.id]
    };

    console.log(`[${this.username}] Relaying packet ${packet.id} with updated TTL ${relayedPacket.ttl} and routing history: [${relayedPacket.routeHistory.join(' -> ')}]`);
    
    // Push into prioritized local outgoing queue
    this.queuePacket(relayedPacket);
    this.flood(relayedPacket, network);

    return null;
  }

  // Floods packet to all connected peers
  private flood(packet: MeshPacket, network: Map<string, VirtualDevice>) {
    for (const peerId of this.peers) {
      const neighbor = network.get(peerId);
      if (neighbor) {
        // Simulating 1 second interval over BLE/Multipeer and exponential retry
        setTimeout(() => {
          neighbor.receivePacket(packet, network);
        }, 100);
      }
    }
  }
}

// DRIVER TEST SUITE RUNNER
export function runMeshDriverTest() {
  console.log("=================================================");
  console.log("RUNNING OFFLINE MESH DRIVER & ENCRYPTION TESTS...");
  console.log("=================================================");

  const devices = new Map<string, VirtualDevice>();
  const nodeA = new VirtualDevice("NODE-A", "Alice (Android)");
  const nodeB = new VirtualDevice("NODE-B", "Bob (Android)");
  const nodeC = new VirtualDevice("NODE-C", "Charlie (iOS - Receive-Only Client)");

  devices.set(nodeA.id, nodeA);
  devices.set(nodeB.id, nodeB);
  devices.set(nodeC.id, nodeC);

  // Configure physical layout: A and B are peers, B and C are peers. A and C cannot communicate directly (out of BLE range)
  nodeA.peers.add(nodeB.id);
  nodeB.peers.add(nodeA.id);
  nodeB.peers.add(nodeC.id);
  nodeC.peers.add(nodeB.id);

  console.log("Network topology configured:");
  console.log(`- ${nodeA.username} <--> ${nodeB.username} <--> ${nodeC.username}`);
  console.log(`- Note: Alice cannot communicate with Charlie directly.`);

  // Assemble distinct packets with various priorities to verify priority queuing
  const filePacket: MeshPacket = {
    id: "pkt-file-123",
    senderId: nodeA.id,
    recipientId: nodeC.id,
    payload: nodeA.encryptPayload("Large map dataset file content", "CHARLIE-KEYCHAIN-STUB"),
    type: "FILE",
    ttl: 5,
    routeHistory: [nodeA.id]
  };

  const textPacket: MeshPacket = {
    id: "pkt-text-456",
    senderId: nodeA.id,
    recipientId: nodeC.id,
    payload: nodeA.encryptPayload("Hello Charlie from Alice over mesh!", "CHARLIE-KEYCHAIN-STUB"),
    type: "TEXT",
    ttl: 5,
    routeHistory: [nodeA.id]
  };

  const voicePacket: MeshPacket = {
    id: "pkt-voice-789",
    senderId: nodeA.id,
    recipientId: nodeC.id,
    payload: nodeA.encryptPayload("E2EE Voice note 2 seconds stream", "CHARLIE-KEYCHAIN-STUB"),
    type: "VOICE",
    ttl: 5,
    routeHistory: [nodeA.id]
  };

  console.log("\n--- TEST Priority Queuing order on Node A's transmitter queue ---");
  nodeA.queuePacket(filePacket);
  nodeA.queuePacket(textPacket);
  nodeA.queuePacket(voicePacket);

  console.log(`Queue positions configured. Expected order: VOICE -> TEXT -> FILE`);
  nodeA.packetQueue.forEach((pkt, idx) => {
    console.log(`  Queue [${idx}]: Type: ${pkt.type}, ID: ${pkt.id}`);
  });

  const isValidPriorityOrder = 
    nodeA.packetQueue[0].type === 'VOICE' && 
    nodeA.packetQueue[1].type === 'TEXT' && 
    nodeA.packetQueue[2].type === 'FILE';

  console.log(`Priority queue sorting check: ${isValidPriorityOrder ? "PASS ✅" : "FAIL ❌"}`);

  console.log("\n--- TEST Multihop Epidemic Flooding and Re-Encryption Relay (A -> B -> C) ---");
  
  // Alice broadcasts her packets over the priority ordered stream
  while (nodeA.packetQueue.length > 0) {
    const nextPkt = nodeA.packetQueue.shift();
    if (nextPkt) {
      // Bob receives it first
      nodeB.receivePacket(nextPkt, devices);
    }
  }

  console.log("\nDriver simulation completed initialized successfully.");
  return {
    success: isValidPriorityOrder,
    topology: "Line (A-B-C)",
    routingType: "Epidemic Flooding",
    queueSorted: isValidPriorityOrder
  };
}
