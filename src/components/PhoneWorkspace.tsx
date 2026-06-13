import React, { useState, useEffect } from 'react';
import { MeshNode, MeshMessage, MessageType, MessageStatus, EscrowSession, BlockchainLog } from '../types';
import {
  ShieldCheck, Wifi, WifiOff, Send, Mic, Paperclip, Wallet, Radio, Map,
  User, Image, Clock, ArrowUpRight, Lock, Key, Copy, Check, Info, AlertTriangle, Play, HelpCircle, ToggleLeft, ToggleRight,
  Battery, BatteryCharging, Zap, Cpu,
  MessageSquare, Network, Globe, Search, Share2, Sliders, SlidersHorizontal, Plus, ChevronRight, Fingerprint, QrCode, Phone, MoreVertical, ChevronDown,
  Activity
} from 'lucide-react';

interface PhoneWorkspaceProps {
  nodes: MeshNode[];
  onNodesUpdate: (nodes: MeshNode[]) => void;
  messages: MeshMessage[];
  onMessagesUpdate: (messages: MeshMessage[]) => void;
  activeNodeId: string;
  isSimulatingHop: boolean;
  setIsSimulatingHop: (val: boolean) => void;
  simulatedPath: string[];
  setSimulatedPath: (path: string[]) => void;
  currentHopNodeId: string | null;
  setCurrentHopNodeId: (id: string | null) => void;
  blockchainLogs: BlockchainLog[];
  onAddBlockchainLog: (log: BlockchainLog) => void;
}

