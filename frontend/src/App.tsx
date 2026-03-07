import { useEffect, useState, useRef } from 'react';
import { useClusterStore } from './stores/clusterStore';
import { useWebSocket } from './hooks/useWebSocket';
import { useClusterState } from './hooks/useClusterState';
import Sidebar from './components/Sidebar';
import TopologyView from './components/TopologyView';
import RaftLogViewer from './components/RaftLogViewer';
import KvExplorer from './components/KvExplorer';
import MetricsDashboard from './components/MetricsDashboard';
import EventTimeline from './components/EventTimeline';
import ChaosPanel from './components/ChaosPanel';
import SpeculationPanel from './components/SpeculationPanel';
import {
  Network, ScrollText, Database, BarChart3, Radio, Flame,
  WifiOff, ChevronDown, Server, Skull, Activity, Zap,
} from 'lucide-react';

const TABS = [
  { id: 'topology', label: 'Topology', icon: Network, description: 'Cluster visualization' },
  { id: 'logs', label: 'Raft Log', icon: ScrollText, description: 'Consensus log entries' },
  { id: 'kv', label: 'KV Store', icon: Database, description: 'Key-value operations' },
  { id: 'speculation', label: 'Speculation', icon: Zap, description: 'Speculative MVCC Consensus' },
  { id: 'metrics', label: 'Metrics', icon: BarChart3, description: 'Performance monitoring' },
  { id: 'events', label: 'Events', icon: Radio, description: 'Real-time event stream' },
  { id: 'chaos', label: 'Chaos', icon: Flame, description: 'Fault injection testing' },
];

function App() {
  const activeTab = useClusterStore((s) => s.activeTab);
  const setActiveTab = useClusterStore((s) => s.setActiveTab);
  const clusterStatus = useClusterStore((s) => s.clusterStatus);

  useWebSocket();
  useClusterState();

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      const idx = parseInt(e.key) - 1;
      if (idx >= 0 && idx < TABS.length) {
        setActiveTab(TABS[idx].id);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [setActiveTab]);

  const renderContent = () => {
    switch (activeTab) {
      case 'topology': return <TopologyView />;
      case 'logs': return <RaftLogViewer />;
      case 'kv': return <KvExplorer />;
      case 'speculation': return <SpeculationPanel />;
      case 'metrics': return <MetricsDashboard />;
      case 'events': return <EventTimeline />;
      case 'chaos': return <ChaosPanel />;
      default: return <TopologyView />;
    }
  };

  const isKilled = clusterStatus?.killed === true;

  return (
    <div className="flex h-screen bg-surface-950 bg-mesh text-gray-100 overflow-hidden">
      {/* Left Sidebar */}
      <Sidebar />

      {/* Main content area */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Killed Node Banner */}
        {isKilled && (
          <div className="relative overflow-hidden bg-gradient-to-r from-red-950/80 via-red-900/60 to-red-950/80 border-b border-red-500/30 px-6 py-3">
            <div className="absolute inset-0 bg-[repeating-linear-gradient(45deg,transparent,transparent_10px,rgba(239,68,68,0.05)_10px,rgba(239,68,68,0.05)_20px)]" />
            <div className="relative flex items-center justify-center gap-3">
              <div className="flex items-center gap-2 animate-pulse">
                <Skull className="w-5 h-5 text-red-400" />
                <span className="text-sm font-bold text-red-300 tracking-wide uppercase">Node Killed</span>
                <Skull className="w-5 h-5 text-red-400" />
              </div>
              <span className="text-xs text-red-400/70 ml-4">
                This node has been simulated as dead — it will not respond to gRPC requests. Use Chaos panel to recover.
              </span>
            </div>
          </div>
        )}

        {/* Top navigation */}
        <header className="relative z-30 flex items-center justify-between border-b border-white/[0.06] bg-white/[0.015] backdrop-blur-2xl px-5 py-2">
          {/* Logo */}
          <div className="flex items-center gap-3 min-w-[160px]">
            <div className="relative">
              <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 flex items-center justify-center shadow-lg shadow-brand-600/20">
                <Database className="w-4 h-4 text-white" />
              </div>
              {!isKilled && <div className="absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full bg-emerald-500 border-2 border-surface-950" />}
              {isKilled && <div className="absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full bg-red-500 border-2 border-surface-950" />}
            </div>
            <div>
              <h1 className="text-sm font-bold tracking-tight bg-gradient-to-r from-white to-white/80 bg-clip-text text-transparent">MiniDB</h1>
              <p className="text-[10px] text-white/25 -mt-0.5 font-medium">Distributed Database</p>
            </div>
          </div>

          {/* Tab Navigation */}
          <nav className="flex items-center gap-0.5 bg-white/[0.025] rounded-2xl p-1 border border-white/[0.05]">
            {TABS.map((tab, index) => {
              const Icon = tab.icon;
              const isActive = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`group relative flex items-center gap-2 px-3.5 py-2 rounded-xl text-xs font-medium transition-all duration-200 ${
                    isActive
                      ? 'bg-white/[0.1] text-white shadow-sm ring-1 ring-white/[0.08]'
                      : 'text-white/35 hover:text-white/60 hover:bg-white/[0.03]'
                  }`}
                  title={tab.description}
                >
                  <Icon className={`w-3.5 h-3.5 ${isActive ? 'text-brand-400' : ''}`} />
                  <span className={isActive ? 'text-white/90' : ''}>{tab.label}</span>
                  <kbd className={`hidden xl:inline-block ml-0.5 text-[9px] px-1.5 py-0.5 rounded-md font-mono ${
                    isActive ? 'bg-white/[0.08] text-white/50' : 'bg-white/[0.03] text-white/15'
                  }`}>
                    {index + 1}
                  </kbd>
                  {isActive && (
                    <div className="absolute -bottom-1 left-1/2 -translate-x-1/2 w-6 h-0.5 rounded-full bg-brand-400" />
                  )}
                </button>
              );
            })}
          </nav>

          {/* Node Switcher + Connection Status */}
          <div className="flex items-center gap-2 min-w-[160px] justify-end">
            <NodeSwitcher />
            <ConnectionStatus />
          </div>
        </header>

        {/* Content */}
        <main className="flex-1 overflow-auto p-5">
          {renderContent()}
        </main>
      </div>
    </div>
  );
}

