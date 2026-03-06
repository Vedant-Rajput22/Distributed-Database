import { useClusterStore } from '../stores/clusterStore';
import type { ClusterEvent } from '../types/events';
import {
  Crown, Users, Hash, GitCommitHorizontal, Timer,
  Activity, Server, ArrowUpDown, Shield, Zap,
  Database, HardDrive, Radio, AlertTriangle, Skull,
  HeartPulse, Gauge, CircleDot
} from 'lucide-react';

const EVENT_ICONS: Record<string, typeof Crown> = {
  RAFT: Shield,
  REPLICATION: ArrowUpDown,
  KV: Database,
  HEARTBEAT: HeartPulse,
  SNAPSHOT: HardDrive,
  TXN: Zap,
  MVCC: GitCommitHorizontal,
  CHAOS: AlertTriangle,
};

const EVENT_COLORS: Record<string, string> = {
  RAFT: 'text-purple-400',
  REPLICATION: 'text-blue-400',
  KV: 'text-emerald-400',
  HEARTBEAT: 'text-pink-400',
  SNAPSHOT: 'text-amber-400',
  TXN: 'text-cyan-400',
  MVCC: 'text-orange-400',
  CHAOS: 'text-red-400',
};

const ROLE_BADGES: Record<string, { color: string; bg: string; icon: typeof Crown; gradient: string }> = {
  LEADER: { color: 'text-emerald-400', bg: 'bg-emerald-500/10 border-emerald-500/20', icon: Crown, gradient: 'from-emerald-500/20 to-emerald-600/5' },
  FOLLOWER: { color: 'text-blue-400', bg: 'bg-blue-500/10 border-blue-500/20', icon: Users, gradient: 'from-blue-500/20 to-blue-600/5' },
  CANDIDATE: { color: 'text-amber-400', bg: 'bg-amber-500/10 border-amber-500/20', icon: Radio, gradient: 'from-amber-500/20 to-amber-600/5' },
};

