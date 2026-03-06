import { useState, useCallback } from 'react';
import { motion } from 'framer-motion';
import { useClusterStore } from '../stores/clusterStore';
import type { ClusterStatus, NodeInfo } from '../types/events';
import {
  Crown, Skull, Unplug, Bomb, HeartPulse, Crosshair,
  Server, Trash2, RotateCcw, Link2, AlertTriangle,
  ScrollText, Sparkles, Wifi, WifiOff, ShieldAlert,
  Zap, Activity, CircleDot
} from 'lucide-react';

interface Scenario {
  id: string;
  name: string;
  icon: typeof Crown;
  secondaryIcon?: typeof Skull;
  description: string;
  accent: string;
  glow: string;
  action: () => Promise<void>;
}

/** Return the effective API base from the store */
function getApiBase() {
  return useClusterStore.getState().apiBaseUrl || '/api';
}

// Force a quick re-fetch of cluster status and nodes so topology updates immediately
async function forceRefresh(
  setClusterStatus: (s: ClusterStatus) => void,
  setNodes: (n: NodeInfo[]) => void,
) {
  const base = getApiBase();
  try {
    const [statusRes, nodesRes] = await Promise.all([
      fetch(`${base}/cluster/status`),
      fetch(`${base}/nodes`),
    ]);
    if (statusRes.ok) setClusterStatus(await statusRes.json());
    if (nodesRes.ok) setNodes(await nodesRes.json());
  } catch {
    // silent — polling will catch up
  }
}

