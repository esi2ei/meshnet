import React from 'react';
import { BlockchainLog } from '../types';
import { Terminal, Database, Shield, Zap, RefreshCw, Cpu, Layers } from 'lucide-react';

interface SystemInfoProps {
  logs: BlockchainLog[];
  onClearLogs: () => void;
}

export default function SystemInfo({ logs, onClearLogs }: SystemInfoProps) {
  return (
    <div className="flex flex-col h-full bg-[#15171C] border border-slate-800 rounded-3xl overflow-hidden shadow-2xl">
      {/* Exporter Header */}
      <div className="p-4 bg-[#15171C] border-b border-slate-800 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="p-1.5 bg-cyan-500/10 text-cyan-400 rounded-lg">
            <Terminal className="w-4 h-4" />
          </span>
          <div>
            <h2 className="text-sm font-semibold text-white font-sans uppercase tracking-wider">
              Distributed Protocol &amp; Ledger Audit Log
            </h2>
            <p className="text-[11px] text-slate-400 mt-1 font-sans">
              Real-time monitoring of P2P double ratchets, multi-hop packet hops, and Solana virtual anchor claims.
            </p>
          </div>
        </div>

        <button
          onClick={onClearLogs}
          className="text-[11px] text-slate-300 font-mono font-medium hover:text-white border border-slate-800 hover:bg-slate-900 px-3 py-1 rounded-lg transition-all active:scale-95"
        >
          Flush Log Buffer
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 flex-1 divide-y md:divide-y-0 md:divide-x divide-slate-800 min-h-0 overflow-hidden bg-[#15171C]">
        
        {/* Blockchain transactions audit stream */}
        <div className="flex flex-col min-h-0 overflow-hidden">
          <div className="px-3.5 py-2 bg-[#0A0B0D]/40 border-b border-slate-800 flex items-center justify-between">
            <span className="text-[10px] uppercase font-bold text-slate-400 tracking-wider flex items-center gap-1.5">
              <Database className="w-3.5 h-3.5 text-cyan-400" />
              Claims &amp; Routing Events ({logs.length})
            </span>
            <span className="text-[9px] text-cyan-400 bg-cyan-500/10 border border-cyan-500/20 px-1.5 py-0.5 rounded font-mono uppercase">
              Solana Anchor Devnet
            </span>
          </div>

          <div className="flex-1 overflow-y-auto p-3 space-y-2 font-mono text-[10.5px]">
            {logs.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center p-8 text-slate-500 text-center">
                <Layers className="w-8 h-8 opacity-20 mb-2" />
                No events recorded. Generate some signals in the emulator!
              </div>
            ) : (
              [...logs].reverse().map((log) => {
                const isEscrow = log.event.includes('ESCROW') || log.event.includes('DEVNET');
                return (
                  <div
                    key={log.id}
                    className="p-2.5 bg-[#0A0B0D] w-full border border-slate-800/80 rounded-xl space-y-1 hover:border-slate-700 transition-all"
                  >
                    <div className="flex items-center justify-between text-slate-400 animate-fadeIn">
                      <span className={`px-1.5 py-0.5 rounded text-[8.5px] font-bold ${
                        isEscrow ? 'bg-amber-500/10 text-amber-400' : 'bg-cyan-500/10 text-cyan-400'
                      }`}>
                        {log.event}
                      </span>
                      <span className="text-slate-500 text-[9px]">{log.timestamp}</span>
                    </div>
                    <p className="text-slate-300 leading-normal">{log.details}</p>
                    <div className="text-[9px] text-slate-500 flex items-center justify-between leading-none pt-0.5">
                      <span className="truncate max-w-[120px]">Hash: {log.txHash}</span>
                      <span className="text-slate-600">Net: {log.network}</span>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* Cryptographic explanation of Signal Protocol Double Ratchet */}
        <div className="p-4 overflow-y-auto space-y-4">
          <div className="text-[10px] uppercase font-bold text-slate-400 tracking-wider flex items-center gap-1.5 border-b border-slate-800 pb-2">
            <Shield className="w-3.5 h-3.5 text-cyan-400" />
            Double Ratchet Cryptography Protocol
          </div>

          <div className="space-y-3.5 text-[11px] text-slate-450 text-slate-400 leading-relaxed">
            <div className="space-y-1">
              <span className="font-bold text-slate-200 block">1. Perfect Forward Secrecy (PFS)</span>
              <p>
                Every message corresponds to a <strong>single ephemeral Diffie-Hellman key tick</strong>. Even if a private identity key is extracted from physical device inspection later, historically caught encrypted packages cannot be compromised because previous keys have been discarded from RAM.
              </p>
            </div>

            <div className="space-y-1">
              <span className="font-bold text-slate-200 block">2. Self-Healing Ratchets</span>
              <p>
                If a hacker intercepting wireless packets derives a message key, they will lose track immediately upon the next peer handshake response exchange, because the ratchet updates the sending/receiving chains using brand new random values.
              </p>
            </div>

            <div className="space-y-1">
              <span className="font-bold text-slate-200 block">3. Multi-Hop Hopping store-and-forward</span>
              <p>
                Mesh packets are packed as standard <strong>XCha20 secret symmetric boxes</strong> with public destination wrappers. Intermediate relays (like Node B) only forward the package envelope, having completely zero keys to decrypt the internal messages.
              </p>
            </div>

            {/* Visual key agreement graphic */}
            <div className="bg-[#0A0B0D] p-3 rounded-xl border border-slate-800 font-mono text-[9px] space-y-1.5 text-cyan-400">
              <div className="font-bold text-slate-300 uppercase tracking-widest text-center mb-1">Key Derivation Tree</div>
              <div className="text-center text-slate-500">RootKey (HKDF)</div>
              <div className="text-center text-slate-500">▼</div>
              <div className="flex justify-between px-6">
                <span className="text-amber-400 font-bold">SendingChain</span>
                <span className="text-cyan-400 font-bold">ReceivingChain</span>
              </div>
              <div className="flex justify-between px-6 text-slate-400">
                <span>└ MsgKey0 ➔ Encrypt</span>
                <span>└ MsgKey0 ➔ Decrypt</span>
              </div>
            </div>
          </div>
        </div>

      </div>
    </div>
  );
}
