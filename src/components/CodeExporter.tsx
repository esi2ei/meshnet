import React, { useState } from 'react';
import { FLUTTER_CODEBASE } from '../data/flutterCodebase';
import { CodeFile } from '../types';
import { FileCode, Copy, Check, Search, Download, Terminal, ChevronRight, CheckCircle2 } from 'lucide-react';

export default function CodeExporter() {
  const [selectedFile, setSelectedFile] = useState<CodeFile>(FLUTTER_CODEBASE[0]);
  const [copied, setCopied] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  const filteredFiles = FLUTTER_CODEBASE.filter(file =>
    file.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    file.filepath.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const handleCopy = () => {
    navigator.clipboard.writeText(selectedFile.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const getLanguageColor = (lang: string) => {
    switch (lang) {
      case 'dart': return 'bg-sky-500/10 text-sky-400 border-sky-500/20';
      case 'kotlin': return 'bg-purple-500/10 text-purple-400 border-purple-500/20';
      case 'swift': return 'bg-orange-500/10 text-orange-400 border-orange-500/20';
      case 'yaml': return 'bg-yellow-500/10 text-yellow-400 border-yellow-500/20';
      default: return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
    }
  };

  return (
    <div className="flex flex-col h-full bg-[#15171C] border border-slate-800 rounded-2xl overflow-hidden shadow-2xl">
      {/* Exporter Header */}
      <div className="p-4 bg-[#15171C] border-b border-slate-800 flex items-center justify-between">
        <div>
          <h2 className="text-sm font-semibold text-white flex items-center gap-2 font-mono uppercase tracking-wider">
            <span className="p-1 px-1.5 bg-cyan-500/10 text-cyan-400 rounded border border-cyan-500/25">
              <FileCode className="w-4 h-4" />
            </span>
            Mobile Codebase Explorer &amp; Blueprint Export
          </h2>
          <p className="text-xs text-slate-400 mt-1.5 font-sans">
            Browse and download ready-to-run Flutter (Dart), Android (Kotlin), and iOS (Swift) native SDK layouts.
          </p>
        </div>
      </div>

      <div className="flex flex-1 overflow-hidden divide-x divide-slate-800">
        {/* Sidebar file tree */}
        <div className="w-1/3 flex flex-col bg-[#0A0B0D]/80">
          <div className="p-3 border-b border-slate-800">
            <div className="relative">
              <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-slate-500" />
              <input
                type="text"
                placeholder="Search files..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pl-8 pr-3 py-1.5 bg-[#15171C]/50 border border-slate-800 text-xs rounded-lg text-slate-200 placeholder-slate-550 placeholder-slate-500 focus:outline-none focus:border-slate-700"
              />
            </div>
          </div>

          <div className="flex-1 overflow-y-auto p-2 space-y-1">
            {filteredFiles.map((file) => {
              const isSelected = selectedFile.filepath === file.filepath;
              return (
                <button
                  key={file.filepath}
                  onClick={() => {
                    setSelectedFile(file);
                    setCopied(false);
                  }}
                  className={`w-full flex items-center justify-between p-2 rounded-lg text-left transition-all ${
                    isSelected
                      ? 'bg-slate-800/80 border border-slate-700 text-white font-semibold'
                      : 'border border-transparent text-slate-405 text-slate-400 hover:bg-slate-900/50 hover:text-slate-200'
                  }`}
                >
                  <div className="flex items-center gap-2 overflow-hidden">
                    <span className="text-slate-550 shrink-0">
                      <ChevronRight className={`w-3.5 h-3.5 transition-transform ${isSelected ? 'rotate-90 text-cyan-400' : 'text-slate-600'}`} />
                    </span>
                    <div className="truncate">
                      <div className="text-xs font-mono truncate">{file.name}</div>
                      <div className="text-[10px] text-slate-500 truncate font-mono">{file.filepath}</div>
                    </div>
                  </div>
                  <span className={`text-[9px] font-mono px-1.5 py-0.5 rounded border ${getLanguageColor(file.language)} shrink-0 uppercase font-bold`}>
                    {file.language}
                  </span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Code Viewer */}
        <div className="w-2/3 flex flex-col bg-[#0A0B0D] overflow-hidden">
          {/* File path line */}
          <div className="px-4 py-2 bg-[#15171C]/30 border-b border-slate-800 flex items-center justify-between text-slate-400 text-xs font-mono">
            <span className="flex items-center gap-2">
              <Terminal className="w-3.5 h-3.5 text-slate-500" />
              {selectedFile.filepath}
            </span>
            <div className="flex items-center gap-2">
              <button
                onClick={handleCopy}
                className="flex items-center gap-1.5 px-3 py-1 bg-slate-800 hover:bg-slate-705 hover:bg-slate-700 text-slate-200 text-xs rounded-lg transition-all active:scale-95 border border-slate-700"
              >
                {copied ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-emerald-400" />
                    <span className="text-emerald-400 font-bold">Copied!</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5" />
                    <span className="font-bold">Copy File</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Actual Code Area */}
          <div className="flex-1 overflow-auto p-4 font-mono text-xs text-slate-300 leading-relaxed bg-[#0A0B0D]">
            <pre className="whitespace-pre-wrap word-break">
              <code>{selectedFile.content}</code>
            </pre>
          </div>
        </div>
      </div>

      {/* Codebase installation quick hints */}
      <div className="bg-[#15171C] p-3 border-t border-slate-800 flex items-center gap-4 text-xs text-slate-400">
        <div className="flex items-center gap-1.5 text-cyan-400 shrink-0 font-mono text-[11px] uppercase tracking-wider">
          <CheckCircle2 className="w-4 h-4 text-cyan-400" />
          <span className="font-bold">Production Ready SDK Blueprint</span>
        </div>
        <p className="truncate">
          These elements contain proper implementation for Wi-Fi Aware, BLE multi-hop routing, cryptographic Double Ratchets &amp; Solana USDC escrows!
        </p>
      </div>
    </div>
  );
}