export default function Sidebar() {
  const clusterStatus = useClusterStore((s) => s.clusterStatus);
  const metrics = useClusterStore((s) => s.metrics);
  const events = useClusterStore((s) => s.events);

  const isKilled = clusterStatus?.killed === true;

  const formatUptime = (ms: number) => {
    const s = Math.floor(ms / 1000);
    if (s < 60) return `${s}s`;
    if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
    return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
  };

  const formatTime = (ts: string) => {
    try {
      return new Date(ts).toLocaleTimeString('en-US', { hour12: false });
    } catch {
      return ts;
    }
  };

  const recentEvents = events.slice(0, 25);
  const roleBadge = isKilled
    ? { color: 'text-red-400', bg: 'bg-red-500/10 border-red-500/20', icon: Skull, gradient: 'from-red-500/20 to-red-600/5' }
    : (ROLE_BADGES[clusterStatus?.role || ''] || ROLE_BADGES.FOLLOWER);
  const RoleIcon = roleBadge.icon;

  return (
    <aside className={`w-72 flex-shrink-0 border-r bg-white/[0.01] backdrop-blur-xl flex flex-col overflow-hidden transition-colors duration-500 ${
      isKilled ? 'border-red-500/20' : 'border-white/[0.06]'
    }`}>
      {/* Node Identity */}
      <div className={`p-5 border-b transition-colors duration-500 ${isKilled ? 'border-red-500/15 bg-red-500/[0.03]' : 'border-white/[0.06]'}`}>
        <div className="flex items-center gap-3 mb-4">
          <div className={`relative w-11 h-11 rounded-xl border flex items-center justify-center bg-gradient-to-br ${roleBadge.gradient} ${roleBadge.bg}`}>
            <RoleIcon className={`w-5 h-5 ${roleBadge.color}`} />
            {/* Status dot */}
            <div className={`absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-surface-950 ${
              isKilled ? 'bg-red-500' : 'bg-emerald-500'
            }`} />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-bold truncate tracking-tight">{clusterStatus?.nodeId || 'Loading...'}</p>
            <div className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[10px] font-bold border mt-0.5 ${roleBadge.bg} ${roleBadge.color}`}>
              <CircleDot className="w-2.5 h-2.5" />
              {isKilled ? 'KILLED' : (clusterStatus?.role || '---')}
            </div>
          </div>
        </div>

        {/* Key Metrics Grid */}
        {clusterStatus && (
          <div className="grid grid-cols-2 gap-2">
            <MetricTile icon={Hash} label="Term" value={clusterStatus.term} color="text-brand-400" />
            <MetricTile icon={GitCommitHorizontal} label="Commit" value={clusterStatus.commitIndex} color="text-emerald-400" />
            <MetricTile
              icon={Crown}
              label="Leader"
              value={clusterStatus.leaderId?.replace('node-', 'n-') || '---'}
              color="text-amber-400"
            />
            <MetricTile icon={Users} label="Peers" value={clusterStatus.peers?.length || 0} color="text-blue-400" />
          </div>
        )}
      </div>

      {/* Quick Stats */}
      <div className={`px-5 py-4 border-b transition-colors duration-500 ${isKilled ? 'border-red-500/15' : 'border-white/[0.06]'}`}>
        <p className="section-title mb-3">System</p>
        <div className="space-y-3">
          <StatRow icon={isKilled ? Skull : Activity} label="Status" value={
            <span className={`flex items-center gap-1.5 font-semibold ${isKilled ? 'text-red-400' : 'text-emerald-400'}`}>
              <span className={`relative w-2 h-2 rounded-full ${isKilled ? 'bg-red-400' : 'bg-emerald-400'}`}>
                {!isKilled && <span className="absolute inset-0 rounded-full bg-emerald-400 animate-ping opacity-75" />}
              </span>
              {isKilled ? 'Killed' : 'Healthy'}
            </span>
          } />
          <StatRow icon={HardDrive} label="Log Size" value={<span className="font-mono text-white/60 tabular-nums">{metrics?.logSize ?? '---'}</span>} />
          <StatRow icon={Timer} label="Uptime" value={<span className="font-mono text-white/60 tabular-nums">{metrics ? formatUptime(metrics.uptime) : '---'}</span>} />
          <StatRow icon={Gauge} label="Ops/sec" value={
            <span className="font-mono text-white/60 tabular-nums">
              {metrics ? ((metrics.putOps ?? 0) + (metrics.getOps ?? 0) + (metrics.deleteOps ?? 0)) : '---'}
            </span>
          } />
        </div>
      </div>

      {/* Live Event Feed */}
      <div className="flex-1 overflow-hidden flex flex-col">
        <div className="px-5 pt-4 pb-2 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <p className="section-title !mb-0">Live Feed</p>
            <span className="inline-flex items-center gap-1 px-1.5 py-0.5 bg-white/[0.04] rounded-md text-[9px] text-white/25 font-mono border border-white/[0.04]">
              {events.length}
            </span>
          </div>
          <div className="flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
            <span className="text-[9px] text-white/20">LIVE</span>
          </div>
        </div>
        <div className="flex-1 overflow-y-auto px-3 pb-3">
          <div className="space-y-0.5">
            {recentEvents.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 gap-2">
                <Radio className="w-6 h-6 text-white/10" />
                <p className="text-[11px] text-white/20">Waiting for events...</p>
              </div>
            ) : (
              recentEvents.map((event) => {
                const Icon = EVENT_ICONS[event.category] || Radio;
                const color = EVENT_COLORS[event.category] || 'text-white/40';
                return (
                  <div
                    key={event.id}
                    className="flex items-start gap-2 py-1.5 px-2 rounded-lg hover:bg-white/[0.03] transition-colors animate-fade-in group"
                  >
                    <div className={`mt-0.5 p-1 rounded-md bg-white/[0.03] flex-shrink-0`}>
                      <Icon className={`w-2.5 h-2.5 ${color}`} />
                    </div>
                    <div className="min-w-0 flex-1">
                      <span className="text-[9px] font-mono text-white/20 tabular-nums">{formatTime(event.timestamp)}</span>
                      <p className="text-[11px] text-white/45 leading-snug break-words group-hover:text-white/65 transition-colors">
                        {event.message?.length > 65
                          ? event.message.substring(0, 65) + '…'
                          : event.message}
                      </p>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>
      </div>
    </aside>
  );
}

function MetricTile({ icon: Icon, label, value, color }: { icon: typeof Crown; label: string; value: string | number; color: string }) {
  return (
    <div className="relative bg-white/[0.03] rounded-xl p-2.5 border border-white/[0.05] overflow-hidden group hover:bg-white/[0.05] transition-all duration-200">
      <div className="flex items-center gap-1.5 mb-1">
        <Icon className={`w-3 h-3 ${color} opacity-70`} />
        <span className="text-[10px] text-white/30 font-medium tracking-wide">{label}</span>
      </div>
      <p className="text-sm font-bold font-mono text-white/90 truncate tabular-nums">{value}</p>
    </div>
  );
}

function StatRow({ icon: Icon, label, value }: { icon: typeof Crown; label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between group">
      <div className="flex items-center gap-2.5">
        <div className="p-1 rounded-md bg-white/[0.03]">
          <Icon className="w-3 h-3 text-white/25 group-hover:text-white/40 transition-colors" />
        </div>
        <span className="text-xs text-white/40 font-medium">{label}</span>
      </div>
      <div className="text-xs">{value}</div>
    </div>
  );
}