export default function ChaosPanel() {
  const clusterStatus = useClusterStore((s) => s.clusterStatus);
  const nodes = useClusterStore((s) => s.nodes);
  const setClusterStatus = useClusterStore((s) => s.setClusterStatus);
  const setNodes = useClusterStore((s) => s.setNodes);
  const [log, setLog] = useState<string[]>([]);
  const [running, setRunning] = useState<string | null>(null);

  const addLog = (msg: string) => {
    const ts = new Date().toLocaleTimeString('en-US', { hour12: false });
    setLog((prev) => [`[${ts}] ${msg}`, ...prev].slice(0, 100));
  };

  const chaosApi = useCallback(async (path: string, method = 'POST') => {
    const base = getApiBase();
    try {
      const res = await fetch(`${base}${path}`, { method });
      const data = await res.json();
      return data;
    } catch (e: any) {
      addLog(`ERROR: ${e.message}`);
      return null;
    }
  }, []);

  const killNode = async (nodeId: string) => {
    addLog(`Killing node ${nodeId}...`);
    const data = await chaosApi(`/chaos/kill-node/${nodeId}`);
    if (data) addLog(`Node ${nodeId} killed: ${data.message || data.error || (data.success ? 'OK' : 'FAILED')}`);
    // Immediate refresh for visible topology change
    await forceRefresh(setClusterStatus, setNodes);
  };

  const recoverNode = async (nodeId: string) => {
    addLog(`Recovering node ${nodeId}...`);
    const data = await chaosApi(`/chaos/recover/${nodeId}`);
    if (data) addLog(`Node ${nodeId} recovered: ${data.message || data.error || (data.success ? 'OK' : 'FAILED')}`);
    await forceRefresh(setClusterStatus, setNodes);
  };

  const partitionNode = async (nodeId: string) => {
    addLog(`Partitioning node ${nodeId}...`);
    const data = await chaosApi(`/chaos/partition/${nodeId}`);
    if (data) addLog(`Node ${nodeId} partitioned: ${data.message || 'OK'}`);
    await forceRefresh(setClusterStatus, setNodes);
  };

  const healPartition = async (nodeId: string) => {
    addLog(`Healing partition for ${nodeId}...`);
    const data = await chaosApi(`/chaos/heal-partition/${nodeId}`);
    if (data) addLog(`Partition healed for ${nodeId}: ${data.message || 'OK'}`);
    await forceRefresh(setClusterStatus, setNodes);
  };

  const recoverAll = async () => {
    addLog('Recovering all nodes...');
    const data = await chaosApi('/chaos/recover-all');
    if (data) addLog(`All recovered: ${data.message || 'OK'}`);
    await forceRefresh(setClusterStatus, setNodes);
  };

  const scenarios: Scenario[] = [
    {
      id: 'kill-leader', name: 'Kill Leader', icon: Crown, secondaryIcon: Skull,
      description: 'Kill the current leader to trigger an election',
      accent: 'border-red-500/15 hover:border-red-500/30', glow: 'bg-red-500/10',
      action: async () => {
        setRunning('kill-leader');
        addLog('=== SCENARIO: Kill Leader ===');
        if (clusterStatus?.leaderId) {
          await killNode(clusterStatus.leaderId);
          addLog('Waiting for new election...');
          // Give the cluster time to detect the dead node, then refresh again
          setTimeout(() => forceRefresh(setClusterStatus, setNodes), 3000);
          setTimeout(() => forceRefresh(setClusterStatus, setNodes), 6000);
        }
        else addLog('No leader to kill!');
        setRunning(null);
      },
    },
    {
      id: 'network-partition', name: 'Network Partition', icon: Unplug,
      description: 'Isolate the leader from the cluster',
      accent: 'border-orange-500/15 hover:border-orange-500/30', glow: 'bg-orange-500/10',
      action: async () => {
        setRunning('network-partition');
        addLog('=== SCENARIO: Network Partition ===');
        if (clusterStatus?.leaderId) { await partitionNode(clusterStatus.leaderId); addLog('Leader isolated.'); }
        else addLog('No leader to partition!');
        setRunning(null);
      },
    },
    {
      id: 'kill-majority', name: 'Kill Majority', icon: Bomb,
      description: 'Kill majority of nodes — cluster loses quorum',
      accent: 'border-rose-500/15 hover:border-rose-500/30', glow: 'bg-rose-500/10',
      action: async () => {
        setRunning('kill-majority');
        addLog('=== SCENARIO: Kill Majority ===');
        const allIds = clusterStatus ? [clusterStatus.nodeId, ...(clusterStatus.peers || [])] : [];
        const majority = Math.ceil(allIds.length / 2);
        for (let i = 0; i < majority && i < allIds.length; i++) await killNode(allIds[i]);
        addLog(`Killed ${majority} of ${allIds.length} nodes.`);
        setRunning(null);
      },
    },
    {
      id: 'recover-all', name: 'Recover All', icon: HeartPulse,
      description: 'Heal all partitions and recover all nodes',
      accent: 'border-emerald-500/15 hover:border-emerald-500/30', glow: 'bg-emerald-500/10',
      action: async () => {
        setRunning('recover-all');
        addLog('=== SCENARIO: Recover All ===');
        await recoverAll();
        addLog('All nodes recovered and partitions healed.');
        setRunning(null);
      },
    },
  ];

  const allNodeIds = clusterStatus ? [clusterStatus.nodeId, ...(clusterStatus.peers || [])] : [];

  // Get health info for a node
  const getNodeHealth = (nodeId: string) => {
    const nodeInfo = nodes.find(n => n.id === nodeId);
    if (nodeId === clusterStatus?.nodeId) {
      return { isAlive: !clusterStatus.killed, isPartitioned: false, isUp: !clusterStatus.killed };
    }
    if (nodeInfo) {
      return {
        isAlive: nodeInfo.isAlive !== false,
        isPartitioned: nodeInfo.isPartitioned === true,
        isUp: nodeInfo.isUp !== false,
      };
    }
    // Fallback to peerHealth
    const peerAlive = clusterStatus?.peerHealth?.[nodeId];
    const isPartitioned = clusterStatus?.partitionedPeers?.includes(nodeId) ?? false;
    return {
      isAlive: peerAlive !== false,
      isPartitioned,
      isUp: peerAlive !== false && !isPartitioned,
    };
  };

  return (
    <div className="h-full flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold tracking-tight bg-gradient-to-r from-white to-white/70 bg-clip-text text-transparent">Chaos Engineering</h2>
          <p className="text-xs text-white/30 mt-0.5">Inject failures to test cluster resilience and fault tolerance</p>
        </div>
        <div className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-red-500/[0.06] border border-red-500/10">
          <AlertTriangle className="w-3.5 h-3.5 text-red-400 animate-pulse" />
          <span className="text-[11px] text-red-400/80 font-semibold">Destructive Actions</span>
        </div>
      </div>

      <div className="flex-1 flex gap-4 overflow-hidden">
        {/* Left: Controls */}
        <div className="w-[420px] flex flex-col gap-3 overflow-y-auto flex-shrink-0">
          {/* Scenarios */}
          <div className="glass-card p-5">
            <div className="flex items-center gap-2.5 mb-4">
              <div className="p-1.5 rounded-lg bg-white/[0.04]">
                <Crosshair className="w-4 h-4 text-white/40" />
              </div>
              <div>
                <p className="text-sm font-semibold text-white/80">Quick Scenarios</p>
                <p className="text-[10px] text-white/25">Pre-built failure injection patterns</p>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-2.5">
              {scenarios.map((s) => {
                const Icon = s.icon;
                return (
                  <motion.button key={s.id}
                    whileHover={{ scale: 1.01, y: -1 }} whileTap={{ scale: 0.98 }}
                    onClick={s.action} disabled={running !== null}
                    className={`p-4 rounded-xl border text-left transition-all duration-200 bg-white/[0.02] ${s.accent} ${
                      running && running !== s.id ? 'opacity-30 pointer-events-none' : ''
                    } ${running === s.id ? 'ring-1 ring-white/10' : ''}`}>
                    <div className={`w-10 h-10 rounded-xl flex items-center justify-center mb-3 ${s.glow}`}>
                      <Icon className="w-5 h-5 text-white/80" />
                    </div>
                    <div className="text-sm font-bold text-white/80 tracking-tight">{s.name}</div>
                    <div className="text-[10px] text-white/25 mt-1 leading-relaxed">{s.description}</div>
                    {running === s.id && (
                      <div className="mt-2 flex items-center gap-1.5 text-[10px] text-brand-400">
                        <Zap className="w-3 h-3 animate-pulse" />
                        Running...
                      </div>
                    )}
                  </motion.button>
                );
              })}
            </div>
          </div>

          {/* Node Controls */}
          <div className="glass-card p-5">
            <div className="flex items-center gap-2.5 mb-4">
              <div className="p-1.5 rounded-lg bg-white/[0.04]">
                <Server className="w-4 h-4 text-white/40" />
              </div>
              <div>
                <p className="text-sm font-semibold text-white/80">Node Controls</p>
                <p className="text-[10px] text-white/25">Individual node management</p>
              </div>
            </div>
            <div className="space-y-2">
              {allNodeIds.map((nodeId) => {
                const isLeader = nodeId === clusterStatus?.leaderId;
                const isSelf = nodeId === clusterStatus?.nodeId;
                const health = getNodeHealth(nodeId);
                return (
                  <div key={nodeId} className={`flex items-center gap-3 p-3 rounded-xl border transition-all duration-300 ${
                    !health.isAlive
                      ? 'bg-red-500/[0.04] border-red-500/12'
                      : health.isPartitioned
                        ? 'bg-orange-500/[0.04] border-orange-500/12'
                        : 'bg-white/[0.02] border-white/[0.05] hover:border-white/[0.08]'
                  }`}>
                    {/* Node info */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <div className={`w-6 h-6 rounded-lg flex items-center justify-center ${
                          !health.isAlive ? 'bg-red-500/10' : health.isPartitioned ? 'bg-orange-500/10' : 'bg-white/[0.04]'
                        }`}>
                          {!health.isAlive 
                            ? <Skull className="w-3 h-3 text-red-400" />
                            : health.isPartitioned 
                              ? <ShieldAlert className="w-3 h-3 text-orange-400" />
                              : <Activity className="w-3 h-3 text-emerald-400" />
                          }
                        </div>
                        <span className="text-xs font-semibold text-white/70">{nodeId}</span>
                        {isLeader && <Crown className="w-3 h-3 text-amber-400" />}
                        {isSelf && <span className="text-[8px] font-bold text-brand-400/60 bg-brand-500/10 px-1.5 py-0.5 rounded-full">SELF</span>}
                        <span className={`ml-auto text-[9px] font-bold ${
                          !health.isAlive ? 'text-red-400' : health.isPartitioned ? 'text-orange-400' : 'text-emerald-400/50'
                        }`}>
                          {!health.isAlive ? 'DEAD' : health.isPartitioned ? 'ISOLATED' : 'ALIVE'}
                        </span>
                      </div>
                    </div>
                    {/* Action buttons */}
                    <div className="flex gap-1">
                      <ActionBtn icon={Skull} label="Kill" color="red" onClick={() => killNode(nodeId)} />
                      <ActionBtn icon={RotateCcw} label="Recover" color="green" onClick={() => recoverNode(nodeId)} />
                      <ActionBtn icon={Unplug} label="Isolate" color="orange" onClick={() => partitionNode(nodeId)} />
                      <ActionBtn icon={Link2} label="Heal" color="blue" onClick={() => healPartition(nodeId)} />
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* Right: Activity Log */}
        <div className="flex-1 glass-card flex flex-col overflow-hidden">
          <div className="p-4 border-b border-white/[0.06] flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="p-1.5 rounded-lg bg-white/[0.04]">
                <ScrollText className="w-4 h-4 text-white/40" />
              </div>
              <h3 className="text-sm font-semibold text-white/60">Activity Log</h3>
              {log.length > 0 && (
                <span className="text-[10px] font-mono text-white/25 bg-white/[0.04] px-2 py-0.5 rounded-md border border-white/[0.05]">
                  {log.length}
                </span>
              )}
            </div>
            <button onClick={() => setLog([])}
              className="text-[11px] text-white/25 hover:text-white/50 flex items-center gap-1.5 transition-colors px-2.5 py-1 rounded-lg hover:bg-white/[0.04]">
              <Trash2 className="w-3 h-3" /> Clear
            </button>
          </div>
          <div className="flex-1 overflow-y-auto p-4 font-mono text-xs space-y-0.5">
            {log.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-full gap-3">
                <div className="w-16 h-16 rounded-2xl bg-white/[0.02] border border-white/[0.04] flex items-center justify-center">
                  <Sparkles className="w-7 h-7 text-white/10" />
                </div>
                <p className="text-white/20 text-sm font-sans">No chaos actions yet</p>
                <p className="text-white/10 text-xs font-sans">Use scenarios or node controls to inject failures</p>
              </div>
            ) : (
              log.map((entry, i) => (
                <div key={i} className={`py-1 px-2 rounded-lg ${
                  entry.includes('ERROR') ? 'text-red-400 bg-red-500/[0.03]' :
                  entry.includes('===') ? 'text-amber-400 font-semibold bg-amber-500/[0.03] border-l-2 border-amber-500/30 pl-3 my-1' :
                  'text-white/40 hover:text-white/55 hover:bg-white/[0.02]'
                } transition-colors`}>{entry}</div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function ActionBtn({ icon: Icon, label, color, onClick }: {
  icon: typeof Crown; label: string; color: 'red' | 'green' | 'orange' | 'blue'; onClick: () => void;
}) {
  const colors = {
    red: 'text-red-400/70 hover:text-red-400 hover:bg-red-500/10 border-red-500/10',
    green: 'text-emerald-400/70 hover:text-emerald-400 hover:bg-emerald-500/10 border-emerald-500/10',
    orange: 'text-orange-400/70 hover:text-orange-400 hover:bg-orange-500/10 border-orange-500/10',
    blue: 'text-blue-400/70 hover:text-blue-400 hover:bg-blue-500/10 border-blue-500/10',
  };
  return (
    <button onClick={onClick} title={label}
      className={`p-1.5 rounded-lg border transition-all duration-150 ${colors[color]}`}>
      <Icon className="w-3 h-3" />
    </button>
  );
}
