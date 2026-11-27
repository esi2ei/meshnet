import React from 'react';

export type LivePeer = {
  id: string;
  address: string;
  transport: string;
  connectedAt: number;
};

export type ObservedRoute = {
  id: string;
  nodes: string[];
  observedAt: number;
  observations: number;
};

type Props = {
  running: boolean;
  native: boolean;
  peers: LivePeer[];
  routes: ObservedRoute[];
  localNodeId: string | null;
};

export default function MeshSimulator({ running, native, peers, routes, localNodeId }: Props) {
  const width = 720, height = 300, center = { x: width / 2, y: height / 2 };
  const positions = peers.map((_, index) => {
    const angle = (Math.PI * 2 * index) / Math.max(peers.length, 1) - Math.PI / 2;
    const radius = Math.min(112, 42 + peers.length * 10);
    return { x: center.x + Math.cos(angle) * radius, y: center.y + Math.sin(angle) * radius };
  });

  // Route observations are group-key-authenticated frame histories that reached this device.
  // Keep their graph separate from live radio links: a recently observed path is not
  // proof that every hop is still connected at this instant.
  const validRoutes = routes.filter(route =>
    route.nodes.length >= 2 &&
    route.nodes.length <= 9 &&
    localNodeId !== null &&
    route.nodes[route.nodes.length - 1] === localNodeId
  );
  const depthByNode = new Map<string, number>();
  validRoutes.forEach(route => route.nodes.slice(0, -1).forEach((node, index) => {
    const depth = route.nodes.length - 1 - index;
    const previous = depthByNode.get(node);
    if (previous === undefined || depth < previous) depthByNode.set(node, depth);
  }));
  const nodesByDepth = new Map<number, string[]>();
  depthByNode.forEach((depth, node) => {
    const group = nodesByDepth.get(depth) ?? [];
    group.push(node);
    nodesByDepth.set(depth, group);
  });
  const routeWidth = 900, routeHeight = 280;
  const localPosition = { x: 820, y: routeHeight / 2 };
  const routePositions = new Map<string, { x: number; y: number }>();
  nodesByDepth.forEach((nodes, depth) => {
    nodes.sort();
    nodes.forEach((node, index) => {
      routePositions.set(node, {
        x: 820 - depth * 90,
        y: ((index + 1) * routeHeight) / (nodes.length + 1),
      });
    });
  });
  if (localNodeId) routePositions.set(localNodeId, localPosition);
  const edgeMap = new Map<string, { from: string; to: string }>();
  validRoutes.forEach(route => {
    for (let index = 0; index < route.nodes.length - 1; index++) {
      const from = route.nodes[index], to = route.nodes[index + 1];
      edgeMap.set(`${from}>${to}`, { from, to });
    }
  });
  const routeEdges = Array.from(edgeMap.values()).filter(edge =>
    routePositions.has(edge.from) && routePositions.has(edge.to)
  );

  return (
    <section className="rounded-2xl border border-slate-800 bg-[#0A0B0D] p-3 sm:p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-white">Live radio topology</h3>
          <p className="mt-1 text-xs text-slate-500">Established BLE/GATT and local Wi-Fi data-channel connections</p>
        </div>
        <span className={`rounded-full border px-2.5 py-1 text-[11px] ${running ? 'border-emerald-500/30 text-emerald-300' : 'border-slate-700 text-slate-400'}`}>
          {running ? 'Mesh service running' : 'Mesh service stopped'}
        </span>
      </div>
      {!native ? (
        <div className="mt-4 rounded-xl border border-amber-500/30 bg-amber-500/5 p-4 text-xs leading-5 text-amber-100">
          Live peer and route telemetry is available inside the Android APK. A desktop browser cannot inspect nearby radio connections.
        </div>
      ) : (
        <>
          <div className="mt-3 overflow-hidden rounded-xl border border-slate-800 bg-[#0d1117]">
            <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Current device and established direct radio peers" className="h-auto w-full">
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
            <span className="rounded-lg border border-slate-700 px-2.5 py-1.5">{peers.length} direct peer{peers.length === 1 ? '' : 's'}</span>
          </div>
          {peers.length > 0 && <ul className="mt-3 divide-y divide-slate-800">
            {peers.map(peer => <li key={peer.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-xs">
              <span className="font-mono text-slate-200">{peer.address}</span>
              <span className="text-emerald-300">{peer.transport} · connected {new Date(peer.connectedAt).toLocaleTimeString()}</span>
            </li>)}
          </ul>}

          <div className="mt-5 border-t border-slate-800 pt-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h4 className="text-sm font-semibold text-white">Observed multi-hop routes</h4>
                <p className="mt-1 text-xs text-slate-500">Paths reconstructed from group-key-authenticated frame hop histories</p>
              </div>
              <span className="rounded-lg border border-slate-700 px-2 py-1 text-[10px] text-slate-400">{validRoutes.length} observed path{validRoutes.length === 1 ? '' : 's'}</span>
            </div>
            {validRoutes.length === 0 || !localNodeId ? (
              <p className="mt-3 rounded-lg border border-slate-800 bg-slate-950 p-3 text-xs leading-5 text-slate-500">
                No forwarded route has reached this device yet. When a message crosses relay nodes, its group-key-authenticated hop history will appear here. These observations expire and are not presented as currently active links.
              </p>
            ) : (
              <>
                <div className="mt-3 overflow-hidden rounded-xl border border-slate-800 bg-[#0d1117]">
                  <svg viewBox={`0 0 ${routeWidth} ${routeHeight}`} role="img" aria-label="Observed multi-hop routes from group-key-authenticated mesh frames" className="h-auto w-full">
                    <defs><pattern id="route-grid" width="24" height="24" patternUnits="userSpaceOnUse"><path d="M 24 0 L 0 0 0 24" fill="none" stroke="#1e293b" strokeWidth="0.7" /></pattern></defs>
                    <rect width={routeWidth} height={routeHeight} fill="url(#route-grid)" />
                    {routeEdges.map(edge => {
                      const from = routePositions.get(edge.from)!;
                      const to = routePositions.get(edge.to)!;
                      return <line key={`${edge.from}>${edge.to}`} x1={from.x} y1={from.y} x2={to.x} y2={to.y} stroke="#a78bfa" strokeWidth="2.5" strokeOpacity=".85" />;
                    })}
                    {Array.from(routePositions.entries()).map(([id, point]) => {
                      const isLocal = id === localNodeId;
                      return <g key={id}>
                        <circle cx={point.x} cy={point.y} r={isLocal ? 24 : 19} fill={isLocal ? '#164e63' : '#312e81'} stroke={isLocal ? '#22d3ee' : '#a78bfa'} strokeWidth="2" />
                        <text x={point.x} y={point.y + 4} textAnchor="middle" fontSize="10" fill="#f5f3ff" fontWeight="600">{isLocal ? 'THIS' : id.slice(0, 7)}</text>
                      </g>;
                    })}
                  </svg>
                </div>
                <ul className="mt-3 divide-y divide-slate-800">
                  {validRoutes.slice(0, 6).map(route => <li key={route.id} className="py-2 text-xs">
                    <p className="break-all font-mono leading-5 text-slate-300">{route.nodes.join(' → ')}</p>
                    <p className="mt-1 text-[10px] text-slate-500">{route.observations} observation{route.observations === 1 ? '' : 's'} · last seen {new Date(route.observedAt).toLocaleTimeString()}</p>
                  </li>)}
                </ul>
              </>
            )}
          </div>
        </>
      )}
    </section>
  );
}
