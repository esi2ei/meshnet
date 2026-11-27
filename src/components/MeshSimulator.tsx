import React, { useState, useEffect, useRef } from 'react';
import { MeshNode, MeshMessage, MessageType, MessageStatus } from '../types';
import { Radar, Plus, Trash2, Zap, HelpCircle, Activity, ShieldAlert, CheckCircle } from 'lucide-react';

interface MeshSimulatorProps {
  nodes: MeshNode[];
  onNodesUpdate: (nodes: MeshNode[]) => void;
  messages: MeshMessage[];
  onMessagesUpdate: (messages: MeshMessage[]) => void;
  activeNodeId: string;
  setActiveNodeId: (id: string) => void;
  isSimulatingHop: boolean;
  setIsSimulatingHop: (val: boolean) => void;
  simulatedPath: string[];
  setSimulatedPath: (path: string[]) => void;
  currentHopNodeId: string | null;
  setCurrentHopNodeId: (id: string | null) => void;
}

export default function MeshSimulator({
  nodes,
  onNodesUpdate,
  messages,
  onMessagesUpdate,
  activeNodeId,
  setActiveNodeId,
  isSimulatingHop,
  setIsSimulatingHop,
  simulatedPath,
  setSimulatedPath,
  currentHopNodeId,
  setCurrentHopNodeId,
}: MeshSimulatorProps) {
  const [draggedNodeId, setDraggedNodeId] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);

  // Default coordinate scaling bounds
  const RF_RANGE = 200; // in pixels

  // Helper to determine if nodes are linked directly in RF range
  const isLinked = (n1: MeshNode, n2: MeshNode) => {
    return Math.hypot(n1.x - n2.x, n1.y - n2.y) < RF_RANGE;
  };

  // Compute links for drawing lines
  const links: [MeshNode, MeshNode][] = [];
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      if (isLinked(nodes[i], nodes[j])) {
        links.push([nodes[i], nodes[j]]);
      }
    }
  }

  // Handle Dragging
  const handlePointerDown = (nodeId: string, e: React.PointerEvent<HTMLDivElement>) => {
    if (isSimulatingHop) return;
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    setDraggedNodeId(nodeId);
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggedNodeId || !containerRef.current || isSimulatingHop) return;
    const rect = containerRef.current.getBoundingClientRect();
    let x = e.clientX - rect.left;
    let y = e.clientY - rect.top;

    // Boundary clamps
    x = Math.max(20, Math.min(rect.width - 20, x));
    y = Math.max(20, Math.min(rect.height - 20, y));

    const updated = nodes.map((node) =>
      node.id === draggedNodeId ? { ...node, x, y } : node
    );
    onNodesUpdate(updated);
  };

  const handlePointerUp = () => {
    setDraggedNodeId(null);
  };

  // Add a new mesh node
  const addNewNode = () => {
    const names = ['Charlie', 'Delta', 'Echo', 'Foxtrot', 'Golf', 'Hotel', 'India'];
    const count = nodes.length;
    const nextName = names[count - 1] || `PeerNode-${count + 1}`;
    
    // Position it somewhat randomly in the center range
    const x = 120 + Math.random() * 200;
    const y = 80 + Math.random() * 200;
    
    // Create new node object
    const newNode: MeshNode = {
      id: `NODE-${count + 1}`,
      username: nextName,
      avatar: '', // use local initials; do not request remote images
      x,
      y,
      isSharingInternet: false,
      pricePerMB: 0.15,
      balanceUSDC: 5.0,
      hasInternet: false,
      batteryPercentage: Math.floor(65 + Math.random() * 32),
      isOnline: true,
      publicKey: `pub_${Math.random().toString(36).substring(4, 16)}`,
      preKeyId: Math.floor(1000 + Math.random() * 9000),
      ephemeralKey: `eph_${Math.random().toString(36).substring(4, 16)}`,
    };

    onNodesUpdate([...nodes, newNode]);
  };

  // Delete a mesh node
  const deleteNode = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (nodes.length <= 2) return; // Maintain at least two nodes for chat
    if (id === activeNodeId) {
      const remaining = nodes.filter((n) => n.id !== id);
      setActiveNodeId(remaining[0].id);
      onNodesUpdate(remaining);
    } else {
      onNodesUpdate(nodes.filter((n) => n.id !== id));
    }
  };

  return (
    <div
      ref={containerRef}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      className="relative flex flex-col h-[380px] bg-[#0A0B0D] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl select-none"
    >
      {/* Background Tech Radar Rings */}
      <div className="absolute inset-0 pointer-events-none opacity-20">
        <div className="absolute top-1/2 left-1/2 w-[300px] h-[300px] border border-cyan-500/20 rounded-full border-dashed transform -translate-x-1/2 -translate-y-1/2 animate-pulse" />
        <div className="absolute top-1/2 left-1/2 w-[550px] h-[550px] border border-cyan-500/10 rounded-full border-dashed transform -translate-x-1/2 -translate-y-1/2" />
        <div className="absolute inset-0 bg-[radial-gradient(#334155_1px,transparent_1px)] [background-size:24px_24px]" />
      </div>

      {/* Control bar */}
      <div className="absolute top-3 left-3 right-3 z-10 flex items-center justify-between pointer-events-auto">
        <div className="flex items-center gap-1.5 px-2.5 bg-slate-900/90 border border-slate-800 rounded-lg py-1 backdrop-blur text-[10px] text-slate-300">
          <Radar className="w-3.5 h-3.5 text-cyan-400 animate-spin" style={{ animationDuration: '4s' }} />
          <span className="font-semibold text-slate-200 uppercase tracking-widest font-mono">Simulated RF Map</span>
          <span className="w-1.5 h-1.5 bg-amber-500 rounded-full" />
        </div>

        <div className="flex items-center gap-1.5">
          <button
            onClick={addNewNode}
            disabled={isSimulatingHop}
            className="flex items-center gap-1 px-3 py-1 bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-400 text-[10px] rounded-lg transition-all active:scale-95 text-xs font-mono font-medium disabled:opacity-50 uppercase tracking-wide"
          >
            <Plus className="w-3.5 h-3.5" />
            <span>Add Node</span>
          </button>
        </div>
      </div>

      {/* SVG Canvas for P2P connection links */}
      <svg className="absolute inset-0 w-full h-full pointer-events-none">
        <defs>
          <linearGradient id="link-grad" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#06b6d4" stopOpacity="0.5" />
            <stop offset="100%" stopColor="#3b82f6" stopOpacity="0.5" />
          </linearGradient>
        </defs>

        {/* Draw Link cables */}
        {links.map(([n1, n2], idx) => (
          <g key={idx}>
            <line
              x1={n1.x}
              y1={n1.y}
              x2={n2.x}
              y2={n2.y}
              stroke="url(#link-grad)"
              strokeWidth="1.5"
              strokeDasharray="4 2"
              className="animate-[dash_20s_linear_infinite]"
            />
            {/* Tiny packet particles sliding along active links */}
            <circle r="2.5" fill="#06b6d4" className="animate-[pulse_1.5s_infinite]">
              <animateMotion
                dur="3s"
                repeatCount="indefinite"
                path={`M ${n1.x} ${n1.y} L ${n2.x} ${n2.y}`}
              />
            </circle>
          </g>
        ))}

        {/* Draw active multi-hop store-and-forward animation path */}
        {isSimulatingHop && simulatedPath.length > 1 && (
          <path
            d={`M ${simulatedPath.map(id => {
              const node = nodes.find(n => n.id === id);
              return node ? `${node.x} ${node.y}` : '';
            }).join(' L ')}`}
            fill="none"
            stroke="#f59e0b"
            strokeWidth="3"
            strokeLinecap="round"
            className="animate-pulse"
          />
        )}
      </svg>

      {/* Display Interconnected/Offline warning status */}
      <div className="absolute bottom-3 left-3 pointer-events-none text-[10px] text-slate-500 flex flex-col gap-1 max-w-[200px]">
        <div className="flex items-center gap-1.5 text-slate-400">
          <Activity className="w-3.5 h-3.5 text-cyan-500" />
          <span>Drag nodes to re-route dynamically</span>
        </div>
        <div className="flex items-center gap-1.5 text-slate-400">
          <Zap className="w-3.5 h-3.5 text-amber-500" />
          <span>RF Link limit: {RF_RANGE}px (based on simulated BLE RSSI)</span>
        </div>
      </div>

      {/* Interactive Drag & Drop Nodes overlay */}
      <div className="absolute inset-0 pointer-events-auto">
        {nodes.map((node) => {
          const isSelected = activeNodeId === node.id;
          const isCurrentHop = currentHopNodeId === node.id;
          const isPartOfPath = simulatedPath.includes(node.id);

          return (
            <div
              key={node.id}
              style={{
                left: node.x - 22,
                top: node.y - 22,
                position: 'absolute',
                cursor: draggedNodeId === node.id ? 'grabbing' : 'grab',
              }}
              onPointerDown={(e) => handlePointerDown(node.id, e)}
              onMouseEnter={() => setHoveredNodeId(node.id)}
              onMouseLeave={() => setHoveredNodeId(null)}
              onClick={() => {
                if (!isSimulatingHop) setActiveNodeId(node.id);
              }}
              className={`group z-20 flex touch-none flex-col items-center transition-transform ${
                isSelected ? 'scale-110' : 'hover:scale-105'
              }`}
            >
              <div className="relative">
                {/* Ring representing selected or hopping animation */}
                <div
                  className={`absolute -inset-1.5 rounded-full blur-[2px] transition-all ${
                    isCurrentHop
                      ? 'bg-amber-500 animate-[ping_1.2s_infinite]'
                      : isPartOfPath
                      ? 'bg-amber-500/50 border border-amber-400 animate-pulse'
                      : isSelected
                      ? 'bg-cyan-500/35 border border-cyan-400/80 animate-pulse'
                      : 'bg-transparent border border-transparent'
                  }`}
                />

                {/* Main Node avatar block */}
                <div
                  className={`relative w-11 h-11 rounded-full flex items-center justify-center border-2 text-xs font-bold font-mono select-none overflow-hidden ${
                    isSelected
                      ? 'border-cyan-400 bg-cyan-950/40 text-cyan-200 shadow-[0_0_12px_rgba(6,182,212,0.4)]'
                      : node.id === 'NODE-1'
                      ? 'border-blue-400 bg-blue-950/90 text-blue-200'
                      : 'border-slate-700 bg-slate-905 bg-[#15171C] text-slate-300'
                  }`}
                >
                  {node.username.substring(0, 2).toUpperCase()}

                  {/* Tiny power charging / sharing indicators */}
                  {node.isSharingInternet && (
                    <span className="absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-amber-500 text-[8px] font-bold text-zinc-950 shadow-md">
                      <Zap className="w-2.5 h-2.5" />
                    </span>
                  )}

                  {node.hasInternet && (
                    <span className="absolute -bottom-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-blue-500 text-[8px] font-bold text-zinc-100 shadow-md">
                      🌍
                    </span>
                  )}
                </div>
              </div>

              {/* Node title */}
              <div className="mt-1 flex flex-col items-center">
                <span
                  className={`px-1.5 py-0.5 text-[9px] rounded font-medium shadow-md flex items-center gap-1 ${
                    isSelected
                      ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 font-bold'
                      : 'bg-slate-900 border border-slate-800 text-slate-455 text-slate-400'
                  }`}
                >
                  {node.username}
                  {node.id === 'NODE-1' && <span className="text-[7px] bg-blue-500/20 text-blue-400 px-0.5 rounded font-mono font-normal">S</span>}
                </span>

                {/* Drag Delete button hover overlay */}
                {hoveredNodeId === node.id && nodes.length > 2 && (
                  <button
                    onClick={(e) => deleteNode(node.id, e)}
                    className="absolute -top-3 -left-3 p-1 bg-red-950 hover:bg-red-900 border border-red-800 rounded-full text-red-400 hover:text-red-200 shadow-lg active:scale-90"
                  >
                    <Trash2 className="w-2.5 h-2.5" />
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