export default function PhoneWorkspace({
  nodes,
  onNodesUpdate,
  messages,
  onMessagesUpdate,
  activeNodeId,
  isSimulatingHop,
  setIsSimulatingHop,
  simulatedPath,
  setSimulatedPath,
  currentHopNodeId,
  setCurrentHopNodeId,
  blockchainLogs,
  onAddBlockchainLog,
}: PhoneWorkspaceProps) {
  const [activeTab, setActiveTab] = useState<'mesh' | 'chats' | 'wallet' | 'settings'>('mesh');
  const [selectedPeerId, setSelectedPeerId] = useState<string | null>(null);
  const [typewrittenMessage, setTypewrittenMessage] = useState('');
  
  // Settings Tab states to mirror screenshots exactly
  const [settingsToken, setSettingsToken] = useState<'USDC' | 'SOL' | 'MATIC' | 'USDT'>('USDC');
  const [bleEnabled, setBleEnabled] = useState(true);
  const [nanEnabled, setNanEnabled] = useState(true);
  const [directEnabled, setDirectEnabled] = useState(true);
  const [copiedAddress, setCopiedAddress] = useState(false);
  const [showDiagnostics, setShowDiagnostics] = useState(false);
  const [showDisclaimers, setShowDisclaimers] = useState(false);
  const [recentSettlement, setRecentSettlement] = useState<{
    id: string;
    buyerName: string;
    hostName: string;
    dataDownloaded: number;
    ratePerMB: number;
    spentUSDC: number;
    refundUSDC: number;
    txHash: string;
  } | null>(null);
  
  // Audio recording simulation state
  const [isRecordingVoice, setIsRecordingVoice] = useState(false);
  const [recordingDuration, setRecordingDuration] = useState(0);
  const [voiceIntervalId, setVoiceIntervalId] = useState<any>(null);

  // File upload simulation state
  const [fileProgress, setFileProgress] = useState<number | null>(null);

  // Wallet backup seed phrase display
  const [mnemonicCreated, setMnemonicCreated] = useState(false);
  const [copiedMnemonic, setCopiedMnemonic] = useState(false);

  // Hotspot shared connection billing tracking
  const [activeEscrow, setActiveEscrow] = useState<EscrowSession | null>(null);
  const [meteredSpeed, setMeteredSpeed] = useState<number>(0);
  const [confirmingSharer, setConfirmingSharer] = useState<MeshNode | null>(null);

  // Custom proposed tether states
  const [targetClientId, setTargetClientId] = useState<string>('');
  const [proposedRate, setProposedRate] = useState<number>(0.15);

  const activeNode = nodes.find(n => n.id === activeNodeId) || nodes[0];

  useEffect(() => {
    if (activeNode) {
      setProposedRate(activeNode.pricePerMB);
    }
  }, [activeNodeId, activeNode.pricePerMB]);

  // Fluttering current draw simulation
  const [currentMilliAmpsFlutter, setCurrentMilliAmpsFlutter] = useState(14.2);
  useEffect(() => {
    const timer = setInterval(() => {
      let baseMA = 4.8; // idle discovery advertising load
      if (activeNode.isSharingInternet) {
        baseMA += 85.0; // wifi hotspot active
      }
      if (activeEscrow) {
        baseMA += 125.0; // active proxy payload streaming
      }
      const flutter = (Math.random() - 0.5) * 5.2;
      setCurrentMilliAmpsFlutter(Math.max(1.5, parseFloat((baseMA + flutter).toFixed(1))));
    }, 1500);
    return () => clearInterval(timer);
  }, [activeNode.isSharingInternet, activeEscrow]);

  // Simulated node battery percentage starting at 78% and draining extremely slowly based on activity
  const [simulatedBatteryPercentage, setSimulatedBatteryPercentage] = useState(78.5);
  useEffect(() => {
    const drainInterval = setInterval(() => {
      let drainRate = 0.0002; // background BLE rate
      if (activeNode.isSharingInternet) drainRate += 0.0025; // hotspot rate
      if (activeEscrow) drainRate += 0.0045; // proxy streaming rate
      
      setSimulatedBatteryPercentage(prev => Math.max(1, parseFloat((prev - drainRate).toFixed(4))));
    }, 3000);
    return () => clearInterval(drainInterval);
  }, [activeNode.isSharingInternet, activeEscrow]);

  const SIMULATED_MNEMONIC = 'mesh draft nuclear typical dynamic rocket crypto solar shield secure border quantum';

  // Find all other nodes we can connect with or route through
  const peerList = nodes.filter(n => n.id !== activeNodeId);

  // Filter messages specifically belonging to the active node and the chosen peer
  const activeChatMessages = messages.filter(msg => 
    (msg.senderId === activeNodeId && msg.recipientId === selectedPeerId) || 
    (msg.senderId === selectedPeerId && msg.recipientId === activeNodeId)
  );

  // Calculate shortest routing path on mesh utilizing simple BFS
  const computeRoutingPath = (startId: string, endId: string): string[] | null => {
    const queue: string[][] = [[startId]];
    const visited = new Set<string>();

    const getNeighbors = (nodeId: string) => {
      const srcNode = nodes.find(n => n.id === nodeId);
      if (!srcNode) return [];
      return nodes.filter(other => {
        if (other.id === nodeId) return false;
        const dist = Math.hypot(srcNode.x - other.x, srcNode.y - other.y);
        return dist < 200; // linked range limit
      }).map(n => n.id);
    };

    while (queue.length > 0) {
      const path = queue.shift()!;
      const current = path[path.length - 1];

      if (current === endId) return path;

      if (!visited.has(current)) {
        visited.add(current);
        const neighbors = getNeighbors(current);
        for (const neighbor of neighbors) {
          queue.push([...path, neighbor]);
        }
      }
    }
    return null; // Partitoned
  };

  // Trigger encrypted message hopping simulation
  const handleSendMessage = (type: MessageType, fileMeta?: { name: string; size: string }) => {
    if (!selectedPeerId) return;

    let payloadText = '';
    if (type === MessageType.TEXT) {
      if (!typewrittenMessage.trim()) return;
      payloadText = typewrittenMessage;
      setTypewrittenMessage('');
    } else if (type === MessageType.VOICE) {
      payloadText = `Voice message note (${recordingDuration} seconds)`;
    } else if (type === MessageType.IMAGE) {
      payloadText = `Sent raw encrypted Photo: ${fileMeta?.name}`;
    } else if (type === MessageType.FILE) {
      payloadText = `Sent secure raw Archive File: ${fileMeta?.name}`;
    }

    const peer = nodes.find(n => n.id === selectedPeerId)!;
    const path = computeRoutingPath(activeNodeId, selectedPeerId);

    const base64EncryptedSim = `Envelope[iv:${Math.random().toString(36).substring(3, 11)}, ratChStep:${messages.length + 1}]`;

    const newMsg: MeshMessage = {
      id: `msg-${Date.now()}`,
      senderId: activeNodeId,
      recipientId: selectedPeerId,
      senderUsername: activeNode.username,
      content: payloadText,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      status: path ? MessageStatus.DELIVERED : MessageStatus.ENCRYPTED_QUEUE,
      type: type,
      fileName: fileMeta?.name,
      fileSize: fileMeta?.size,
      voiceDuration: type === MessageType.VOICE ? recordingDuration : undefined,
      hops: path || [activeNodeId],
      encryptedBytes: base64EncryptedSim,
      ratchetStep: messages.length + 1
    };

    if (path) {
      // Animate Packet Traveling on Map!
      setIsSimulatingHop(true);
      setSimulatedPath(path);
      
      let hopIndex = 0;
      setCurrentHopNodeId(path[0]);

      onAddBlockchainLog({
        id: `tx-log-${Date.now()}`,
        timestamp: new Date().toLocaleTimeString(),
        event: 'MESH_PACKET_ROUTE_INIT',
        details: `Routing packet ${newMsg.id.substring(0,6)} through ${path.length} hops: ${path.join(' -> ')}`,
        txHash: `oob-mesh-${Math.random().toString(36).substring(4, 12)}`,
        network: 'Solana'
      });

      const interval = setInterval(() => {
        hopIndex++;
        if (hopIndex < path.length) {
          setCurrentHopNodeId(path[hopIndex]);
        } else {
          clearInterval(interval);
          setIsSimulatingHop(false);
          setSimulatedPath([]);
          setCurrentHopNodeId(null);
          // Push to state
          onMessagesUpdate([...messages, newMsg]);
        }
      }, 900);
    } else {
      // Partitoned: Queued offline
      onMessagesUpdate([...messages, newMsg]);
      onAddBlockchainLog({
        id: `tx-log-${Date.now()}`,
        timestamp: new Date().toLocaleTimeString(),
        event: 'OFFLINE_QUEUE_STORE',
        details: `No active route to ${peer.username}. Saved securely to local Store-And-Forward SQLite queue.`,
        txHash: `queued-${Math.random().toString(36).substring(4, 12)}`,
        network: 'Solana'
      });
    }
  };

  // Voice recording simulation triggers
  const startRecording = () => {
    setIsRecordingVoice(true);
    setRecordingDuration(0);
    const interval = setInterval(() => {
      setRecordingDuration(d => d + 1);
    }, 1000);
    setVoiceIntervalId(interval);
  };

  const stopRecordingAndSend = () => {
    if (voiceIntervalId) {
      clearInterval(voiceIntervalId);
      setVoiceIntervalId(null);
    }
    setIsRecordingVoice(false);
    handleSendMessage(MessageType.VOICE);
  };

  // Mock File Upload Triggers
  const simulateFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setFileProgress(5);
    const interval = setInterval(() => {
      setFileProgress(p => {
        if (p === null) return null;
        if (p >= 100) {
          clearInterval(interval);
          setTimeout(() => {
            setFileProgress(null);
            const isImg = file.type.startsWith('image/');
            handleSendMessage(
              isImg ? MessageType.IMAGE : MessageType.FILE,
              { name: file.name, size: `${(file.size / 1024).toFixed(1)} KB` }
            );
          }, 400);
          return 100;
        }
        return p + 25;
      });
    }, 250);
  };

  // Internet Tethering Session Simulators - Offline-First Manual Prepaid Quota System & Host Approval Model
  const initiatePendingTetheringRequest = (sharer: MeshNode) => {
    const prepaidAmount = 2.50; // Manual Prepaid quota purchase of 2.50 USDC
    if (activeNode.balanceUSDC < prepaidAmount) {
      alert(`Insufficient funds! Please mint additional USDC first. Prepaid payment requires at least ${prepaidAmount.toFixed(2)} USDC.`);
      return;
    }

    const price = sharer.pricePerMB;
    const tx = `PrepaidSub-${Math.random().toString(36).substring(4,14).toUpperCase()}`;

    // Create a PENDING session waiting for host confirmation
    const session: EscrowSession = {
      id: `session-${Date.now()}`,
      buyerId: activeNodeId,
      sharerId: sharer.id,
      ratePerMB: price,
      dataMeteredMB: 0,
      escrowBalanceUSDC: prepaidAmount, // Used here as prepaid balance
      status: 'PENDING',
      txHash: tx
    };

    setActiveEscrow(session);

    onAddBlockchainLog({
      id: `tx-log-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      event: 'SOL_DIRECT_USDC_INITIATED',
      details: `Prepaid lease request submitted. Reserved ${prepaidAmount.toFixed(2)} USDC for host ${sharer.username}'s hotspot at ${price.toFixed(2)} USDC/MB.`,
      txHash: tx,
      network: 'Solana'
    });
  };

  const startActiveTetheringOfPending = () => {
    if (!activeEscrow || activeEscrow.status !== 'PENDING') return;
    const prepaidAmount = activeEscrow.escrowBalanceUSDC;
    
    // Check buyer's balance to proceed
    const buyerNode = nodes.find(n => n.id === activeEscrow.buyerId);
    if (!buyerNode) return;
    if (buyerNode.balanceUSDC < prepaidAmount) {
      alert("Selected buyer does not have enough Solana USDC funds to complete the transaction.");
      setActiveEscrow(null);
      return;
    }

    // Deduct prepaid fee from buyer, locking it in the Escrow Pool (we do NOT credit host yet)
    const updatedNodes = nodes.map(n => {
      if (n.id === activeEscrow.buyerId) return { ...n, balanceUSDC: n.balanceUSDC - prepaidAmount };
      return n;
    });
    onNodesUpdate(updatedNodes);

    setActiveEscrow(prev => {
      if (!prev) return null;
      return {
        ...prev,
        status: 'ACTIVE'
      };
    });

    setMeteredSpeed(5.2 + Math.random() * 9.5); // Mbps

    onAddBlockchainLog({
      id: `tx-log-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      event: 'SOL_DIRECT_USDC_PAYMENT',
      details: `Prepaid lease approved by host! Deposited ${prepaidAmount.toFixed(2)} USDC from buyer into escrow. SOCKS5 tunnel authorized.`,
      txHash: activeEscrow.txHash,
      network: 'Solana'
    });
  };

  const declinePendingTetheringRequest = () => {
    if (!activeEscrow) return;

    onAddBlockchainLog({
      id: `tx-log-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      event: 'SOL_LEASE_DECLINED',
      details: `Lease connection request declined by provider. Refunded reserved escrow amount back.`,
      txHash: activeEscrow.txHash,
      network: 'Solana'
    });

    setActiveEscrow(null);
  };

  const sendLeaseProposal = () => {
    if (!targetClientId) {
      alert("Please select a target client node to propose tethering to.");
      return;
    }

    const clientNode = nodes.find(n => n.id === targetClientId);
    if (!clientNode) return;

    const tx = `Proposal-${Math.random().toString(36).substring(4, 14).toUpperCase()}`;

    // Create a PROPOSED session waiting for client acceptance
    const session: EscrowSession = {
      id: `session-${Date.now()}`,
      buyerId: targetClientId,
      sharerId: activeNodeId,
      ratePerMB: proposedRate,
      dataMeteredMB: 0,
      escrowBalanceUSDC: 2.50, // Standard prepaid pool
      status: 'PROPOSED' as any, // Cast as 'any' to avoid restrictive compile-time enum checks if they arise
      txHash: tx
    };

    setActiveEscrow(session);

    onAddBlockchainLog({
      id: `tx-log-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      event: 'SOL_HOTSPOT_PROPOSED',
      details: `Internet Gateway ${activeNode.username} proposed a tethering connection lease to client ${clientNode.username} at ${proposedRate.toFixed(2)} USDC/MB.`,
      txHash: tx,
      network: 'Solana'
    });
  };

  const acceptProposedTetheringRequest = () => {
    if (!activeEscrow || activeEscrow.status !== 'PROPOSED') return;
    const prepaidAmount = activeEscrow.escrowBalanceUSDC;
    
    // Find client node (buyer)
    const buyerNode = nodes.find(n => n.id === activeEscrow.buyerId);
    if (!buyerNode) return;
    if (buyerNode.balanceUSDC < prepaidAmount) {
      alert(`Insufficient funds! Please mint additional USDC. Connection requires ${prepaidAmount.toFixed(2)} USDC.`);
      setActiveEscrow(null);
      return;
    }

    // Deduct prepaid fee from buyer, locking it in the Escrow Pool (we do NOT credit host yet)
    const updatedNodes = nodes.map(n => {
      if (n.id === activeEscrow.buyerId) return { ...n, balanceUSDC: n.balanceUSDC - prepaidAmount };
      return n;
    });
    onNodesUpdate(updatedNodes);

    setActiveEscrow(prev => {
      if (!prev) return null;
      return {
        ...prev,
        status: 'ACTIVE'
      };
    });

    setMeteredSpeed(6.1 + Math.random() * 8.5); // Mbps

    onAddBlockchainLog({
      id: `tx-log-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      event: 'SOL_PROPOSAL_ACCEPTED',
      details: `Proposal accepted by client! Locked ${prepaidAmount.toFixed(2)} USDC into escrow contract. SOCKS5 Wi-Fi tunnel opened.`,
      txHash: activeEscrow.txHash,
      network: 'Solana'
    });
  };

  const declineProposedTetheringRequest = () => {
    if (!activeEscrow) return;

    onAddBlockchainLog({
      id: `tx-log-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      event: 'SOL_PROPOSAL_DECLINED',
      details: `Hotspot connection proposal declined by client node ${nodes.find(n => n.id === activeEscrow.buyerId)?.username}.`,
      txHash: activeEscrow.txHash,
      network: 'Solana'
    });

    setActiveEscrow(null);
  };

  const stopTethering = () => {
    if (!activeEscrow) return;

    const spentUSDC = parseFloat(Math.min(2.50, activeEscrow.dataMeteredMB * activeEscrow.ratePerMB).toFixed(4));
    const refundAmount = parseFloat((2.50 - spentUSDC).toFixed(4));

    // Update balances
    const updatedNodes = nodes.map(n => {
      if (n.id === activeEscrow.buyerId) {
        return { ...n, balanceUSDC: parseFloat((n.balanceUSDC + refundAmount).toFixed(4)) };
      }
      if (n.id === activeEscrow.sharerId) {
        return { ...n, balanceUSDC: parseFloat((n.balanceUSDC + spentUSDC).toFixed(4)) };
      }
      return n;
    });
    onNodesUpdate(updatedNodes);

    const buyerNode = nodes.find(n => n.id === activeEscrow.buyerId);
    const hostNode = nodes.find(n => n.id === activeEscrow.sharerId);

    const tx = `Settle-${Math.random().toString(36).substring(4, 14).toUpperCase()}`;

    // Set recent settlement receipt info to display to the user in a high-fidelity modal overlay!
    setRecentSettlement({
      id: activeEscrow.id,
      buyerName: buyerNode?.username || 'Client',
      hostName: hostNode?.username || 'Gateway',
      dataDownloaded: activeEscrow.dataMeteredMB,
      ratePerMB: activeEscrow.ratePerMB,
      spentUSDC: spentUSDC,
      refundUSDC: refundAmount,
      txHash: tx
    });

    onAddBlockchainLog({
      id: `tx-log-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      event: 'SOL_ESCROW_SETTLED_REFUND',
      details: `Decentralized lease escrow settled. Paid host ${spentUSDC.toFixed(4)} USDC for ${activeEscrow.dataMeteredMB.toFixed(2)} MB of data. Refunded remaining ${refundAmount.toFixed(4)} USDC back to peer.`,
      txHash: tx,
      network: 'Solana'
    });

    setActiveEscrow(null);
    setMeteredSpeed(0);
  };

  // Synchronize dynamic price discounting if the host changes their price
  const nodesSerializedPrices = JSON.stringify(nodes.map(n => ({ id: n.id, price: n.pricePerMB })));
  const escrowId = activeEscrow?.id;
  const escrowStatus = activeEscrow?.status;
  const escrowRate = activeEscrow?.ratePerMB;
  const escrowSharerId = activeEscrow?.sharerId;

  useEffect(() => {
    if (activeEscrow && activeEscrow.status === 'ACTIVE') {
      const host = nodes.find(n => n.id === activeEscrow.sharerId);
      if (host && host.pricePerMB < activeEscrow.ratePerMB) {
        const oldPrice = activeEscrow.ratePerMB;
        const newPrice = host.pricePerMB;
        setActiveEscrow(prev => {
          if (!prev) return null;
          return {
            ...prev,
            ratePerMB: newPrice
          };
        });

        onAddBlockchainLog({
          id: `tx-log-${Date.now()}`,
          timestamp: new Date().toLocaleTimeString(),
          event: 'HOTSPOT_PRICE_DISCOUNT',
          details: `Host reduced the price of usage! Applied auto-discount: rate lowered from ${oldPrice.toFixed(2)} to ${newPrice.toFixed(2)} USDC/MB. Remaining data quota upgraded!`,
          txHash: activeEscrow.txHash,
          network: 'Solana'
        });
      }
    }
  }, [nodesSerializedPrices, escrowId, escrowStatus, escrowRate, escrowSharerId]);

  // Background metering updates for Prepaid Quota (Stops when quota depleted)
  useEffect(() => {
    let timer: any;
    if (activeEscrow && activeEscrow.status === 'ACTIVE') {
      const quotaPaid = activeEscrow.escrowBalanceUSDC;
      const dataQuotaMB = quotaPaid / activeEscrow.ratePerMB;

      timer = setInterval(() => {
        setActiveEscrow(prev => {
          if (!prev) return null;
          // increment metered bytes
          const chunk = 0.15 + Math.random() * 0.40;
          const updatedMB = prev.dataMeteredMB + chunk;

          // Automatically disconnect if quota is reached
          if (updatedMB >= dataQuotaMB) {
            clearInterval(timer);
            setTimeout(() => {
              stopTethering();
            }, 50);
            return {
              ...prev,
              dataMeteredMB: dataQuotaMB
            };
          }

          return {
            ...prev,
            dataMeteredMB: updatedMB
          };
        });
      }, 1000);
    }
    return () => clearInterval(timer);
  }, [escrowId, escrowStatus, escrowRate]);


  // Handle auto-deliveries when route recovers
  useEffect(() => {
    // Check if there are any queued offline messages between activeNode and participants,
    // and if a route now exists, deliver it automatically!
    const searchOfflineAndDeliver = () => {
      let changed = false;
      const updatedMessages = messages.map(msg => {
        if (msg.status === MessageStatus.ENCRYPTED_QUEUE) {
          const path = computeRoutingPath(msg.senderId, msg.recipientId);
          if (path) {
            changed = true;
            return {
              ...msg,
              status: MessageStatus.DELIVERED,
              hops: path
            };
          }
        }
        return msg;
      });

      if (changed) {
        onMessagesUpdate(updatedMessages);
        onAddBlockchainLog({
          id: `tx-log-${Date.now()}`,
          timestamp: new Date().toLocaleTimeString(),
          event: 'STORE_FORWARD_DELIVERY',
          details: `Mesh router repaired a path. Multi-hop queue flushed & delivered encrypted packets.`,
          txHash: `delivery-${Math.random().toString(36).substring(4,12)}`,
          network: 'Solana'
        });
      }
    };

    const checker = setTimeout(searchOfflineAndDeliver, 2000);
    return () => clearTimeout(checker);
  }, [nodes, messages]);

  const renderRadarMap = () => {
    const radarWidth = 310;
    const radarHeight = 150;
    const cx = radarWidth / 2;
    const cy = radarHeight / 2;

    return (
      <div className="relative w-full h-[160px] bg-[#0c0d12]/90 border border-zinc-900 rounded-2xl overflow-hidden flex flex-col justify-between p-2">
        {/* Absolute indicators */}
        <div className="absolute top-2 left-2 flex items-center gap-1 bg-zinc-950/80 px-1.5 py-0.5 rounded border border-zinc-900">
          <span className="w-1.5 h-1.5 bg-emerald-500 rounded-full animate-ping" />
          <span className="text-[7.5px] font-mono text-emerald-400 font-bold uppercase tracking-wider">Mesh Node Pulse Radar</span>
        </div>
        <div className="absolute top-2 right-2 text-[7.5px] font-mono text-zinc-500 bg-zinc-950/80 px-1.5 py-0.5 rounded border border-zinc-900">
          Range: 200px
        </div>

        <svg className="w-full h-full" viewBox={`0 0 ${radarWidth} ${radarHeight}`}>
          {/* Radar concentric circular guides */}
          <circle cx={cx} cy={cy} r="30" fill="none" stroke="#1f2937" strokeWidth="0.8" strokeDasharray="2 3" />
          <circle cx={cx} cy={cy} r="55" fill="none" stroke="#1f2937" strokeWidth="0.8" strokeDasharray="3 4" />
          <circle cx={cx} cy={cy} r="80" fill="none" stroke="#111827" strokeWidth="0.8" strokeDasharray="4 5" />

          {/* Diagonal coordinates grid lines */}
          <line x1={cx - 100} y1={cy} x2={cx + 100} y2={cy} stroke="#111827" strokeWidth="0.8" strokeDasharray="1 5" />
          <line x1={cx} y1={cy - 60} x2={cx} y2={cy + 60} stroke="#111827" strokeWidth="0.8" strokeDasharray="1 5" />

          {/* Pulsing Sweep effect radar line */}
          <line
            x1={cx}
            y1={cy}
            x2={cx + Math.cos((Date.now() / 2000) * 2 * Math.PI) * 80}
            y2={cy + Math.sin((Date.now() / 2000) * 2 * Math.PI) * 80}
            stroke="rgba(16, 185, 129, 0.2)"
            strokeWidth="1.2"
          />

          {/* Plot actual nodes dynamically! */}
          {nodes.map(node => {
            if (node.id === activeNodeId) return null;

            const dx = node.x - activeNode.x;
            const dy = node.y - activeNode.y;
            const dist = Math.hypot(dx, dy);

            // Scale delta coordinates
            const scale = 0.45;
            const px = cx + dx * scale;
            const py = cy + dy * scale;
            
            // Clip check
            if (Math.abs(dx * scale) > cx - 15 || Math.abs(dy * scale) > cy - 15) return null;

            const inRange = dist < 200;
            const dbm = Math.round(-42 - dist / 3);

            return (
              <g key={node.id}>
                {/* Ripple connection line to centered node */}
                {inRange && (
                  <line
                    x1={cx}
                    y1={cy}
                    x2={px}
                    y2={py}
                    stroke={node.isSharingInternet ? "#06b6d4" : "#10b981"}
                    strokeWidth="0.8"
                    strokeOpacity="0.35"
                    strokeDasharray="3 3"
                  />
                )}

                {/* Node pulsing background circle */}
                <circle
                  cx={px}
                  cy={py}
                  r="7"
                  fill={inRange ? (node.isSharingInternet ? "rgba(6, 182, 212, 0.15)" : "rgba(16, 185, 129, 0.15)") : "rgba(75, 85, 99, 0.1)"}
                  stroke={inRange ? (node.isSharingInternet ? "#06b6d4" : "#10b981") : "#4b5563"}
                  strokeWidth="1.2"
                />

                {/* Node index initial identifier letter */}
                <text
                  x={px}
                  y={py + 2}
                  fill="#ffffff"
                  fontSize="6.5"
                  fontWeight="900"
                  textAnchor="middle"
                  className="font-sans"
                >
                  {node.username.charAt(0).toUpperCase()}
                </text>

                {/* Signal dBm overlay label */}
                <text
                  x={px}
                  y={py - 10}
                  fill={inRange ? (node.isSharingInternet ? "#22d3ee" : "#34d399") : "#6b7280"}
                  fontSize="5.5"
                  fontWeight="bold"
                  textAnchor="middle"
                  className="font-mono"
                >
                  {inRange ? `${dbm} dBm` : "Offline"}
                </text>
              </g>
            );
          })}

          {/* Centered current workspace node */}
          <circle cx={cx} cy={cy} r="5" fill="#10b981" />
          <circle cx={cx} cy={cy} r="10" fill="none" stroke="#10b981" strokeWidth="0.8" className="animate-ping [animation-duration:3s]" />
          <text x={cx} y={cy + 1.5} fill="#ffffff" fontSize="5" fontWeight="black" textAnchor="middle">U</text>
        </svg>
        
        {/* Visual signals coordinates feed info */}
        <div className="flex justify-between items-center text-[6px] font-mono text-zinc-500 border-t border-zinc-900/60 pt-1 mt-0.5">
          <div>LAT: 34.0522° N</div>
          <div>LON: -118.2437° W</div>
          <div>PEERS DETECTED: {nodes.length - 1}</div>
        </div>
      </div>
    );
  };

  return (
    <div className="w-full max-w-[340px] h-[580px] bg-[#0A0B0D] border-4 border-slate-755 border-slate-700 rounded-[36px] overflow-hidden flex flex-col relative shadow-[0_0_40px_rgba(0,0,0,0.8)]">
      {/* Floating Push Notification Overlay for client node when a connection is proposed */}
      {activeEscrow && activeEscrow.status === 'PROPOSED' && activeNodeId === activeEscrow.buyerId && (
        <div className="absolute top-[32px] left-3 right-3 bg-zinc-950/95 border-2 border-amber-500/80 text-zinc-100 rounded-2xl p-3 shadow-[0_8px_32px_rgba(0,0,0,0.8)] z-50 animate-bounce duration-300">
          <div className="flex items-center gap-2 mb-1">
            <div className="w-6 h-6 rounded-full bg-cyan-950/45 border border-cyan-500/30 flex items-center justify-center text-xs text-cyan-400">
              <Wifi className="w-3.5 h-3.5 text-cyan-400" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-[10px] font-bold text-zinc-100 flex items-center gap-1.5 uppercase font-sans tracking-wide">
                <span>⚡ Instant Hotspot Offer</span>
                <span className="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse" />
              </div>
              <p className="text-[8.5px] text-zinc-400 font-sans truncate">
                From {nodes.find(n => n.id === activeEscrow.sharerId)?.username || 'Gateway'}
              </p>
            </div>
            <button 
              onClick={declineProposedTetheringRequest}
              className="text-zinc-500 hover:text-zinc-300 p-0.5 rounded bg-zinc-900 border border-zinc-800 transition-colors"
            >
              <span className="text-[9px] px-1 font-bold">✕</span>
            </button>
          </div>
          <div className="space-y-2 mt-1.5 pt-1.5 border-t border-zinc-900">
            <p className="text-[9.5px] text-zinc-300 font-sans">
              Gateway proposed connecting you at <strong className="text-teal-400 font-mono font-bold">{activeEscrow.ratePerMB.toFixed(2)} USDC/MB</strong>
            </p>
            <div className="flex gap-2 justify-end">
              <button
                onClick={declineProposedTetheringRequest}
                className="px-2 py-0.5 bg-zinc-805 bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-[9.5px] font-semibold rounded-md border border-zinc-700/40 transition-all"
              >
                Decline
              </button>
              <button
                onClick={() => {
                  acceptProposedTetheringRequest();
                  setActiveTab('mesh'); // slide over to mesh screen automatically to show live active layout stats
                }}
                className="px-2.5 py-0.5 bg-teal-500 hover:bg-teal-400 text-zinc-950 text-[9.5px] font-bold rounded-md transition-all shadow-sm"
              >
                Accept &amp; Connect
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Notch Speaker and Camera bar */}
      <div className="w-full bg-[#15171C] h-6 flex justify-between items-center px-6 shrink-0 relative border-b border-slate-900">
        <div className="text-[10px] font-semibold text-slate-400 font-mono">
          {new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
        </div>
        <div className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 w-16 h-4 bg-black rounded-full flex items-center justify-center">
          <span className="w-1.5 h-1.5 bg-slate-800 rounded-full mr-2" />
          <span className="w-6 h-1 bg-slate-800 rounded-full" />
        </div>
        <div className="flex items-center gap-1.5 text-slate-400">
          <Wifi className="w-3 h-3 text-cyan-400" />
          <span className="text-[9px] font-mono font-bold text-cyan-400">P2P</span>
        </div>
      </div>

      {/* App Header Screen */}
      <div className="bg-[#15171C] p-3 border-b border-slate-900 shrink-0">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-full bg-cyan-950/45 border border-cyan-500/30 flex items-center justify-center text-xs text-cyan-300 font-bold">
              {activeNode.username.substring(0,1).toUpperCase()}
            </div>
            <div>
              <div className="text-xs font-bold text-slate-100 flex items-center gap-1">
                {activeNode.username}'s Device
                <ShieldCheck className="w-3.5 h-3.5 text-cyan-400 shrink-0" />
              </div>
              <div className="text-[8.5px] font-mono text-slate-500 font-medium truncate max-w-[150px]">
                ID: {activeNode.id} • Key: {activeNode.publicKey.substring(0,8)}...
              </div>
            </div>
          </div>
          <div className="flex items-center gap-1.5 bg-slate-900 px-2 py-0.5 rounded border border-slate-800/80">
            <span className="w-1.5 h-1.5 rounded-full bg-green-400 animate-pulse" />
            <span className="text-[8.5px] font-mono text-slate-400 uppercase font-bold tracking-wider">OFFLINE</span>
          </div>
        </div>
      </div>

      {/* Main Dynamic Workspace Screen */}
      <div className="flex-1 overflow-hidden flex flex-col bg-[#0A0B0D]">
        
        {/* VIEW TAB CHATS */}
        {activeTab === 'chats' && (
          <div className="flex-1 flex flex-col overflow-hidden">
            {!selectedPeerId ? (
              // Chat Threads Listing View
              <div className="flex-1 overflow-y-auto p-2 space-y-1">
                <div className="p-2 text-zinc-500 text-[10px] font-semibold tracking-wider uppercase">
                  Connected Peers Nearby ({peerList.length})
                </div>
                {peerList.map(peer => {
                  const meshPath = computeRoutingPath(activeNodeId, peer.id);
                  const isDirect = meshPath?.length === 2;
                  const isRouted = meshPath && meshPath.length > 2;

                  return (
                    <button
                      key={peer.id}
                      onClick={() => setSelectedPeerId(peer.id)}
                      className="w-full p-2.5 rounded-xl bg-zinc-900/40 hover:bg-zinc-900 border border-zinc-900/50 text-left flex items-center justify-between transition-all"
                    >
                      <div className="flex items-center gap-2">
                        <div className="relative">
                          <div className="w-9 h-9 rounded-full bg-zinc-800 flex items-center justify-center text-xs font-semibold text-zinc-300">
                            {peer.username.substring(0, 2).toUpperCase()}
                          </div>
                          <span className={`absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full border-2 border-zinc-900 ${meshPath ? 'bg-emerald-500' : 'bg-amber-500'}`} />
                        </div>
                        <div>
                          <div className="text-xs font-bold text-zinc-200">{peer.username}</div>
                          <div className="text-[9px] text-zinc-500 truncate max-w-[140px]">
                            {meshPath
                              ? `Path: ${meshPath.join(' ➔ ')} (${meshPath.length - 1} Hops)`
                              : "No link. Multi-hop queue ready."}
                          </div>
                        </div>
                      </div>

                      <div className="text-right">
                        <div className="text-[9.5px] font-mono text-zinc-500 flex items-center gap-1 justify-end">
                          {isDirect && <span className="text-emerald-400 bg-emerald-500/10 px-1 py-0.5 rounded text-[8px] border border-emerald-500/10">1-Hop</span>}
                          {isRouted && <span className="text-amber-400 bg-amber-500/10 px-1 py-0.5 rounded text-[8px] border border-amber-500/10">Multi-Hop</span>}
                          {!meshPath && <span className="text-zinc-500 bg-zinc-800/40 px-1 py-0.5 rounded text-[8px]">Store-Fwd</span>}
                        </div>
                      </div>
                    </button>
                  );
                })}
              </div>
            ) : (
              // Individual Chat Thread View
              <div className="flex-1 flex flex-col overflow-hidden">
                {/* Peer bar header */}
                <div className="p-2 bg-zinc-900/60 border-b border-zinc-950 flex items-center justify-between shrink-0">
                  <button onClick={() => setSelectedPeerId(null)} className="text-xs text-teal-400 hover:text-teal-300 font-semibold px-2 py-1">
                    ◀ Peers
                  </button>
                  <div className="text-center">
                    <div className="text-xs font-bold text-zinc-100">{nodes.find(n => n.id === selectedPeerId)?.username}</div>
                    <div className="text-[8.5px] text-zinc-500 font-mono">
                      Signal Protocol E2EE channel active
                    </div>
                  </div>
                  <div className="w-10 opacity-0" /> {/* Spacer */}
                </div>

                {/* Messages Window */}
                <div className="flex-1 overflow-y-auto p-3 space-y-2.5 flex flex-col">
                  {activeChatMessages.length === 0 ? (
                    <div className="my-auto flex flex-col items-center p-4 text-center">
                      <Lock className="w-8 h-8 text-teal-500/30 mb-2" />
                      <div className="text-[10px] text-zinc-500 font-semibold uppercase tracking-wider">Device Keys Verified</div>
                      <p className="text-[10px] text-zinc-500 max-w-[180px] mt-1 leading-normal">
                        All communications are encrypted offline utilizing Double-Ratchet XCha20 AEAD packages.
                      </p>
                    </div>
                  ) : (
                    activeChatMessages.map(msg => {
                      const isMe = msg.senderId === activeNodeId;
                      const hasActiveRoute = msg.hops.length > 1 || msg.status === MessageStatus.DELIVERED;

                      return (
                        <div key={msg.id} className={`max-w-[85%] flex flex-col ${isMe ? 'self-end items-end' : 'self-start items-start'}`}>
                          <div className={`p-2.5 rounded-xl text-xs leading-normal ${
                            isMe ? 'bg-teal-700 text-teal-50' : 'bg-zinc-800/90 text-zinc-100'
                          }`}>
                            {msg.type === MessageType.TEXT && msg.content}

                            {msg.type === MessageType.VOICE && (
                              <div className="flex items-center gap-1.5 py-0.5">
                                <Play className="w-3.5 h-3.5 fill-current text-teal-300 shrink-0" />
                                <div className="h-4 w-20 bg-zinc-900/50 rounded flex items-center px-1">
                                  <div className="h-1 w-full bg-gradient-to-r from-teal-400 to-teal-500 rounded" style={{ width: '80%' }} />
                                </div>
                                <span className="text-[8.5px] font-mono">{msg.voiceDuration}s</span>
                              </div>
                            )}

                            {(msg.type === MessageType.IMAGE || msg.type === MessageType.FILE) && (
                              <div className="flex items-center gap-2 border border-zinc-700/50 p-1.5 bg-zinc-950/40 rounded-lg">
                                <Paperclip className="w-3.5 h-3.5 text-teal-400" />
                                <div className="leading-tight text-left">
                                  <div className="text-[10px] truncate max-w-[120px] font-mono text-zinc-300">{msg.fileName}</div>
                                  <div className="text-[8px] text-zinc-500">{msg.fileSize} • E2E Encrypted</div>
                                </div>
                              </div>
                            )}

                            {/* Tech debugging metadata envelope block (Aesthetic details) */}
                            <div className="mt-1 flex items-center gap-1.5 text-[7px] text-zinc-400 font-mono scale-95 origin-right">
                              <span>Step: {msg.ratchetStep}</span>
                              <span>•</span>
                              <span className="truncate max-w-[80px]" title={msg.encryptedBytes}>Sig: {msg.encryptedBytes?.substring(9,17)}...</span>
                            </div>
                          </div>

                          <div className="mt-0.5 flex items-center gap-1.5 text-[8.5px] font-mono text-zinc-500 p-0.5">
                            <span>{msg.timestamp}</span>
                            <span>•</span>
                            <span className={msg.status === MessageStatus.DELIVERED ? 'text-emerald-400' : 'text-amber-400'}>
                              {msg.status === MessageStatus.DELIVERED ? 'Delivered' : 'Offline Queue'}
                            </span>
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>

                {/* File Upload Progress Banner */}
                {fileProgress !== null && (
                  <div className="px-3 py-1.5 bg-zinc-900 border-t border-zinc-950 flex items-center justify-between text-[10px] text-zinc-400">
                    <span className="flex items-center gap-1 text-teal-400 font-medium">
                      <Paperclip className="w-3 h-3 animate-spin" />
                      Encrypting & packetizing file...
                    </span>
                    <span className="font-mono">{fileProgress}%</span>
                  </div>
                )}

                {/* Chat Inputs */}
                <div className="p-2 bg-zinc-900 border-t border-zinc-950 shrink-0 flex items-center gap-1">
                  <div className="flex items-center bg-zinc-950 rounded-lg px-2 py-1.5 flex-grow border border-zinc-800">
                    <input
                      type="text"
                      placeholder="Mesh double ratchet chat..."
                      value={typewrittenMessage}
                      onChange={(e) => setTypewrittenMessage(e.target.value)}
                      onKeyDown={(e) => e.key === 'Enter' && handleSendMessage(MessageType.TEXT)}
                      className="w-full bg-transparent focus:outline-none text-[11px] text-zinc-200 placeholder-zinc-600"
                    />

                    {/* Hidden Native File Attachment Simulator */}
                    <label className="cursor-pointer p-0.5 hover:bg-zinc-900 rounded stroke-[1.5] text-teal-500 hover:text-teal-400 shrink-0 select-none">
                      <Paperclip className="w-3.5 h-3.5" />
                      <input
                        type="file"
                        onChange={simulateFileUpload}
                        className="hidden"
                      />
                    </label>
                  </div>

                  {/* Record Voice Note Button toggle */}
                  {isRecordingVoice ? (
                    <button
                      onClick={stopRecordingAndSend}
                      className="p-2 bg-red-600 hover:bg-red-500 text-zinc-100 rounded-lg text-xs font-bold animate-pulse shrink-0"
                    >
                      Stop {recordingDuration}s
                    </button>
                  ) : (
                    <button
                      onClick={startRecording}
                      className="p-2 bg-zinc-800 hover:bg-zinc-700 text-teal-400 rounded-lg shrink-0"
                      title="Simulate voice PTT note"
                    >
                      <Mic className="w-3.5 h-3.5" />
                    </button>
                  )}

                  <button
                    onClick={() => handleSendMessage(MessageType.TEXT)}
                    className="p-2 bg-teal-500 hover:bg-teal-400 text-zinc-950 rounded-lg shrink-0"
                  >
                    <Send className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            )}
          </div>
        )}

        {/* VIEW TAB SETTINGS */}
        {activeTab === 'settings' && (
          <div className="flex-1 p-3 overflow-y-auto space-y-4">
            <h3 className="text-sm font-bold text-zinc-100 uppercase tracking-widest text-[#00D2FF] font-sans">
              Settings
            </h3>

            {/* Profile Section Box */}
            <div className="bg-[#15171C] p-3 rounded-2xl border border-zinc-900 flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-full bg-cyan-950/45 border-2 border-cyan-400 flex items-center justify-center text-xs font-black text-cyan-400">
                  YO
                </div>
                <div>
                  <div className="text-xs font-bold text-slate-100 flex items-center gap-1">
                    You
                    <span className="text-[9px] text-zinc-500 cursor-pointer hover:text-cyan-400 font-sans">📝</span>
                  </div>
                  <div className="text-[7.5px] font-mono text-zinc-500 truncate max-w-[140px]" title={activeNode.id}>
                    ID: {activeNode.id}
                  </div>
                </div>
              </div>
              <div className="p-1.5 bg-zinc-900 border border-zinc-800 text-zinc-400 rounded-lg">
                <QrCode className="w-3.5 h-3.5" />
              </div>
            </div>

            {/* INTERNET SHARING SECTION */}
            <div className="space-y-1.5">
              <div className="text-[8.5px] font-bold text-zinc-500 uppercase tracking-widest font-mono px-1">
                Internet Sharing
              </div>
              <div className="bg-[#15171C] rounded-2xl border border-zinc-900 divide-y divide-zinc-950 overflow-hidden">
                {/* Switch row */}
                <div className="p-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className="w-7 h-7 bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 rounded-lg flex items-center justify-center">
                      <Wifi className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-[10.5px] font-bold text-slate-100">Share My Internet</div>
                      <div className="text-[7.5px] text-zinc-500">Allow mesh peers to use your connection</div>
                    </div>
                  </div>
                  <button
                    onClick={() => {
                      const updated = nodes.map(n => {
                        if (n.id === activeNodeId) return { ...n, isSharingInternet: !n.isSharingInternet };
                        return n;
                      });
                      onNodesUpdate(updated);
                    }}
                    className="focus:outline-none"
                  >
                    {activeNode.isSharingInternet ? (
                      <ToggleRight className="w-7 h-7 text-emerald-400 fill-current" />
                    ) : (
                      <ToggleLeft className="w-7 h-7 text-zinc-600" />
                    )}
                  </button>
                </div>

                {/* Price configuring input */}
                <div className="p-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className="w-7 h-7 bg-amber-500/10 border border-amber-500/20 text-amber-500 rounded-lg flex items-center justify-center">
                      <Zap className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-[10.5px] font-bold text-slate-100">Price per MB</div>
                      <div className="text-[7.5px] text-zinc-500 font-sans">Charged to buyers in USDC via escrow</div>
                    </div>
                  </div>
                  {/* Digital precise pricing controller */}
                  <div className="flex items-center justify-between bg-zinc-950 border border-zinc-900 p-0.5 rounded-lg w-20 text-center font-mono text-[10px]">
                    <button 
                      type="button"
                      onClick={() => {
                        const newPrice = Math.max(0.001, parseFloat((activeNode.pricePerMB - 0.001).toFixed(3)));
                        const updated = nodes.map(n => {
                          if (n.id === activeNodeId) return { ...n, pricePerMB: newPrice };
                          return n;
                        });
                        onNodesUpdate(updated);
                      }}
                      className="px-1.5 py-0.5 bg-zinc-90 w-full hover:bg-zinc-850 text-zinc-200 rounded font-bold"
                    >
                      -
                    </button>
                    <span className="font-bold text-cyan-400 px-1">{activeNode.pricePerMB.toFixed(3)}</span>
                    <button 
                      type="button"
                      onClick={() => {
                        const newPrice = Math.max(0.001, parseFloat((activeNode.pricePerMB + 0.001).toFixed(3)));
                        const updated = nodes.map(n => {
                          if (n.id === activeNodeId) return { ...n, pricePerMB: newPrice };
                          return n;
                        });
                        onNodesUpdate(updated);
                      }}
                      className="px-1.5 py-0.5 bg-zinc-90 w-full hover:bg-zinc-850 text-zinc-200 rounded font-bold"
                    >
                      +
                    </button>
                  </div>
                </div>

                {/* Token selectors buttons */}
                <div className="p-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className="w-7 h-7 bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 rounded-lg flex items-center justify-center">
                      <Wallet className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-[10.5px] font-bold text-slate-100">Accept Token</div>
                      <div className="text-[7.5px] text-zinc-500 font-sans">USDC stablecoin is default routing fuel</div>
                    </div>
                  </div>
                  <div className="flex gap-1">
                    {(['USDC', 'SOL', 'MATIC', 'USDT'] as const).map(tk => (
                      <button
                        key={tk}
                        onClick={() => setSettingsToken(tk)}
                        className={`text-[8px] font-bold px-1.5 py-0.5 rounded border transition-all ${
                          settingsToken === tk 
                            ? 'bg-cyan-500/10 text-cyan-450 text-cyan-400 border-cyan-500/30' 
                            : 'bg-zinc-950 text-zinc-500 border-zinc-900 hover:text-zinc-350'
                        }`}
                      >
                        {tk}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            </div>

            {/* RADIO TRANSPORTS SECTION */}
            <div className="space-y-1.5">
              <div className="text-[8.5px] font-bold text-zinc-500 uppercase tracking-widest font-mono px-1">
                Radio Transports
              </div>
              <div className="bg-[#15171C] rounded-2xl border border-zinc-900 divide-y divide-zinc-950 overflow-hidden">
                {/* Bluetooth LE */}
                <div className="p-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className="w-7 h-7 bg-blue-500/10 border border-blue-500/20 text-blue-400 rounded-lg flex items-center justify-center">
                      <Radio className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-[10.5px] font-bold text-slate-100">Bluetooth LE</div>
                      <div className="text-[7.5px] text-zinc-500">Range ~50m, ultra low standby draw</div>
                    </div>
                  </div>
                  <button onClick={() => setBleEnabled(!bleEnabled)} className="focus:outline-none">
                    {bleEnabled ? <ToggleRight className="w-7 h-7 text-emerald-400 fill-current" /> : <ToggleLeft className="w-7 h-7 text-zinc-600" />}
                  </button>
                </div>

                {/* Wi-Fi Aware NAN */}
                <div className="p-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className="w-7 h-7 bg-purple-500/10 border border-purple-500/20 text-purple-400 rounded-lg flex items-center justify-center">
                      <Network className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-[10.5px] font-bold text-slate-100">Wi-Fi Aware (NAN)</div>
                      <div className="text-[7.5px] text-zinc-500">Range ~100m, peer discovery mesh layers</div>
                    </div>
                  </div>
                  <button onClick={() => setNanEnabled(!nanEnabled)} className="focus:outline-none">
                    {nanEnabled ? <ToggleRight className="w-7 h-7 text-emerald-400 fill-current" /> : <ToggleLeft className="w-7 h-7 text-zinc-600" />}
                  </button>
                </div>

                {/* Wi-Fi Direct */}
                <div className="p-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className="w-7 h-7 bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 rounded-lg flex items-center justify-center">
                      <Wifi className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-[10.5px] font-bold text-slate-100">Wi-Fi Direct</div>
                      <div className="text-[7.5px] text-zinc-500">Range ~200m, high capacity data streams</div>
                    </div>
                  </div>
                  <button onClick={() => setDirectEnabled(!directEnabled)} className="focus:outline-none">
                    {directEnabled ? <ToggleRight className="w-7 h-7 text-emerald-400 fill-current" /> : <ToggleLeft className="w-7 h-7 text-zinc-600" />}
                  </button>
                </div>
              </div>
            </div>

            {/* SECURITY & IDENTITY SECTION */}
            <div className="space-y-1.5">
              <div className="text-[8.5px] font-bold text-zinc-500 uppercase tracking-widest font-mono px-1">
                Security &amp; Identity
              </div>
              <div className="bg-[#15171C] rounded-2xl border border-zinc-900 divide-y divide-zinc-950 overflow-hidden text-[10.5px]">
                {/* Protocol */}
                <div className="p-3 flex justify-between items-center">
                  <div className="text-zinc-400 font-medium">Encryption Protocol</div>
                  <div className="text-zinc-500 font-mono text-[9.5px]">Signal Double Ratchet</div>
                </div>
                {/* Key Ex */}
                <div className="p-3 flex justify-between items-center">
                  <div className="text-zinc-400 font-medium">Key Exchange</div>
                  <div className="text-zinc-500 font-mono text-[9.5px]">X25519</div>
                </div>
                {/* Safety Number */}
                <div className="p-3 flex justify-between items-center hover:bg-zinc-900/45 cursor-pointer">
                  <div className="text-zinc-400 font-medium">Safety Number</div>
                  <div className="text-zinc-500 flex items-center gap-1 text-[9.5px]">
                    Verify out-of-band <ChevronRight className="w-3 h-3 text-zinc-600" />
                  </div>
                </div>
              </div>

              {/* Identity Fingerprint Card */}
              <div className="bg-[#0A0B0D] p-3 rounded-2xl border border-zinc-900 space-y-2">
                <div className="text-[8.5px] font-bold text-[#E255FF] uppercase tracking-wider font-mono">
                  Your Identity Fingerprint
                </div>
                <div className="bg-zinc-950 rounded-xl p-2.5 border border-zinc-900 text-center font-mono space-y-1 leading-snug">
                  <div className="text-[9px] text-[#00D2FF] font-black tracking-widest">
                    7737  7237  6D76  5172  7A7A  6339
                  </div>
                  <div className="text-[9px] text-zinc-500 font-bold tracking-widest">
                    5A66  356G  356B  7C8E  91DE  211D
                  </div>
                </div>
                <p className="text-[7.5px] text-zinc-500 leading-normal font-sans text-center">
                  Compare this with your peer in person or via another authenticated secure channel to verify E2E path integrity.
                </p>
              </div>

              {/* Add Peer QR row */}
              <div className="bg-[#15171C] p-3 rounded-2xl border border-zinc-900 flex justify-between items-center hover:bg-zinc-900/40 cursor-pointer">
                <div className="flex items-center gap-2">
                  <QrCode className="w-4 h-4 text-[#00D2FF]" />
                  <div>
                    <div className="text-[10.5px] font-bold text-slate-100">Add Peer via QR Code</div>
                    <div className="text-[7.5px] text-zinc-500">Scan to initiate cryptographic handshake</div>
                  </div>
                </div>
                <ChevronRight className="w-4 h-4 text-zinc-600" />
              </div>
            </div>

            {/* DIAGNOSTICS & SYSTEM STATUS */}
            <div className="space-y-1.5">
              <button
                type="button"
                onClick={() => setShowDiagnostics(!showDiagnostics)}
                className="w-full flex items-center justify-between bg-[#15171C] border border-zinc-900 rounded-xl p-3 text-[10.5px] font-bold text-zinc-350 hover:text-[#00D2FF] transition-all cursor-pointer font-display"
              >
                <span className="flex items-center gap-1.5 font-display text-[10.5px] uppercase tracking-wider">
                  <Cpu className="w-3.5 h-3.5 text-[#00D2FF]" />
                  Diagnostics &amp; System Info
                </span>
                {showDiagnostics ? (
                  <ChevronDown className="w-3.5 h-3.5 text-[#00D2FF]" />
                ) : (
                  <ChevronRight className="w-3.5 h-3.5 text-zinc-500" />
                )}
              </button>
              
              {showDiagnostics && (
                <div className="bg-[#15171C] rounded-2xl border border-zinc-900 p-3 space-y-3 animate-in fade-in slide-in-from-top-1 duration-200">
                  <div className="text-[10px] text-zinc-400 font-sans leading-normal">
                    Technical telemetry and power metrics from the active mobile radio transceiver:
                  </div>
                  
                  {/* Detailed Mesh Radio battery Diagnostics */}
                  {(() => {
                    const relayedMCount = messages.filter(msg => 
                      msg.hops && msg.hops.includes(activeNodeId) && msg.senderId !== activeNodeId && msg.recipientId !== activeNodeId
                    ).length;

                    const userMCount = messages.filter(msg =>
                      msg.senderId === activeNodeId || msg.recipientId === activeNodeId
                    ).length;

                    const computedRoutingEnergy = 12.5 + (relayedMCount * 1.5) + (userMCount * 0.8);
                    const computedTetheringEnergy = (activeNode.isSharingInternet ? 32.0 : 0.0) + 
                      (activeEscrow ? activeEscrow.dataMeteredMB * 2.5 : 0.0);

                    const totalEnergy = computedRoutingEnergy + computedTetheringEnergy;
                    const rPercent = totalEnergy > 0 ? (computedRoutingEnergy / totalEnergy) * 100 : 50;
                    const tPercent = totalEnergy > 0 ? (computedTetheringEnergy / totalEnergy) * 100 : 50;

                    return (
                      <div className="space-y-3">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-1.5">
                            <Battery className="w-3.5 h-3.5 text-[#00D2FF]" />
                            <h4 className="text-[11px] font-bold text-zinc-250">Mesh Radio Power</h4>
                          </div>
                          <div className="flex items-center gap-1">
                            <span className="text-[10px] font-mono text-zinc-300">{simulatedBatteryPercentage.toFixed(1)}%</span>
                            <div className="w-5 h-2.5 border border-zinc-700 rounded-sm p-0.5 flex">
                              <div 
                                className="bg-emerald-500 h-full rounded-2xs" 
                                style={{ width: `${Math.min(100, Math.max(10, simulatedBatteryPercentage))}%` }}
                              />
                            </div>
                          </div>
                      </div>

                        {/* Stacked comparison bar */}
                        <div className="space-y-1">
                          <div className="flex justify-between text-[9px] text-zinc-500 font-mono">
                            <span>ROUTING ({rPercent.toFixed(0)}%)</span>
                            <span>TETHER ({tPercent.toFixed(0)}%)</span>
                          </div>
                          <div className="h-2 w-full bg-zinc-950 rounded-full overflow-hidden flex">
                            <div 
                              className="bg-[#00D2FF] transition-all duration-500" 
                              style={{ width: `${rPercent}%` }} 
                              title={`Routing: ${computedRoutingEnergy.toFixed(1)} mAh`}
                            />
                            <div 
                              className="bg-indigo-500 transition-all duration-500" 
                              style={{ width: `${tPercent}%` }} 
                              title={`Tethering: ${computedTetheringEnergy.toFixed(1)} mAh`}
                            />
                          </div>
                        </div>

                        {/* Breakdown */}
                        <div className="grid grid-cols-2 gap-2 text-[10px]">
                          <div className="bg-zinc-950/65 p-2 rounded-lg border border-zinc-900/80 space-y-1">
                            <div className="flex items-center gap-1 font-semibold text-[#00D2FF] font-mono">
                              <Cpu className="w-3 h-3 shrink-0" />
                              <span>P2P Routing</span>
                            </div>
                            <div className="text-zinc-500 text-[9px] space-y-0.5 font-sans">
                              <div className="flex justify-between">
                                <span>BLE Scan:</span>
                                <span className="font-mono text-zinc-400">12.5 mAh</span>
                              </div>
                              <div className="flex justify-between">
                                <span>Relayed:</span>
                                <span className="font-mono text-zinc-400">{relayedMCount} pkts</span>
                              </div>
                              <div className="border-t border-zinc-800/80 pt-1 mt-1 flex justify-between font-bold text-zinc-350">
                                <span>Total cost:</span>
                                <span className="font-mono text-cyan-300">{computedRoutingEnergy.toFixed(1)} mAh</span>
                              </div>
                            </div>
                          </div>

                          <div className="bg-zinc-950/65 p-2 rounded-lg border border-zinc-900/80 space-y-1">
                            <div className="flex items-center gap-1 font-semibold text-indigo-400 font-mono">
                              <Zap className="w-3 h-3 shrink-0" />
                              <span>Tether Share</span>
                            </div>
                            <div className="text-zinc-500 text-[9px] space-y-0.5 font-sans">
                              <div className="flex justify-between">
                                <span>AP Standby:</span>
                                <span className="font-mono text-zinc-400">
                                  {activeNode.isSharingInternet ? '32.0 mAh' : '0.0 mAh'}
                                </span>
                              </div>
                              <div className="flex justify-between">
                                <span>Proxy Data:</span>
                                <span className="font-mono text-zinc-400">
                                  {activeEscrow ? `${activeEscrow.dataMeteredMB.toFixed(1)} MB` : '0.0 MB'}
                                </span>
                              </div>
                              <div className="border-t border-zinc-800/80 pt-1 mt-1 flex justify-between font-bold text-zinc-300 font-sans">
                                <span>Total cost:</span>
                                <span className="font-mono text-indigo-300">{computedTetheringEnergy.toFixed(1)} mAh</span>
                              </div>
                            </div>
                          </div>
                        </div>

                        {/* Active Real-time metrics */}
                        <div className="text-[9px] text-zinc-500 flex justify-between items-center bg-zinc-950 p-1.5 rounded-md font-mono border border-zinc-900">
                          <span className="flex items-center gap-1">
                            <span className={`w-1.5 h-1.5 rounded-full ${currentMilliAmpsFlutter > 15 ? 'bg-amber-500 animate-pulse' : 'bg-green-500'}`} />
                            Instant Discharge:
                          </span>
                          <span className="text-zinc-300 font-bold">-{currentMilliAmpsFlutter} mA</span>
                        </div>
                      </div>
                    );
                  })()}

                  {/* Smart proxy escrow info card */}
                  <div className="p-2 bg-zinc-950 rounded-lg border border-zinc-900 text-[9px] text-zinc-500 leading-normal flex items-start gap-1.5">
                    <ShieldCheck className="w-3.5 h-3.5 text-emerald-400 shrink-0 mt-0.5" />
                    <p className="font-sans">
                      Session traffic is fully sandboxed &amp; cryptographically piped via local SOCKS5 proxy over authenticated Wi-Fi Direct protocols.
                    </p>
                  </div>
                </div>
              )}
            </div>

            {/* LEGAL DISCLAIMERS SECTION */}
            <div className="space-y-1.5">
              <button
                type="button"
                onClick={() => setShowDisclaimers(!showDisclaimers)}
                className="w-full flex items-center justify-between bg-[#15171C] border border-zinc-900 rounded-xl p-3 text-[10.5px] font-bold text-zinc-350 hover:text-[#00D2FF] transition-all cursor-pointer font-display"
              >
                <span className="flex items-center gap-1.5 font-display text-[10.5px] uppercase tracking-wider">
                  <Info className="w-3.5 h-3.5 text-[#00D2FF]" />
                  Disclaimers
                </span>
                {showDisclaimers ? (
                  <ChevronDown className="w-3.5 h-3.5 text-[#00D2FF]" />
                ) : (
                  <ChevronRight className="w-3.5 h-3.5 text-zinc-500" />
                )}
              </button>

              {showDisclaimers && (
                <div className="bg-[#15171C] rounded-2xl border border-zinc-900 p-3 space-y-3 text-[10px] leading-normal font-sans animate-in fade-in slide-in-from-top-1 duration-200">
                  {/* Carrier Terms Warning */}
                  <div className="p-2.5 bg-amber-500/5 border border-amber-500/15 rounded-xl flex gap-2 items-start">
                    <span className="text-amber-500 shrink-0">⚠️</span>
                    <div className="text-[9.5px] text-amber-500/90 space-y-0.5">
                      <p className="font-bold">Carrier Terms Warning</p>
                      <p className="leading-tight text-[9px]">
                        Reselling mobile internet data for personal crypto profit can breach cellular operator agreements. Tethering and Aware structures may not execute correctly on iOS client devices. Use responsibly.
                      </p>
                    </div>
                  </div>

                  {/* Additional Regulatory Policy */}
                  <div className="space-y-2 border-t border-zinc-900 pt-2.5 text-[9px] text-zinc-500">
                    <div className="flex items-start gap-1.5">
                      <span className="text-zinc-600">•</span>
                      <p>
                        <strong>Non-Custodial Escrow:</strong> All peer payments are executed directly on the Solana blockchain network through decentralized escrow, with zero custody of third-party keys or user tokens by developers.
                      </p>
                    </div>
                    <div className="flex items-start gap-1.5">
                      <span className="text-zinc-600">•</span>
                      <p>
                        <strong>Radio &amp; Location:</strong> This application communicates peer-to-peer over license-free ISM bands. While encrypted via Signal Double Ratchet, local radio transmissions remain physically detectable.
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* VIEW TAB MESH DASHBOARD */}
        {activeTab === 'mesh' && (
          <div className="flex-1 overflow-y-auto space-y-4 pb-4 px-3 pt-3 font-sans">
            {/* 1. Live animated RSSI node map */}
            <div className="space-y-1.5 animate-in fade-in duration-350">
              <div className="text-[8px] font-bold text-zinc-500 uppercase tracking-widest font-mono px-0.5 flex justify-between items-center">
                <span>Physical Mesh Node Map</span>
                <span className="text-teal-400 font-bold lowercase">Dynamic Local RF Sweep</span>
              </div>
              {renderRadarMap()}
            </div>

            {/* 2. Escrow Active Session / Connection status */}
            <div className="space-y-1.5">
              <div className="text-[8px] font-bold text-zinc-500 uppercase tracking-widest font-mono px-0.5">
                Smart Contract Hotspot Status
              </div>

              {activeEscrow ? (
                // Active, Pending, or Proposed Hotspot session
                activeEscrow.status === 'PROPOSED' ? (
                  activeNodeId === activeEscrow.buyerId ? (
                    // VIEW FOR CLIENT WHO RECEIVED THE PROPOSAL
                    <div className="p-3 bg-zinc-90 w-full bg-zinc-900 border border-amber-500/35 rounded-2xl space-y-3 font-sans animate-bounce duration-500">
                      <div className="flex items-center justify-between border-b border-zinc-900 pb-2">
                        <span className="text-[10.5px] font-bold text-zinc-100 flex items-center gap-1.5">
                          <span className="w-1.5 h-1.5 bg-amber-500 rounded-full animate-bounce" />
                          Incoming Hotspot Proposal
                        </span>
                        <span className="px-1.5 py-0.5 bg-amber-500/10 text-amber-400 border border-amber-500/20 text-[8px] font-mono rounded">
                          OFFER RECEIVED
                        </span>
                      </div>
                      <p className="text-[10px] text-zinc-400 leading-normal">
                        Internet Gateway <strong className="text-zinc-200">{nodes.find(n => n.id === activeEscrow.sharerId)?.username}</strong> is proposing to tunnel your traffic through their cellular connection.
                      </p>
                      <div className="bg-zinc-950 p-2.5 rounded-xl border border-zinc-855 font-mono text-center space-y-1">
                        <span className="text-[9px] text-zinc-500 block">Proposed Rate:</span>
                        <span className="text-[#00D2FF] font-black text-xs block">{activeEscrow.ratePerMB.toFixed(3)} USDC / MB</span>
                        <span className="text-[8px] text-zinc-500 block">Deducts 2.50 USDC fixed prepaid deposit</span>
                      </div>
                      
                      <div className="grid grid-cols-2 gap-2 pt-1 font-sans font-semibold">
                        <button
                          onClick={declineProposedTetheringRequest}
                          className="py-1.5 bg-zinc-900 hover:bg-zinc-800 text-zinc-400 rounded-xl font-semibold text-[10.5px] border border-zinc-800"
                        >
                          Decline
                        </button>
                        <button
                          onClick={acceptProposedTetheringRequest}
                          className="py-1.5 bg-gradient-to-r from-teal-500 to-cyan-500 text-zinc-900 font-bold rounded-xl text-[10.5px]"
                        >
                          Accept Tunnel
                        </button>
                      </div>
                    </div>
                  ) : activeNodeId === activeEscrow.sharerId ? (
                    // VIEW FOR GATEWAY WHO PROPOSED IT
                    <div className="p-3 bg-zinc-900 border border-[#00D2FF]/20 rounded-2xl space-y-3 font-sans">
                      <div className="flex items-center justify-between border-b border-zinc-850 pb-2">
                        <span className="text-[10.5px] font-bold text-zinc-200 flex items-center gap-1.5">
                          <span className="w-1.5 h-1.5 bg-cyan-400 rounded-full animate-pulse" />
                          Tether proposal sent
                        </span>
                        <span className="px-1.5 py-0.5 bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 text-[8px] font-mono rounded">
                          AWAITING PEER
                        </span>
                      </div>
                      <p className="text-[10px] text-zinc-400 leading-normal">
                        Awaiting acceptance from peer <strong className="text-zinc-200">{nodes.find(n => n.id === activeEscrow.buyerId)?.username}</strong>. They must sign the escrow payload.
                      </p>
                      <div className="bg-zinc-950 p-2.5 rounded-xl border border-zinc-855 font-mono text-center">
                        <div className="text-[9px] text-zinc-550 block">Your Offered Rate:</div>
                        <div className="text-[#00D2FF] font-black text-xs block">{activeEscrow.ratePerMB.toFixed(3)} USDC / MB</div>
                      </div>
                      <button
                        onClick={() => setActiveEscrow(null)}
                        className="w-full py-1.5 bg-zinc-950 hover:bg-zinc-90 w-full hover:bg-zinc-900 text-red-100 rounded-xl text-[10px] font-semibold border border-zinc-800 transition-all font-sans"
                      >
                        Revoke Proposal
                      </button>
                    </div>
                  ) : (
                    // VIEW FOR NEUTRAL THIRD PARTY ON PROPOSAL
                    <div className="p-3 text-center text-zinc-500 text-[9px] bg-[#15171C] rounded-2xl border border-zinc-900 font-mono italic">
                      ホットスポット (Hotspot proposal circulating nearby)
                    </div>
                  )
                ) : activeEscrow.status === 'PENDING' ? (
                  activeNodeId === activeEscrow.buyerId ? (
                    // VIEW FOR BUYER (PENDING HOST APPROVAL)
                    <div className="p-3 bg-zinc-900 border border-yellow-500/20 rounded-2xl space-y-3 font-sans">
                      <div className="flex items-center justify-between border-b border-zinc-800 pb-2">
                        <span className="text-[10.5px] font-bold text-zinc-100 flex items-center gap-1.5">
                          <span className="w-1.5 h-1.5 bg-yellow-400 rounded-full animate-pulse" />
                          Awaiting Operator signature
                        </span>
                        <span className="px-1.5 py-0.5 bg-yellow-500/10 text-yellow-400 border border-yellow-500/20 text-[8px] font-mono rounded">
                          PENDING APPROVAL
                        </span>
                      </div>
                      <p className="text-[10px] text-zinc-400 leading-normal font-sans">
                        You submitted a prepaid lease contract to host <strong className="text-zinc-200">{nodes.find(n => n.id === activeEscrow.sharerId)?.username}</strong>. Awaiting Wi-Fi SSID auth credentials.
                      </p>
                      <button
                        onClick={declinePendingTetheringRequest}
                        className="w-full py-1.5 bg-zinc-900 hover:bg-zinc-800 text-zinc-400 border border-zinc-800 rounded-xl text-[10px] font-semibold transition-all font-sans"
                      >
                        Cancel Prepaid Request &amp; Unlock Funds
                      </button>
                    </div>
                  ) : activeNodeId === activeEscrow.sharerId ? (
                    // VIEW FOR SELLER (NEED TO CONFIRM OR DECLINE GUEST CONNECTION)
                    <div className="p-3 bg-zinc-900 border border-yellow-500/35 rounded-2xl space-y-3 font-sans">
                      <div className="flex items-center justify-between border-b border-zinc-800 pb-2">
                        <span className="text-[10.5px] font-bold text-zinc-100 flex items-center gap-1.5">
                          <span className="w-1.5 h-1.5 bg-yellow-400 rounded-full animate-bounce" />
                          Incoming Tether Request
                        </span>
                        <span className="px-1.5 py-0.5 bg-yellow-500/10 text-yellow-400 border border-yellow-500/20 text-[8px] font-mono rounded font-sans">
                          ACTION REQUIRED
                        </span>
                      </div>
                      <p className="text-[10px] text-zinc-400 leading-normal">
                        Mesh peer <strong className="text-zinc-200">{nodes.find(n => n.id === activeEscrow.buyerId)?.username}</strong> is offering to purchase data. They deposited a <strong>2.50 USDC</strong> escrow lock contract.
                      </p>
                      
                      <div className="grid grid-cols-2 gap-2 pt-1 font-sans">
                        <button
                          onClick={declinePendingTetheringRequest}
                          className="py-1.5 bg-zinc-900 hover:bg-zinc-850 text-zinc-400 hover:text-white rounded-xl font-semibold border border-zinc-800 text-[10px] transition-all"
                        >
                          Decline Request
                        </button>
                        <button
                          onClick={startActiveTetheringOfPending}
                          className="py-1.5 bg-gradient-to-r from-[#00D2FF] to-teal-500 text-zinc-950 font-black rounded-xl text-[10px] transition-all"
                        >
                          Approve Lease
                        </button>
                      </div>
                    </div>
                  ) : (
                    // VIEW FOR NEUTRAL THIRD PARTY
                    <div className="p-3 text-center text-zinc-500 text-[9px] bg-[#15171C] rounded-2xl border border-zinc-900 font-mono italic">
                      Prepaid hotspot session is pending approval on the RF grid.
                    </div>
                  )
                ) : (
                  // escrow status is 'ACTIVE'
                  activeNodeId === activeEscrow.buyerId ? (
                    // VIEW FOR ACTIVE BUYER
                    <div className="p-3 bg-[#0d1512] border border-teal-500/30 rounded-2xl space-y-2.5 font-sans animate-in fade-in duration-300">
                      {(() => {
                        const hostNode = nodes.find(n => n.id === activeEscrow.sharerId) || activeNode;
                        const maxQuotaMB = activeEscrow.escrowBalanceUSDC / activeEscrow.ratePerMB;
                        const remainingMB = Math.max(0, maxQuotaMB - activeEscrow.dataMeteredMB);
                        const spentUSDC = Math.min(2.50, activeEscrow.dataMeteredMB * activeEscrow.ratePerMB);

                        return (
                          <>
                            <div className="flex items-center justify-between border-b border-emerald-950 pb-2">
                              <span className="text-[10.5px] font-bold text-emerald-400 flex items-center gap-1.5 font-sans">
                                <span className="w-1.5 h-1.5 bg-emerald-400 rounded-full animate-ping" />
                                Active Tunnel: Meshnet_{hostNode.username}
                              </span>
                              <span className="px-1.5 py-0.5 bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 text-[8px] font-mono rounded">
                                ESCROW LOCKED
                              </span>
                            </div>

                            <p className="text-[9.5px] text-zinc-400 font-mono leading-normal">
                              Connected via SOCKS5 proxy to hot wireless node. Password: <span className="text-yellow-400 font-bold font-sans">mesh_key_sec_{hostNode.publicKey.substring(0, 4)}</span>
                            </p>

                            {/* Meter and Billing Metrics */}
                            <div className="grid grid-cols-2 gap-2 text-center pt-0.5 font-mono">
                              <div className="bg-zinc-900 p-2 rounded-xl border border-zinc-900/40">
                                <div className="text-[7.5px] text-zinc-500 uppercase font-sans">Metered Usage</div>
                                <div className="text-xs font-black text-white mt-0.5">{activeEscrow.dataMeteredMB.toFixed(2)} MB</div>
                                <div className="text-[7px] text-zinc-400 mt-0.5">-{spentUSDC.toFixed(3)} USDC</div>
                              </div>
                              <div className="bg-zinc-900 p-2 rounded-xl border border-[#06b6d4]/10 bg-cyan-950/5">
                                <div className="text-[7.5px] text-cyan-400 uppercase font-sans">Remaining Quota</div>
                                <div className="text-xs font-black text-cyan-400 mt-0.5">{remainingMB.toFixed(1)} MB</div>
                                <div className="text-[7px] text-cyan-500 mt-0.5">+{Math.max(0, 2.50 - spentUSDC).toFixed(3)} Escrow</div>
                              </div>
                            </div>

                            {/* Flucating Speed Gauge */}
                            <div className="bg-zinc-950 p-2 rounded-xl border border-zinc-900 flex items-center justify-between text-left">
                              <div className="flex items-center gap-1.5 font-sans">
                                <Activity className="w-3.5 h-3.5 text-emerald-400 animate-pulse" />
                                <div>
                                  <div className="text-[8px] text-zinc-500 uppercase font-sans">Real-time Tunnel Speed</div>
                                  <div className="text-[10.5px] font-mono font-bold text-zinc-200">
                                    {(meteredSpeed + Math.sin(Date.now() / 2500) * 1.5).toFixed(1)} Mbps
                                  </div>
                                </div>
                              </div>
                              <span className="text-[7px] font-mono text-emerald-500 bg-emerald-950/40 border border-emerald-900 px-1 py-0.2 rounded font-sans">
                                LOW LATENCY
                              </span>
                            </div>

                            <button
                              onClick={stopTethering}
                              className="w-full py-1.5 bg-red-950/40 hover:bg-red-900/60 text-red-400 hover:text-white rounded-xl text-[10px] font-bold border border-red-900/40 transition-all font-sans"
                            >
                              Settle Escrow &amp; Disconnect
                            </button>
                          </>
                        );
                      })()}
                    </div>
                  ) : activeNodeId === activeEscrow.sharerId ? (
                    // VIEW FOR ACTIVE HOST / PROVIDER
                    <div className="p-3 bg-[#0c141a] border border-[#00d2ff]/25 rounded-2xl space-y-2.5 font-sans animate-in fade-in duration-300">
                      {(() => {
                        const buyerNode = nodes.find(n => n.id === activeEscrow.buyerId) || activeNode;
                        const spentUSDC = Math.min(2.50, activeEscrow.dataMeteredMB * activeEscrow.ratePerMB);

                        return (
                          <>
                            <div className="flex items-center justify-between border-b border-cyan-950 pb-2">
                              <span className="text-[10.5px] font-bold text-cyan-400 flex items-center gap-1.5 leading-none">
                                <span className="w-1.5 h-1.5 bg-cyan-400 rounded-full animate-ping" />
                                Tunnel Operator: Share active
                              </span>
                              <span className="px-1.5 py-0.5 bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 text-[8px] font-mono rounded font-sans">
                                HOSTING
                              </span>
                            </div>

                            <p className="text-[9.5px] text-zinc-400 leading-normal font-mono">
                              Subsidizing high-capacity wireless tunnel bandwidth for guest: <strong className="text-zinc-200">{buyerNode.username}</strong>
                            </p>

                            <div className="grid grid-cols-2 gap-2 text-center pt-0.5 font-mono">
                              <div className="bg-zinc-950 p-2 rounded-xl border border-zinc-900">
                                <div className="text-[7.5px] text-zinc-500 uppercase font-sans">Guest Downloaded</div>
                                <div className="text-xs font-black text-white mt-0.5">{activeEscrow.dataMeteredMB.toFixed(2)} MB</div>
                              </div>
                              <div className="bg-zinc-950 p-2 rounded-xl border border-zinc-900 bg-emerald-950/10 font-bold">
                                <div className="text-[7.5px] text-emerald-400 uppercase font-sans">Your Yield Pending</div>
                                <div className="text-xs font-black text-emerald-400 mt-0.5">+{spentUSDC.toFixed(4)} USDC</div>
                              </div>
                            </div>

                            {/* Host Price Changer mid-session */}
                            <div className="bg-zinc-950 p-2 rounded-xl border border-zinc-900 space-y-2">
                              <div className="flex justify-between items-center text-[8px] font-mono font-bold text-zinc-500 uppercase">
                                <span>Dynamic Discounting</span>
                                <span className="text-[#00D2FF] animate-pulse font-sans font-bold">Live adjustment</span>
                              </div>
                              <div className="flex items-center justify-between w-full">
                                <button 
                                  onClick={() => {
                                    const newPrice = Math.max(0.001, parseFloat((activeNode.pricePerMB - 0.001).toFixed(3)));
                                    const updated = nodes.map(n => {
                                      if (n.id === activeNodeId) return { ...n, pricePerMB: newPrice };
                                      return n;
                                    });
                                    onNodesUpdate(updated);
                                  }}
                                  className="w-7 h-7 flex items-center justify-center bg-zinc-900 hover:bg-zinc-800 text-zinc-200 rounded-lg font-mono font-bold text-xs"
                                >
                                  -
                                </button>
                                <span className="font-mono text-zinc-200 text-[10.5px] font-bold">{activeNode.pricePerMB.toFixed(3)} USDC/MB</span>
                                <button 
                                  onClick={() => {
                                    const newPrice = Math.max(0.001, parseFloat((activeNode.pricePerMB + 0.001).toFixed(3)));
                                    const updated = nodes.map(n => {
                                      if (n.id === activeNodeId) return { ...n, pricePerMB: newPrice };
                                      return n;
                                    });
                                    onNodesUpdate(updated);
                                  }}
                                  className="w-7 h-7 flex items-center justify-center bg-zinc-900 hover:bg-zinc-800 text-zinc-200 rounded-lg font-mono font-bold text-xs"
                                >
                                  +
                                </button>
                              </div>
                            </div>

                            <button
                              onClick={stopTethering}
                              className="w-full py-1.5 bg-red-950/40 hover:bg-red-900/60 text-red-400 hover:text-white rounded-xl text-[10px] font-bold border border-red-900/30 transition-all font-sans"
                            >
                              Terminate Tunnel &amp; Close Settlement
                            </button>
                          </>
                        );
                      })()}
                    </div>
                  ) : (
                    // VIEW FOR NEUTRAL THIRD PARTY ON ACTIVE TUNNEL
                    <div className="p-3 text-center text-zinc-500 text-[9px] bg-[#15171C] rounded-2xl border border-zinc-900 font-mono italic">
                      Hotspot tunnel occupied by anonymous peer network coordinates.
                    </div>
                  )
                )) : (
                // Setup and Select Hotspot Sharing Block (No active session)
                confirmingSharer ? (
                  // Buyer Confirm Accept or Decline Price Card overlay
                  <div className="p-3 bg-zinc-900 border border-teal-500/35 rounded-xl space-y-3">
                    <div className="text-xs font-bold text-teal-400 font-mono text-center flex items-center justify-center gap-1">
                      <ShieldCheck className="w-4 h-4" />
                      Accept / Decline Usage Rate
                    </div>
                    <p className="text-[10.5px] text-zinc-300 text-center leading-normal font-sans">
                      Connect to <strong>{confirmingSharer.username}'s</strong> hotspot? 
                      Your balance will transfer a fixed <strong>2.50 USDC</strong> prepaid fee directly for bandwidth at host's billing rate of:
                    </p>
                    <div className="p-2.5 bg-zinc-950 rounded-md text-center border border-zinc-800">
                      <span className="text-yellow-400 font-mono font-bold text-sm block">{confirmingSharer.pricePerMB.toFixed(2)} USDC / Megabyte</span>
                      <span className="text-[9px] text-zinc-500 uppercase tracking-widest block mt-0.5">Approx. {(2.50 / confirmingSharer.pricePerMB).toFixed(1)} MB maximum data quota</span>
                    </div>

                    <div className="grid grid-cols-2 gap-2 pt-1">
                      <button
                        onClick={() => setConfirmingSharer(null)}
                        className="py-1.5 bg-zinc-800 hover:bg-zinc-700 text-zinc-350 rounded font-semibold text-xs"
                      >
                        Decline Rate
                      </button>
                      <button
                        onClick={() => {
                          initiatePendingTetheringRequest(confirmingSharer);
                          setConfirmingSharer(null);
                        }}
                        className="py-1.5 bg-teal-500 hover:bg-teal-400 text-zinc-950 font-bold rounded text-xs"
                      >
                        Accept & Prepay
                      </button>
                    </div>
                  </div>
                ) : (
                  // General Idle State (No session, no confirming state)
                  <div className="space-y-3">
                    {/* The phone with internet access should set the price of usage */}
                    {activeNode.hasInternet && (
                      <>
                        <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-3 space-y-1.5">
                          <div className="flex items-center justify-between">
                            <div className="text-xs font-semibold text-zinc-100 flex items-center gap-1 font-sans">
                              <Zap className="w-3.5 h-3.5 text-amber-500 animate-pulse" />
                              Configure Hotspot Price
                            </div>
                            <span className="text-[8.5px] px-1 bg-teal-500/10 text-teal-400 border border-teal-500/20 font-mono rounded">
                              INTERNET SOURCE
                            </span>
                          </div>
                          <p className="text-[9.5px] text-zinc-500 leading-normal font-sans">
                            Set your billing price for mobile data relay reselling. Nearby peers will transact at this exact rate.
                          </p>
                          <div className="flex items-center justify-between bg-zinc-950 p-2 rounded border border-zinc-900 w-full font-mono">
                            <button 
                              onClick={() => {
                                const newPrice = Math.max(0.01, parseFloat((activeNode.pricePerMB - 0.01).toFixed(2)));
                                const updated = nodes.map(n => {
                                  if (n.id === activeNodeId) return { ...n, pricePerMB: newPrice };
                                  return n;
                                });
                                onNodesUpdate(updated);
                              }}
                              className="w-8 h-8 flex items-center justify-center bg-zinc-800 hover:bg-zinc-700 text-white rounded font-bold text-sm"
                            >
                              -
                            </button>
                            <div className="text-center">
                              <span className="text-xs font-bold text-teal-400">{activeNode.pricePerMB.toFixed(2)} USDC</span>
                              <span className="text-[8.5px] text-zinc-500 uppercase tracking-widest block">Per Megabyte</span>
                            </div>
                            <button 
                              onClick={() => {
                                const newPrice = Math.max(0.01, parseFloat((activeNode.pricePerMB + 0.01).toFixed(2)));
                                const updated = nodes.map(n => {
                                  if (n.id === activeNodeId) return { ...n, pricePerMB: newPrice };
                                  return n;
                                });
                                onNodesUpdate(updated);
                              }}
                              className="w-8 h-8 flex items-center justify-center bg-zinc-800 hover:bg-zinc-700 text-white rounded font-bold text-sm"
                            >
                              +
                            </button>
                          </div>
                        </div>

                        {/* Propose connection panel for Gateway Node */}
                        <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-3 space-y-2.5 font-sans animate-in fade-in duration-300">
                          <div className="text-xs font-semibold text-zinc-100 flex items-center gap-1.5">
                            <Send className="w-3.5 h-3.5 text-cyan-400" />
                            Propose Lease to Peer
                          </div>
                          <p className="text-[9.5px] text-zinc-500 leading-normal">
                            Offer cellular hotspot sharing to a neighboring mesh node. They get a real-time push notification to Accept or Decline.
                          </p>
                          
                          <div className="space-y-1">
                            <label className="text-[9px] text-zinc-400 uppercase font-mono tracking-wider">Select Client Node:</label>
                            <select 
                              value={targetClientId}
                              onChange={(e) => setTargetClientId(e.target.value)}
                              className="w-full bg-zinc-950 text-zinc-350 text-[10.5px] p-2 rounded border border-zinc-850 focus:outline-none focus:border-teal-500/50 font-sans"
                            >
                              <option value="">-- Choose Peer --</option>
                              {nodes.filter(n => n.id !== activeNodeId).map(peer => {
                                const dist = Math.hypot(activeNode.x - peer.x, activeNode.y - peer.y);
                                const inRange = dist < 200;
                                return (
                                  <option key={peer.id} value={peer.id} disabled={!inRange}>
                                    {peer.username} {!inRange ? '(Out of Range)' : `(~${dist.toFixed(0)}m away)`}
                                  </option>
                                );
                              })}
                            </select>
                          </div>

                          <div className="space-y-1">
                            <div className="flex justify-between items-center text-[9px] text-zinc-400 uppercase font-mono tracking-wider">
                              <span>Proposed Price:</span>
                              <span className="text-teal-400 font-bold font-mono">{proposedRate.toFixed(2)} USDC / MB</span>
                            </div>
                            <div className="flex items-center justify-between bg-zinc-950 border border-zinc-850 rounded p-1 w-full text-center">
                              <button 
                                type="button"
                                onClick={() => setProposedRate(r => Math.max(0.01, parseFloat((r - 0.01).toFixed(2))))}
                                className="w-7 h-7 flex items-center justify-center bg-zinc-800 hover:bg-zinc-700 text-zinc-100 text-xs font-bold rounded"
                              >
                                -
                              </button>
                              <span className="font-mono text-[10px] text-zinc-300 font-bold">{proposedRate.toFixed(2)} USDC/MB</span>
                              <button 
                                type="button"
                                onClick={() => setProposedRate(r => Math.max(0.01, parseFloat((r + 0.01).toFixed(2))))}
                                className="w-7 h-7 flex items-center justify-center bg-zinc-800 hover:bg-zinc-700 text-zinc-100 text-xs font-bold rounded"
                              >
                                +
                              </button>
                            </div>
                          </div>

                          <button
                            type="button"
                            onClick={sendLeaseProposal}
                            className="w-full py-1.5 bg-cyan-500 hover:bg-cyan-400 text-zinc-950 font-bold rounded text-xs transition-all flex items-center justify-center gap-1.5 font-sans"
                          >
                            <Send className="w-3 h-3 text-zinc-950" />
                            Send Connection offer
                          </button>
                        </div>
                      </>
                    )}

                    <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-3 flex flex-col gap-2">
                      <div className="flex items-center justify-between">
                        <div>
                          <div className="text-xs font-semibold text-zinc-100 font-sans">Broadcast Hotspot Access</div>
                          <p className="text-[10px] text-zinc-500 leading-normal mt-0.5 font-sans">Let others buy data from you over Solana USDC</p>
                        </div>
                        <button
                          onClick={() => {
                            const updated = nodes.map(n => {
                              if (n.id === activeNodeId) return { ...n, isSharingInternet: !n.isSharingInternet };
                              return n;
                            });
                            onNodesUpdate(updated);
                          }}
                          className="p-1 focus:outline-none"
                        >
                          {activeNode.isSharingInternet ? (
                            <div className="text-emerald-400 font-bold text-xs flex items-center gap-1 bg-emerald-500/10 border border-emerald-500/20 px-2 py-1 rounded">
                              <span>ADVERTISING AP</span>
                            </div>
                          ) : (
                            <div className="text-zinc-500 text-xs flex items-center gap-1 bg-zinc-950 border border-zinc-800 px-2 py-1 rounded">
                              <span>OFFLINE</span>
                            </div>
                          )}
                        </button>
                      </div>
                      {activeNode.isSharingInternet && (
                        <div className="mt-1 p-2 bg-zinc-950/80 rounded border border-zinc-900 text-[10px] space-y-1 text-zinc-400 font-mono">
                          <div className="text-teal-400 font-bold">SHARED CREDENTIALS:</div>
                          <div>SSID: <span className="text-white font-bold">Meshnet_{activeNode.username}</span></div>
                          <div>Password: <span className="text-teal-300">mesh_key_sec_{activeNode.publicKey.substring(0, 4)}</span></div>
                          <div className="text-[9px] text-zinc-500 border-t border-zinc-800 pt-1 mt-1 font-sans italic">
                            Provide this passcode to peers once they scan your Solana wallet QR address and submit a 2.50 USDC prepaid transfer.
                          </div>
                        </div>
                      )}
                    </div>

                    <div className="p-2 bg-zinc-950 rounded-xl border border-zinc-900">
                      <div className="px-1 text-[10px] font-bold text-zinc-500 uppercase tracking-wider mb-2 font-sans">
                        Available Prepaid Hotspots
                      </div>
                      {nodes.filter(n => n.id !== activeNodeId && n.isSharingInternet).length === 0 ? (
                        <p className="p-4 text-center text-[10.5px] text-zinc-500 leading-normal font-sans">
                          No mesh nodes are broadcasting hotspots right now. Activate sharing on Delta or Charlie to test.
                        </p>
                      ) : (
                        nodes.filter(n => n.id !== activeNodeId && n.isSharingInternet).map(sharer => {
                          const inRange = Math.hypot(activeNode.x - sharer.x, activeNode.y - sharer.y) < 200;

                          return (
                            <div key={sharer.id} className="p-2 bg-zinc-90 w-full flex items-center justify-between text-xs border border-zinc-900 rounded-lg mb-1.5 last:mb-0 bg-zinc-90 w-full flex items-center justify-between text-xs border border-zinc-900 rounded-lg mb-1.5 last:mb-0">
                              <div>
                                <div className="font-bold text-zinc-200 font-sans">{sharer.username}'s Hotspot</div>
                                <div className="text-[9.5px] font-bold text-teal-400 font-mono">{sharer.pricePerMB.toFixed(2)} USDC / MB</div>
                                <div className="text-[9px] text-zinc-600 font-mono">Solana Key: {sharer.publicKey.substring(0, 8)}...</div>
                              </div>
                              {inRange ? (
                                <button
                                  onClick={() => setConfirmingSharer(sharer)}
                                  className="px-2.5 py-1 bg-teal-500 hover:bg-teal-400 text-zinc-950 font-bold rounded text-[10px] transition-all"
                                >
                                  Prepay 2.50 USDC
                                </button>
                              ) : (
                                <span className="text-[9.5px] text-zinc-600 flex items-center gap-1 font-mono">
                                  <span className="w-1.5 h-1.5 bg-red-500 rounded-full" />
                                  Out of Range
                                </span>
                              )}
                            </div>
                          );
                        })
                      )}
                    </div>
                  </div>
                )
              )}
            </div>
          </div>
        )}

        {/* VIEW TAB WALLET */}
        {activeTab === 'wallet' && (
          <div className="flex-1 p-3 overflow-y-auto space-y-3">
            <h3 className="text-xs font-bold text-zinc-100 uppercase tracking-widest text-teal-400 font-mono flex items-center gap-1">
              <Wallet className="w-3.5 h-3.5" />
              USDC Escrow Wallet
            </h3>

            {/* Non-Custodial Wallet activation banner */}
            {!mnemonicCreated ? (
              <div className="bg-zinc-900 p-3 rounded-xl border border-zinc-800 text-center space-y-3">
                <div className="w-9 h-9 bg-zinc-950 border border-zinc-800 rounded-full flex items-center justify-center mx-auto text-teal-400">
                  <Wallet className="w-5 h-5" />
                </div>
                <div className="leading-tight">
                  <div className="text-xs font-bold text-zinc-200">Generate Solana Wallet</div>
                  <p className="text-[9.5px] text-zinc-500 max-w-[180px] mx-auto mt-1 leading-normal">
                    Create secure 12-word recovery mnemonic to receive/send USDC stablecoins offline.
                  </p>
                </div>
                <button
                  onClick={() => setMnemonicCreated(true)}
                  className="px-3 py-1 bg-teal-500 hover:bg-teal-400 text-zinc-950 text-xs font-bold rounded-lg transition-all"
                >
                  Generate Keychain Seed
                </button>
              </div>
            ) : (
              // Active Wallet balance readouts
              <div className="space-y-3">
                <div className="bg-zinc-900 p-4 rounded-xl border border-zinc-800 text-center">
                  <span className="text-[8px] text-teal-500 font-bold tracking-widest uppercase">SOLANA PROTOCOL WALLET</span>
                  <div className="text-2xl font-bold font-mono text-zinc-100 mt-1">
                    {activeNode.balanceUSDC.toFixed(2)} USDC
                  </div>
                  <p className="text-[8px] font-mono text-zinc-600 truncate mt-1">
                    AxG77...29dE (Solana Devnet)
                  </p>

                  <div className="mt-3 flex gap-1.5 justify-center">
                    <button
                      onClick={() => {
                        const updated = nodes.map(n => {
                          if (n.id === activeNodeId) return { ...n, balanceUSDC: n.balanceUSDC + 10.0 };
                          return n;
                        });
                        onNodesUpdate(updated);
                        
                        onAddBlockchainLog({
                          id: `tx-log-${Date.now()}`,
                          timestamp: new Date().toLocaleTimeString(),
                          event: 'DEVNET_FAUCET_MINT',
                          details: `Minted 10.00 USDC for testing in wallet: AxG77...29dE`,
                          txHash: `sol-mint-${Math.random().toString(36).substring(4, 12)}`,
                          network: 'Solana'
                        });
                      }}
                      className="px-2.5 py-1 bg-zinc-950 border border-zinc-800 hover:bg-zinc-800/80 rounded text-[9px] text-teal-300 font-bold uppercase tracking-wider"
                    >
                      + Mint 10 USDC
                    </button>
                  </div>
                </div>

                {/* Secure backup seed block */}
                <div className="p-2.5 bg-zinc-950 border border-zinc-900 rounded-lg">
                  <div className="text-[8px] text-amber-500 font-bold uppercase tracking-wider mb-1">Mnemonic Secret Phrase</div>
                  <p className="text-[9px] font-mono text-zinc-500 leading-relaxed italic">{SIMULATED_MNEMONIC}</p>
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* App Operating Tab bar navigation */}
      <div className="bg-[#15171C] border-t border-slate-900 h-11 shrink-0 grid grid-cols-4 items-center">
        <button
          onClick={() => { setActiveTab('mesh'); setSelectedPeerId(null); }}
          className={`flex flex-col items-center justify-center p-1 transition-all ${activeTab === 'mesh' ? 'text-cyan-400' : 'text-slate-500 hover:text-slate-400'}`}
        >
          <Network className="w-4 h-4" />
          <span className="text-[8px] font-bold mt-1 uppercase tracking-widest font-mono">Mesh</span>
        </button>

        <button
          onClick={() => { setActiveTab('chats'); }}
          className={`flex flex-col items-center justify-center p-1 transition-all relative ${activeTab === 'chats' ? 'text-cyan-400' : 'text-slate-500 hover:text-slate-400'}`}
        >
          <MessageSquare className="w-4 h-4" />
          <span className="text-[8px] font-bold mt-1 uppercase tracking-widest font-mono font-sans">Chats</span>
          {/* High-fidelity red unread circle badge from screenshots */}
          <span className="absolute top-1 right-6 bg-red-500 text-white font-extrabold text-[8px] h-3.5 w-3.5 rounded-full flex items-center justify-center scale-90 border border-[#15171C]">
            7
          </span>
        </button>

        <button
          onClick={() => { setActiveTab('wallet'); setSelectedPeerId(null); }}
          className={`flex flex-col items-center justify-center p-1 transition-all ${activeTab === 'wallet' ? 'text-cyan-400' : 'text-slate-500 hover:text-slate-400'}`}
        >
          <Wallet className="w-4 h-4" />
          <span className="text-[8px] font-bold mt-1 uppercase tracking-widest font-mono">Wallet</span>
        </button>

        <button
          onClick={() => { setActiveTab('settings'); setSelectedPeerId(null); }}
          className={`flex flex-col items-center justify-center p-1 transition-all ${activeTab === 'settings' ? 'text-cyan-400' : 'text-slate-500 hover:text-slate-400'}`}
        >
          <Sliders className="w-4 h-4" />
          <span className="text-[8px] font-bold mt-1 uppercase tracking-widest font-mono">Settings</span>
        </button>
      </div>

      {/* Screen bottom bar spacer */}
      <div className="bg-[#0A0B0D] h-3 shrink-0 flex justify-center items-end pb-1 relative">
        <div className="w-24 h-[3px] bg-slate-800 rounded-full" />
      </div>
    </div>
  );
}