const NODE_OPTIONS = [
  { label: 'node-1', httpPort: 8080, grpcPort: 9090, baseUrl: '', color: 'from-blue-400 to-blue-600' },
  { label: 'node-2', httpPort: 8082, grpcPort: 9092, baseUrl: 'http://localhost:8082/api', color: 'from-violet-400 to-violet-600' },
  { label: 'node-3', httpPort: 8084, grpcPort: 9094, baseUrl: 'http://localhost:8084/api', color: 'from-emerald-400 to-emerald-600' },
];

function NodeSwitcher() {
  const apiBaseUrl = useClusterStore((s) => s.apiBaseUrl);
  const setApiBaseUrl = useClusterStore((s) => s.setApiBaseUrl);
  const clusterStatus = useClusterStore((s) => s.clusterStatus);
  const [open, setOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const current = NODE_OPTIONS.find((n) => n.baseUrl === apiBaseUrl) || NODE_OPTIONS[0];
  const isKilled = clusterStatus?.killed === true;

  // Close dropdown when clicking outside
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div className="relative" ref={dropdownRef}>
      <button
        onClick={() => setOpen(!open)}
        className={`flex items-center gap-2.5 pl-2.5 pr-3 py-1.5 rounded-xl text-xs font-medium border transition-all duration-200 ${
          isKilled
            ? 'bg-red-500/10 border-red-500/20 hover:bg-red-500/15'
            : 'bg-white/[0.04] border-white/[0.08] hover:bg-white/[0.07]'
        }`}
      >
        <div className={`w-5 h-5 rounded-lg bg-gradient-to-br ${current.color} flex items-center justify-center shadow-sm`}>
          <Server className="w-2.5 h-2.5 text-white" />
        </div>
        <span className={`font-semibold ${isKilled ? 'text-red-300' : 'text-white/80'}`}>{current.label}</span>
        <span className="text-white/25 font-mono text-[10px]">:{current.httpPort}</span>
        <ChevronDown className={`w-3 h-3 text-white/30 transition-transform duration-200 ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-2 z-50 min-w-[220px] bg-surface-900/98 backdrop-blur-2xl border border-white/[0.1] rounded-2xl shadow-2xl shadow-black/50 overflow-hidden animate-fade-in">
          <div className="px-4 py-2.5 border-b border-white/[0.06]">
            <p className="text-[10px] text-white/30 uppercase tracking-[0.15em] font-bold">Connect to Node</p>
          </div>
          <div className="p-1.5">
            {NODE_OPTIONS.map((node) => {
              const isActive = node.baseUrl === apiBaseUrl;
              return (
                <button
                  key={node.label}
                  onClick={() => {
                    setApiBaseUrl(node.baseUrl);
                    setOpen(false);
                  }}
                  className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-xs transition-all duration-150 ${
                    isActive
                      ? 'bg-brand-600/15 ring-1 ring-brand-500/20'
                      : 'hover:bg-white/[0.05]'
                  }`}
                >
                  <div className={`w-7 h-7 rounded-lg bg-gradient-to-br ${node.color} flex items-center justify-center shadow-sm`}>
                    <Server className="w-3 h-3 text-white" />
                  </div>
                  <div className="flex flex-col items-start flex-1">
                    <span className={`font-semibold ${isActive ? 'text-brand-300' : 'text-white/70'}`}>{node.label}</span>
                    <span className="text-[10px] text-white/25 font-mono">HTTP :{node.httpPort} · gRPC :{node.grpcPort}</span>
                  </div>
                  {isActive && (
                    <div className="w-2 h-2 rounded-full bg-brand-400 shadow-sm shadow-brand-400/50" />
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

function ConnectionStatus() {
  const connected = useClusterStore((s) => s.connected);
  const clusterStatus = useClusterStore((s) => s.clusterStatus);
  const isKilled = clusterStatus?.killed === true;

  if (isKilled) {
    return (
      <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl text-xs font-semibold bg-red-500/10 text-red-400 border border-red-500/20">
        <Skull className="w-3 h-3" />
        <span>Killed</span>
      </div>
    );
  }

  return (
    <div className={`flex items-center gap-2 px-3 py-1.5 rounded-xl text-xs font-medium transition-all duration-300 border ${
      connected
        ? 'bg-emerald-500/8 text-emerald-400 border-emerald-500/15'
        : 'bg-red-500/8 text-red-400 border-red-500/15 animate-pulse'
    }`}>
      {connected ? (
        <>
          <Activity className="w-3 h-3" />
          <span>Live</span>
          <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 status-dot" />
        </>
      ) : (
        <>
          <WifiOff className="w-3 h-3" />
          <span>Offline</span>
          <span className="w-1.5 h-1.5 rounded-full bg-red-400" />
        </>
      )}
    </div>
  );
}

export default App;
