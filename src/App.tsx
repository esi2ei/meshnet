import React, { useState } from 'react';
import MeshSimulator from './components/MeshSimulator';
import PhoneWorkspace from './components/PhoneWorkspace';
import SystemInfo from './components/SystemInfo';
import CodeExporter from './components/CodeExporter';
import { MeshNode, MeshMessage, BlockchainLog } from './types';
import { Shield, Smartphone, HardDrive, Cpu, Wifi, BookOpen, AlertCircle } from 'lucide-react';

export default function App() {
  const [nodes, setNodes] = useState<MeshNode[]>([
    {
      id: 'NODE-1',
      username: 'Charlie',
      avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=100&q=80',
      x: 120,
      y: 180,
      isSharingInternet: false,
      pricePerMB: 0.12,
      balanceUSDC: 15.00,
      hasInternet: false,
      batteryPercentage: 88,
      isOnline: true,
      publicKey: 'pub_key_charlie_z89Ed32',
      preKeyId: 2130,
      ephemeralKey: 'eph_charlie_k99d'
    },
    {
      id: 'NODE-2',
      username: 'Delta',
      avatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=100&q=80',
      x: 250,
      y: 110,
      isSharingInternet: true, // Delta shares high-speed cell cellular!
      pricePerMB: 0.15,
      balanceUSDC: 2.50,
      hasInternet: true, // Directly online
      batteryPercentage: 94,
      isOnline: true,
      publicKey: 'pub_key_delta_a34Bc89',
      preKeyId: 9005,
      ephemeralKey: 'eph_delta_m221'
    },
    {
      id: 'NODE-3',
      username: 'Echo',
      avatar: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=100&q=80',
      x: 200,
      y: 280,
      isSharingInternet: false,
      pricePerMB: 0.10,
      balanceUSDC: 4.00,
      hasInternet: false,
      batteryPercentage: 62,
      isOnline: true,
      publicKey: 'pub_key_echo_c77De90',
      preKeyId: 5431,
      ephemeralKey: 'eph_echo_v88b'
    }
  ]);

  const [messages, setMessages] = useState<MeshMessage[]>([]);
  const [activeNodeId, setActiveNodeId] = useState<string>('NODE-1');

  // Multi-hop routing states that bridge simulator coordination with phone framing inputs
  const [isSimulatingHop, setIsSimulatingHop] = useState(false);
  const [simulatedPath, setSimulatedPath] = useState<string[]>([]);
  const [currentHopNodeId, setCurrentHopNodeId] = useState<string | null>(null);

  const [blockchainLogs, setBlockchainLogs] = useState<BlockchainLog[]>([
    {
      id: 'seed-log-1',
      timestamp: '17:34:09 PM',
      event: 'MESH_NETWORK_SETUP',
      details: 'MeshNet local radio instances configured over Wi-Fi Direct and BLE interface stubs.',
      txHash: 'ready-state-hash',
      network: 'Solana'
    }
  ]);

  const handleAddBlockchainLog = (newLog: BlockchainLog) => {
    setBlockchainLogs(prev => [...prev, newLog]);
  };

  const handleClearLogs = () => {
    setBlockchainLogs([]);
  };

  const activeNodeUser = nodes.find(n => n.id === activeNodeId)?.username || 'User';

  return (
    <div className="min-h-screen bg-[#0A0B0D] text-slate-200 flex flex-col font-sans transition-all duration-300">
      
      {/* Top Main Navigation HUD */}
      <div className="p-6 pb-0">
        <header className="flex justify-between items-center bg-[#15171C] border border-slate-800 rounded-2xl px-6 py-4 shadow-xl">
          <div className="flex items-center gap-4">
            <div className="w-8 h-8 bg-cyan-500 rounded-lg flex items-center justify-center">
              <svg className="w-5 h-5 text-black" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
            </div>
            <div>
              <div className="text-xl font-display font-bold tracking-wide text-white flex items-center gap-2">
                MESH<span className="text-cyan-400">NET</span>
                <span className="px-2 py-0.5 bg-green-500/10 text-green-400 text-[10px] font-mono rounded border border-green-500/35 uppercase font-bold tracking-wider">
                  BROWSER SIMULATION
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-1 font-medium font-display">
                Offline Mesh Concept · Browser Simulation Only
              </p>
            </div>
          </div>
          <div className="flex items-center gap-6">
            <div className="hidden md:flex flex-col items-end">
              <span className="text-[10px] text-slate-500 uppercase font-mono tracking-widest leading-none">Demo Wallet (Not Connected)</span>
              <span className="text-xs font-mono text-cyan-400 mt-1">No wallet connected</span>
            </div>
            <div className="w-10 h-10 rounded-full border-2 border-slate-705 bg-slate-800 flex items-center justify-center">
              <div className="w-2 h-2 bg-amber-500 rounded-full"></div>
            </div>
          </div>
        </header>
      </div>

      {/* Primary Simulator Split Pane Board */}
      <main className="flex-grow p-6 grid grid-cols-1 lg:grid-cols-12 gap-6 max-w-[1700px] mx-auto w-full overflow-hidden">
        
        {/* LEFT COLUMN: Visual Network Mesh Map & Protocol Logger logs (8 of 12 widths) */}
        <div className="lg:col-span-8 flex flex-col gap-6 min-h-0">
          
          {/* Node Topological Map Component */}
          <div className="relative bg-[#15171C] border border-slate-800 rounded-3xl overflow-hidden p-6 shadow-xl">
            <div className="mb-4">
              <h2 className="text-sm font-semibold uppercase tracking-widest text-slate-400 flex items-center gap-2">
                <Smartphone className="w-4 h-4 text-cyan-400" />
                Local Mesh Topology Space
              </h2>
              <p className="text-xs text-slate-500 mt-1 font-sans italic">
                Illustrative topology only — this browser view is not connected to phone radios. Drag nodes to explore simulated RF links.
              </p>
            </div>

            <MeshSimulator
              nodes={nodes}
              onNodesUpdate={setNodes}
              messages={messages}
              onMessagesUpdate={setMessages}
              activeNodeId={activeNodeId}
              setActiveNodeId={setActiveNodeId}
              isSimulatingHop={isSimulatingHop}
              setIsSimulatingHop={setIsSimulatingHop}
              simulatedPath={simulatedPath}
              setSimulatedPath={setSimulatedPath}
              currentHopNodeId={currentHopNodeId}
              setCurrentHopNodeId={setCurrentHopNodeId}
            />

            {/* Instruction Callout for Simulation Mechanics */}
            <div className="mt-4 flex gap-3 p-4 bg-[#0A0B0D] border border-slate-800 rounded-2xl text-xs text-slate-400 leading-relaxed">
              <AlertCircle className="w-5 h-5 text-amber-500 shrink-0 mt-0.5" />
              <div>
                <span className="font-semibold text-slate-205">How to test Store-and-Forward routing:</span> Demo only: separate the sample nodes to explore simulated store-and-forward routing. No real BLE packets are transmitted by this browser screen.
              </div>
            </div>
          </div>

          {/* Decentralized Smart Contract Ledger Console Logs and Signal Protocol explaining panel */}
          <div className="flex-grow h-[350px] min-h-0">
            <SystemInfo
              logs={blockchainLogs}
              onClearLogs={handleClearLogs}
            />
          </div>

        </div>

        {/* MIDDLE COLUMN: High Fidelity Virtual Emulator Phone Screen frame (4 of 12 widths) */}
        <div className="lg:col-span-4 flex justify-center w-full">
          <div className="flex flex-col items-center w-full max-w-[340px]">
            
            {/* Display which phone screen is currently loaded */}
            <div className="mb-3 text-center">
              <span className="text-[10px] font-bold uppercase text-slate-500 font-mono tracking-widest">
                Active Viewscreen
              </span>
              <h2 className="text-xs font-semibold text-slate-300 mt-1 font-display uppercase tracking-wide">
                {activeNodeUser}'s Console ({activeNodeId})
              </h2>
            </div>

            {/* Simulated hardware wrapper */}
            <PhoneWorkspace
              nodes={nodes}
              onNodesUpdate={setNodes}
              messages={messages}
              onMessagesUpdate={setMessages}
              activeNodeId={activeNodeId}
              isSimulatingHop={isSimulatingHop}
              setIsSimulatingHop={setIsSimulatingHop}
              simulatedPath={simulatedPath}
              setSimulatedPath={setSimulatedPath}
              currentHopNodeId={currentHopNodeId}
              setCurrentHopNodeId={setCurrentHopNodeId}
              blockchainLogs={blockchainLogs}
              onAddBlockchainLog={handleAddBlockchainLog}
            />
          </div>
        </div>

      </main>

      {/* FULL-WIDTH EXPANSION: Code Exporter view offering complete structural packages */}
      <div className="px-6 pb-12 pt-4 max-w-[1700px] mx-auto w-full">
        <div className="bg-[#15171C] border border-slate-800 rounded-3xl overflow-hidden p-6 shadow-xl">
          <div className="mb-4">
            <h2 className="text-sm font-semibold uppercase tracking-widest text-slate-300 flex items-center gap-2">
              <HardDrive className="w-4 h-4 text-cyan-400" />
              Experimental Mobile Source Snippets (Not a Deployable App)
            </h2>
            <p className="text-xs text-slate-500 mt-1 font-sans">
              These snippets are design references only. The native Android BLE prototype lives in mobile/; iOS, payment and production security are not implemented.
            </p>
          </div>
          
          {/* Main Embeddable Code Exporter */}
          <CodeExporter />
        </div>
      </div>

    </div>
  );
}
