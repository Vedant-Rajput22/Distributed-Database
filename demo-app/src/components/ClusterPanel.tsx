import { useState } from 'react';
import { useStore } from '../store';
import * as api from '../api';
import { NODES } from '../types';
import {
  X, Server, Shield, ShieldOff, Crown, Sparkles,
  Database, ChevronRight, Zap,
} from 'lucide-react';

export default function ClusterPanel() {
  const {
    showClusterPanel, setShowClusterPanel,
    activeNodeIndex, setActiveNodeIndex,
    nodeStatus, showToast,
  } = useStore();

  const [chaosLoading, setChaosLoading] = useState<string | null>(null);
  const [seedLoading, setSeedLoading] = useState(false);

  if (!showClusterPanel) return null;

  // ─── Node State ────────────────────────────────

  function getNodeState(nodeLabel: string) {
    if (!nodeStatus) return { alive: true, role: 'UNKNOWN' };
    const isConnected = nodeStatus.nodeId === nodeLabel;
    const isLeader = nodeStatus.leaderId === nodeLabel;
    if (isConnected) {
      return { alive: !nodeStatus.killed, role: nodeStatus.killed ? 'KILLED' : nodeStatus.role };
    }
    const alive = nodeStatus.peerHealth?.[nodeLabel] ?? true;
    return { alive, role: !alive ? 'KILLED' : isLeader ? 'LEADER' : 'FOLLOWER' };
  }

  // ─── Chaos actions ─────────────────────────────

  async function handleChaos(nodeLabel: string, action: 'kill' | 'recover') {
    setChaosLoading(nodeLabel);
    try {
      if (action === 'kill') { await api.killNode(nodeLabel); showToast(`${nodeLabel} killed`); }
      else { await api.recoverNode(nodeLabel); showToast(`${nodeLabel} recovered`); }
    } catch { showToast(`Failed to ${action} ${nodeLabel}`); }
    setChaosLoading(null);
  }

  // ─── Switch node ───────────────────────────────

  function switchNode(idx: number) {
    api.setNodeIndex(idx);
    setActiveNodeIndex(idx);
    showToast(`Connected to ${NODES[idx].label}`);
  }

  // ─── Seed data ─────────────────────────────────

  async function seedData() {
    setSeedLoading(true);
    const msgs = [
      { ch: 'general', user: 'Alice', text: 'Hey everyone! Welcome to RaftChat 👋' },
      { ch: 'general', user: 'Bob', text: 'This is awesome! Messages are replicated across all nodes.' },
      { ch: 'general', user: 'Vedant', text: 'Try killing a node and see what happens to the messages!' },
      { ch: 'random', user: 'Alice', text: 'Random thought: distributed systems are like group projects 😄' },
      { ch: 'tech-talk', user: 'Bob', text: 'Raft consensus ensures strong consistency. Each write needs a majority of nodes to agree.' },
    ];
    for (const m of msgs) {
      await api.sendMessage(m.ch, m.user, m.text);
      await new Promise(r => setTimeout(r, 100));
    }
    showToast('Demo data seeded!');
    setSeedLoading(false);
  }

  return (
    <aside className="w-[300px] bg-white dark:bg-surface-900 border-l border-surface-200 dark:border-surface-800 flex flex-col shrink-0 animate-slide-up">
      {/* Header */}
      <div className="flex items-center justify-between px-5 py-4 border-b border-surface-100 dark:border-surface-800">
        <div className="flex items-center gap-2.5">
          <Database className="w-5 h-5 text-primary-500" />
          <h2 className="text-[15px] font-semibold text-surface-900 dark:text-white">
            Cluster Panel
          </h2>
        </div>
        <button
          onClick={() => setShowClusterPanel(false)}
          className="p-1.5 rounded-xl hover:bg-surface-100 dark:hover:bg-surface-800 text-surface-400 dark:text-surface-500 transition-colors"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Nodes */}
      <div className="flex-1 overflow-y-auto p-4 space-y-2">
        <p className="text-[11px] uppercase tracking-wider text-surface-400 dark:text-surface-500 font-bold mb-2 px-1">
          Nodes
        </p>

        {NODES.map((node, idx) => {
          const state = getNodeState(node.label);
          const isConnected = activeNodeIndex === idx;
          const isLoading = chaosLoading === node.label;

          return (
            <div
              key={node.label}
              className={`rounded-2xl border p-3.5 transition-all ${
                isConnected
                  ? 'bg-primary-50 dark:bg-primary-500/[0.08] border-primary-200 dark:border-primary-500/20'
                  : 'bg-surface-50 dark:bg-surface-800/50 border-surface-200 dark:border-surface-700 hover:border-surface-300 dark:hover:border-surface-600'
              }`}
            >
              <div className="flex items-center gap-3">
                {/* Status indicator */}
                <div className="relative">
                  <Server className={`w-5 h-5 ${state.alive ? 'text-surface-400 dark:text-surface-500' : 'text-red-400'}`} />
                  <div
                    className={`absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 rounded-full border-2 border-white dark:border-surface-900 ${
                      state.alive ? 'bg-emerald-500' : 'bg-red-500'
                    } ${state.alive ? 'animate-pulse-dot' : ''}`}
                  />
                </div>

                {/* Node info */}
                <button onClick={() => switchNode(idx)} className="flex-1 text-left min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className={`text-[14px] font-semibold ${
                      isConnected ? 'text-primary-600 dark:text-primary-400' : 'text-surface-800 dark:text-surface-200'
                    }`}>
                      {node.label}
                    </span>
                    {state.role === 'LEADER' && <Crown className="w-3.5 h-3.5 text-amber-500" />}
                    {isConnected && (
                      <span className="text-[9px] px-1.5 py-0.5 rounded-full bg-primary-500/15 text-primary-600 dark:text-primary-400 font-bold">
                        YOU
                      </span>
                    )}
                  </div>
                  <p className={`text-[11px] ${
                    state.alive ? 'text-surface-400 dark:text-surface-500' : 'text-red-400'
                  }`}>
                    {state.role} · :{node.httpPort}
                  </p>
                </button>

                {/* Chaos button */}
                <button
                  onClick={() => handleChaos(node.label, state.alive ? 'kill' : 'recover')}
                  disabled={isLoading}
                  className={`p-2 rounded-xl transition-all ${
                    state.alive
                      ? 'hover:bg-red-50 dark:hover:bg-red-500/10 text-surface-400 dark:text-surface-500 hover:text-red-500'
                      : 'hover:bg-emerald-50 dark:hover:bg-emerald-500/10 text-surface-400 dark:text-surface-500 hover:text-emerald-500'
                  } ${isLoading ? 'opacity-50 animate-pulse' : ''}`}
                  title={state.alive ? 'Kill node' : 'Recover node'}
                >
                  {state.alive ? <ShieldOff className="w-4 h-4" /> : <Shield className="w-4 h-4" />}
                </button>
              </div>
            </div>
          );
        })}

        {/* Cluster stats */}
        {nodeStatus && (
          <>
            <p className="text-[11px] uppercase tracking-wider text-surface-400 dark:text-surface-500 font-bold mt-5 mb-2 px-1">
              Cluster Info
            </p>
            <div className="rounded-2xl bg-surface-50 dark:bg-surface-800/50 border border-surface-200 dark:border-surface-700 p-4 space-y-2.5">
              <InfoRow label="Term" value={String(nodeStatus.term)} />
              <InfoRow label="Leader" value={nodeStatus.leaderId || '—'} highlight />
              <InfoRow label="Commit Index" value={String(nodeStatus.commitIndex)} />
              <InfoRow label="Last Applied" value={String(nodeStatus.lastApplied)} />
              <InfoRow label="Uptime" value={`${Math.round(nodeStatus.uptime / 1000)}s`} />
            </div>
          </>
        )}

        {/* Seed data */}
        <p className="text-[11px] uppercase tracking-wider text-surface-400 dark:text-surface-500 font-bold mt-5 mb-2 px-1">
          Actions
        </p>
        <button
          onClick={seedData}
          disabled={seedLoading}
          className="w-full flex items-center justify-center gap-2 px-4 py-3 rounded-2xl text-[13px] font-medium bg-primary-50 dark:bg-primary-500/10 text-primary-600 dark:text-primary-400 hover:bg-primary-100 dark:hover:bg-primary-500/15 border border-primary-200 dark:border-primary-500/20 transition-all disabled:opacity-50"
        >
          {seedLoading ? (
            <Zap className="w-4 h-4 animate-pulse" />
          ) : (
            <Sparkles className="w-4 h-4" />
          )}
          {seedLoading ? 'Seeding...' : 'Seed Demo Data'}
        </button>
      </div>
    </aside>
  );
}

function InfoRow({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-[12px] text-surface-400 dark:text-surface-500">{label}</span>
      <span className={`text-[12px] font-mono font-medium ${
        highlight ? 'text-amber-600 dark:text-amber-400' : 'text-surface-700 dark:text-surface-300'
      }`}>
        {value}
      </span>
    </div>
  );
}
