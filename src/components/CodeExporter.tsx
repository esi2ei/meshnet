import React, { useMemo, useState } from 'react';
import { Copy, FileCode2, Search } from 'lucide-react';

// These modules are read from this checkout at Vite build time and bundled locally.
// No remote repository, CDN, or hosted source endpoint is used at runtime.
const frontendSources = import.meta.glob('../**/*.{ts,tsx,css}', { eager: true, query: '?raw', import: 'default' }) as Record<string, string>;
const nativeSources = import.meta.glob('../../mobile/app/src/{main,androidTest}/**/*.{kt,xml}', { eager: true, query: '?raw', import: 'default' }) as Record<string, string>;
const buildSources = import.meta.glob('../../mobile/*.{gradle,gradle.kts,properties}', { eager: true, query: '?raw', import: 'default' }) as Record<string, string>;
const sourceFiles = Object.entries({ ...frontendSources, ...nativeSources, ...buildSources })
  .map(([path, content]) => ({ path: path.replace(/^\.\.\//, ''), content }))
  .sort((a, b) => a.path.localeCompare(b.path));

export default function CodeExporter() {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState(sourceFiles[0]?.path ?? '');
  const current = useMemo(() => sourceFiles.find(file => file.path === selected), [selected]);
  const filtered = useMemo(() => sourceFiles.filter(file => file.path.toLowerCase().includes(query.toLowerCase())), [query]);
  const copy = async () => {
    if (current) await navigator.clipboard.writeText(current.content);
  };
  return (
    <section className="rounded-2xl border border-slate-800 bg-[#0A0B0D] p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="flex items-center gap-2 text-sm font-semibold text-white"><FileCode2 className="h-4 w-4 text-cyan-300" /> Bundled project source</h3>
          <p className="mt-1 text-xs text-slate-500">Actual React, Kotlin, Android XML, instrumentation-test and Gradle source files included at build time.</p>
        </div>
        <span className="rounded-lg border border-emerald-500/30 px-2.5 py-1 text-[11px] text-emerald-300">{sourceFiles.length} local files</span>
      </div>
      <label className="mt-3 flex items-center gap-2 rounded-lg border border-slate-700 bg-slate-950 px-3">
        <Search className="h-4 w-4 text-slate-500" />
        <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Filter source paths" className="min-h-10 w-full bg-transparent text-xs text-white outline-none" />
      </label>
      <div className="mt-3 grid min-h-[320px] gap-3 lg:grid-cols-[minmax(190px,0.8fr)_minmax(0,2fr)]">
        <div className="max-h-[420px] overflow-y-auto rounded-lg border border-slate-800">
          {filtered.map(file => <button key={file.path} onClick={() => setSelected(file.path)} className={`block w-full break-all border-b border-slate-800 px-3 py-2 text-left text-[11px] ${selected === file.path ? 'bg-cyan-950/60 text-cyan-200' : 'text-slate-400 hover:bg-slate-900'}`}>{file.path}</button>)}
          {filtered.length === 0 && <p className="p-3 text-xs text-slate-500">No source files match this filter.</p>}
        </div>
        <div className="min-w-0 overflow-hidden rounded-lg border border-slate-800">
          <div className="flex items-center justify-between gap-2 border-b border-slate-800 bg-slate-950 px-3 py-2">
            <code className="min-w-0 truncate text-[11px] text-cyan-200">{current?.path ?? 'No file selected'}</code>
            <button disabled={!current} onClick={() => void copy()} className="inline-flex shrink-0 items-center gap-1 rounded-md border border-slate-700 px-2 py-1 text-[11px] text-slate-300 disabled:opacity-40"><Copy className="h-3 w-3" /> Copy</button>
          </div>
          <pre className="max-h-[370px] overflow-auto p-3 text-[10px] leading-5 text-slate-300"><code>{current?.content ?? 'No source files were discovered by the local build.'}</code></pre>
        </div>
      </div>
      <p className="mt-3 text-[11px] leading-5 text-slate-500">This is a read-only source viewer. The APK executes compiled Kotlin and bundled JavaScript; viewing source here does not replace native compilation or tests.</p>
    </section>
  );
}
