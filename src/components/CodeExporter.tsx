import React, { useState } from 'react';
import { FLUTTER_CODEBASE } from '../data/flutterCodebase';
import { CodeFile } from '../types';
import { FileCode, Copy, Check } from 'lucide-react';

export default function CodeExporter() {
  const [copied, setCopied] = useState(false);
  const selectedFile: CodeFile = FLUTTER_CODEBASE[0];

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(selectedFile.content);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="flex flex-col min-h-[260px] bg-[#15171C] border border-slate-800 rounded-2xl overflow-hidden">
      <div className="p-4 border-b border-slate-800">
        <h2 className="text-sm font-semibold text-white flex items-center gap-2">
          <FileCode className="w-4 h-4 text-cyan-400" />
          Native implementation status
        </h2>
        <p className="text-xs text-slate-400 mt-1">
          Placeholder source exports were removed. Only the actual Android prototype and its documented gaps are referenced here.
        </p>
      </div>
      <div className="flex-1 p-4 overflow-auto">
        <div className="flex items-center justify-between gap-3 mb-3 text-xs font-mono text-slate-400">
          <span>{selectedFile.filepath}</span>
          <button onClick={handleCopy} className="flex items-center gap-2 rounded-lg border border-slate-700 px-3 py-1.5 text-slate-200 hover:bg-slate-800">
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            {copied ? 'Copied' : 'Copy status'}
          </button>
        </div>
        <pre className="whitespace-pre-wrap text-xs leading-relaxed text-slate-300">{selectedFile.content}</pre>
      </div>
    </div>
  );
}
