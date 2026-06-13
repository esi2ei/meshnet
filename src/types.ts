export enum MessageType {
  TEXT = 'TEXT',
  IMAGE = 'IMAGE',
  FILE = 'FILE',
  VOICE = 'VOICE',
}

export enum MessageStatus {
  PENDING = 'PENDING',
  DELIVERED = 'DELIVERED',
  ENCRYPTED_QUEUE = 'ENCRYPTED_QUEUE',
}

export interface MeshNode {
  id: string;
  username: string;
  avatar: string;
  x: number; // coordinate for mesh visualizer
  y: number; // coordinate for mesh visualizer
  isSharingInternet: boolean;
  pricePerMB: number; // in USDC
  balanceUSDC: number;
  hasInternet: boolean; // true if connected directly to internet node
  batteryPercentage: number;
  isOnline: boolean;
  publicKey: string;
  preKeyId: number;
  ephemeralKey: string;
}

export interface MeshMessage {
  id: string;
  senderId: string;
  recipientId: string;
  senderUsername: string;
  content: string;
  timestamp: string;
  status: MessageStatus;
  type: MessageType;
  fileName?: string;
  fileSize?: string;
  audioUrl?: string;
  voiceDuration?: number; // seconds
  hops: string[]; // Node IDs through which it hopped
  encryptedBytes?: string;
  ratchetStep?: number;
}

export interface EscrowSession {
  id: string;
  buyerId: string;
  sharerId: string;
  ratePerMB: number;
  dataMeteredMB: number;
  escrowBalanceUSDC: number;
  status: 'PENDING' | 'ACTIVE' | 'SETTLED' | 'REFUNDED';
  txHash: string;
}

export interface BlockchainLog {
  id: string;
  timestamp: string;
  event: string;
  details: string;
  txHash: string;
  network: 'Solana' | 'Polygon';
}

export interface CodeFile {
  name: string;
  filepath: string;
  language: 'dart' | 'kotlin' | 'swift' | 'yaml' | 'markdown';
  content: string;
}
