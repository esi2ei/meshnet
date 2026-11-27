import React from 'react';

export type LivePeer = {
  id: string;
  address: string;
  transport: string;
  connectedAt: number;
};

type Props = { running: boolean; native: boolean; peers: LivePeer[] };

export default function MeshSimulator({ running, native, peers }: Props) {
  const width = 720, height = 300, center = { x: width / 2, y: height / 2 };
  const positions = peers.map((_, index) => {
    const angle = (Math.PI * 2 * index) / Math.max(peers.length, 1) - Math.PI / 2;
    const radius = Math.min(112, 42 + peers.length * 10);
    return { x: center.x + Math.cos(angle) * radius, y: center.y + Math.sin(angle) * radius };
  });
  return (
    <section className="rounded-2xl border border-slate-800 bg-[#0A0B0D] p-3 sm:p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-white">Live radio topology</h3>
          <p className="mt-1 text-xs text-slate-500">Connections reported by native Android transport callbacks</p>
        </div>
        <span className={`rounded-full border px-2.5 py-1 text-[11px] ${running ? 'border-emerald-500/30 text-emerald-300' : 'border-slate-700 text-slate-400'}`}>
          {running ? 'Mesh service running' : 'Mesh service stopped'}
        </span>
      </div>
      {!native ? (
        <div className="mt-4 rounded-xl border border-amber-500/30 bg-amber-500/5 p-4 text-xs leading-5 text-amber-100">
          Live peer discovery is available inside the Android APK. A desktop browser cannot inspect nearby Bluetooth devices.
        </div>
      ) : (
        <>
          <div className="mt-3 overflow-hidden rounded-xl border border-slate-800 bg-[#0d1117]">
            <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Current local device and connected BLE peers" className="h-auto w-full">
              <defs><pattern id="mesh-grid" width="24" height="24" patternUnits="userSpaceOnUse"><path d="M 24 0 L 0 0 0 24" fill="none" stroke="#1e293b" strokeWidth="0.7" /></pattern></defs>
              <rect width={width} height={height} fill="url(#mesh-grid)" />
              {positions.map((p, i) => <line key={peers[i].id} x1={center.x} y1={center.y} x2={p.x} y2={p.y} stroke="#0891b2" strokeWidth="2" strokeOpacity=".8" />)}
              <circle cx={center.x} cy={center.y} r="27" fill="#164e63" stroke="#22d3ee" strokeWidth="2" />
              <text x={center.x} y={center.y + 4} textAnchor="middle" fontSize="11" fill="#ecfeff" fontWeight="700">THIS</text>
              {positions.map((p, i) => <g key={peers[i].id}>
                <circle cx={p.x} cy={p.y} r="19" fill="#064e3b" stroke="#34d399" strokeWidth="2" />
                <text x={p.x} y={p.y + 4} textAnchor="middle" fontSize="10" fill="#ecfdf5">{i + 1}</text>
              </g>)}
              {peers.length === 0 && <text x={center.x} y={center.y + 58} textAnchor="middle" fontSize="12" fill="#94a3b8">No connected peers reported</text>}
            </svg>
          </div>
          <div className="mt-3 flex flex-wrap gap-2 text-xs text-slate-400">
            <span className="rounded-lg border border-slate-700 px-2.5 py-1.5">This device · Android</span>
            <span className="rounded-lg border border-slate-700 px-2.5 py-1.5">{peers.length} connected BLE/GATT peer{peers.length === 1 ? '' : 's'}</span>
          </div>
          {peers.length > 0 && <ul className="mt-3 divide-y divide-slate-800">
            {peers.map(peer => <li key={peer.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-xs">
              <span className="font-mono text-slate-200">{peer.address}</span>
              <span className="text-emerald-300">{peer.transport} · connected {new Date(peer.connectedAt).toLocaleTimeString()}</span>
            </li>)}
          </ul>}
        </>
      )}
    </section>
  );
}
