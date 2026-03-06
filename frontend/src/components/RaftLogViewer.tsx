import { useState, useEffect, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useClusterStore } from '../stores/clusterStore';
import type { LogEntry } from '../types/events';
import {
  ScrollText, RefreshCw, CheckCircle2, Clock, Loader2,
  Crown, Server, ArrowUpCircle, ArrowDownCircle, Minus
} from 'lucide-react';

function getApiBase() {
  return useClusterStore.getState().apiBaseUrl || '/api';
}

export default function RaftLogViewer() {
  const clusterStatus = useClusterStore((s) => s.clusterStatus);
  const [logs, setLogs] = useState<Record<string, LogEntry[]>>({});
  const [loading, setLoading] = useState(false);

  const fetchLogs = useCallback(async () => {
    if (!clusterStatus) return;
    setLoading(true);
    try {
      const res = await fetch(`${getApiBase()}/node/${clusterStatus.nodeId}/log?size=100`);
      if (res.ok) {
        const data = await res.json();
        setLogs((prev) => ({ ...prev, [clusterStatus.nodeId]: data.entries || [] }));
      }
    } catch { console.debug('Failed to fetch logs'); }
    setLoading(false);
  }, [clusterStatus]);

  useEffect(() => {
    fetchLogs();
    const interval = setInterval(fetchLogs, 2000);
    return () => clearInterval(interval);
  }, [fetchLogs]);

  const allNodeIds = clusterStatus
    ? [clusterStatus.nodeId, ...(clusterStatus.peers || [])]
    : [];

  const StatusIcon = ({ entry }: { entry: LogEntry }) => {
    if (entry.applied) return <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />;
    if (entry.committed) return <Clock className="w-3.5 h-3.5 text-amber-400" />;
    return <Loader2 className="w-3.5 h-3.5 text-brand-400 animate-spin" />;
  };

  const getStatusLabel = (entry: LogEntry) => {
    if (entry.applied) return 'Applied';
    if (entry.committed) return 'Committed';
    return 'Pending';
  };

  const getStatusColor = (entry: LogEntry) => {
    if (entry.applied) return 'text-emerald-400/60';
    if (entry.committed) return 'text-amber-400/60';
    return 'text-brand-400/60';
  };

  const typeStyles: Record<string, string> = {
    PUT: 'text-emerald-400 bg-emerald-500/10',
    DELETE: 'text-red-400 bg-red-500/10',
    NOOP: 'text-white/30 bg-white/[0.03]',
  };

  return (
    <div className="h-full flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold tracking-tight bg-gradient-to-r from-white to-white/70 bg-clip-text text-transparent">Raft Log Viewer</h2>
          <p className="text-xs text-white/30 mt-0.5">Replicated consensus log entries across cluster nodes</p>
        </div>
        <button onClick={fetchLogs} disabled={loading}
          className="glass-button flex items-center gap-2 text-xs !py-2">
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
          {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      {/* Node Columns */}
      <div className="flex-1 overflow-x-auto">
        <div className="flex gap-3 min-w-max h-full">
          {allNodeIds.map((nodeId) => {
            const entries = logs[nodeId] || [];
            const isSelf = nodeId === clusterStatus?.nodeId;
            const isLeader = nodeId === clusterStatus?.leaderId;

            return (
              <div key={nodeId}
                className={`flex-1 min-w-[300px] glass-card flex flex-col overflow-hidden transition-all duration-300 ${
                  isLeader ? 'ring-1 ring-amber-500/15' : ''
                }`}>
                {/* Node Header */}
                <div className="p-4 border-b border-white/[0.06] flex items-center justify-between">
                  <div className="flex items-center gap-2.5">
                    <div className={`w-8 h-8 rounded-xl flex items-center justify-center ${
                      isLeader ? 'bg-amber-500/10' : 'bg-white/[0.04]'
                    }`}>
                      {isLeader ? <Crown className="w-4 h-4 text-amber-400" /> : <Server className="w-4 h-4 text-white/30" />}
                    </div>
                    <div>
                      <h3 className="font-bold text-sm tracking-tight">{nodeId}</h3>
                      <p className="text-[10px] text-white/25">
                        {isSelf ? `${clusterStatus?.role} · Term ${clusterStatus?.term}` : 'Peer node'}
                      </p>
                    </div>
                  </div>
                  {isSelf && (
                    <div className="text-right">
                      <div className="text-[10px] text-white/20 font-mono">commit: {clusterStatus?.commitIndex}</div>
                      <div className="text-[10px] text-white/20 font-mono">applied: {clusterStatus?.lastApplied}</div>
                    </div>
                  )}
                </div>

                {/* Log Entries */}
                <div className="flex-1 overflow-y-auto p-2 space-y-0.5">
                  {!isSelf ? (
                    <div className="flex flex-col items-center justify-center h-full gap-2">
                      <Server className="w-6 h-6 text-white/10" />
                      <p className="text-white/20 text-xs">Connect to peer to view log</p>
                    </div>
                  ) : entries.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-full gap-2">
                      <ScrollText className="w-6 h-6 text-white/10" />
                      <p className="text-white/20 text-xs">No log entries</p>
                    </div>
                  ) : (
                    <AnimatePresence>
                      {entries.map((entry) => (
                        <motion.div key={entry.index}
                          initial={{ opacity: 0, x: -8 }}
                          animate={{ opacity: 1, x: 0 }}
                          exit={{ opacity: 0 }}
                          className={`flex items-center gap-2 px-2.5 py-2 rounded-lg text-xs font-mono transition-colors ${
                            entry.index === clusterStatus?.commitIndex
                              ? 'bg-emerald-500/[0.06] border border-emerald-500/10'
                              : 'hover:bg-white/[0.02]'
                          }`}>
                          <StatusIcon entry={entry} />
                          <span className="text-white/20 w-10 text-[10px]">#{entry.index}</span>
                          <span className="text-white/15 w-7 text-[10px]">T{entry.term}</span>
                          <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${typeStyles[entry.type] || 'text-brand-400 bg-brand-500/10'}`}>
                            {entry.type}
                          </span>
                          <span className="text-white/50 flex-1 truncate text-[11px]">{entry.key || '—'}</span>
                          <span className={`text-[9px] ${getStatusColor(entry)}`}>{getStatusLabel(entry)}</span>
                        </motion.div>
                      ))}
                    </AnimatePresence>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Legend */}
      <div className="flex items-center gap-5 text-[11px] text-white/25 px-1">
        <span className="flex items-center gap-1.5"><CheckCircle2 className="w-3 h-3 text-emerald-400/60" /> Applied</span>
        <span className="flex items-center gap-1.5"><Clock className="w-3 h-3 text-amber-400/60" /> Committed</span>
        <span className="flex items-center gap-1.5"><Loader2 className="w-3 h-3 text-brand-400/60" /> Replicating</span>
        <span className="ml-auto font-mono text-white/15">commitIndex — matchIndex — nextIndex</span>
      </div>
    </div>
  );
}
