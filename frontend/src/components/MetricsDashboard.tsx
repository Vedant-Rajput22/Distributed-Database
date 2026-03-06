import {
  LineChart, Line, AreaChart, Area,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import { useClusterStore } from '../stores/clusterStore';
import {
  Hash, GitCommit, CheckSquare, Shield, Zap, ScrollText,
  TrendingUp, Clock3, Activity, HardDrive
} from 'lucide-react';

const tooltipStyle = {
  backgroundColor: 'rgba(10,10,18,0.92)',
  border: '1px solid rgba(255,255,255,0.06)',
  borderRadius: '12px',
  backdropFilter: 'blur(12px)',
  boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
};

export default function MetricsDashboard() {
  const metricsHistory = useClusterStore((s) => s.metricsHistory);
  const metrics = useClusterStore((s) => s.metrics);
  const clusterStatus = useClusterStore((s) => s.clusterStatus);

  const fmtTime = (ts: number) => {
    if (!ts) return '';
    const d = new Date(ts);
    return `${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
  };

  const chartData = metricsHistory.map((m) => ({
    time: m.timestamp,
    putOps: m.putOps ?? 0,
    getOps: m.getOps ?? 0,
    deleteOps: m.deleteOps ?? 0,
    p50: m.latencyP50 ?? 0,
    p95: m.latencyP95 ?? 0,
    p99: m.latencyP99 ?? 0,
    elections: m.elections ?? 0,
    logEntries: m.logEntries ?? 0,
    term: m.term ?? 0,
    commitIndex: m.commitIndex ?? 0,
    compactionPending: m.compactionPending ?? 0,
  }));

  const statCards = [
    { label: 'Current Term', value: clusterStatus?.term ?? 0, icon: Hash, accent: 'text-brand-400', glow: 'bg-brand-500/10' },
    { label: 'Commit Index', value: clusterStatus?.commitIndex ?? 0, icon: GitCommit, accent: 'text-emerald-400', glow: 'bg-emerald-500/10' },
    { label: 'Last Applied', value: clusterStatus?.lastApplied ?? 0, icon: CheckSquare, accent: 'text-amber-400', glow: 'bg-amber-500/10' },
    { label: 'Role', value: clusterStatus?.role ?? '—', icon: Shield, accent: 'text-purple-400', glow: 'bg-purple-500/10' },
    { label: 'Elections', value: metrics?.elections ?? 0, icon: Zap, accent: 'text-red-400', glow: 'bg-red-500/10' },
    { label: 'Log Entries', value: metrics?.logEntries ?? 0, icon: ScrollText, accent: 'text-cyan-400', glow: 'bg-cyan-500/10' },
  ];

  const gridStroke = 'rgba(255,255,255,0.04)';
  const axisStroke = 'rgba(255,255,255,0.12)';

  return (
    <div className="h-full flex flex-col gap-4 overflow-y-auto">
      {/* Header */}
      <div>
        <h2 className="text-xl font-bold tracking-tight bg-gradient-to-r from-white to-white/70 bg-clip-text text-transparent">Metrics Dashboard</h2>
        <p className="text-xs text-white/30 mt-0.5">Real-time performance and health indicators</p>
      </div>

      {/* Stat Cards */}
      <div className="grid grid-cols-6 gap-3">
        {statCards.map((s) => {
          const Icon = s.icon;
          return (
            <div key={s.label} className="glass-card p-4 text-center group hover:scale-[1.02] transition-transform duration-200">
              <div className={`w-8 h-8 mx-auto mb-2 rounded-lg flex items-center justify-center ${s.glow}`}>
                <Icon className={`w-4 h-4 ${s.accent}`} />
              </div>
              <div className={`text-xl font-bold font-mono ${s.accent}`}>{s.value}</div>
              <div className="text-[10px] text-white/25 mt-1 uppercase tracking-wider">{s.label}</div>
            </div>
          );
        })}
      </div>

      {/* Charts Grid */}
      <div className="grid grid-cols-2 gap-4 flex-1 min-h-0">
        {/* Throughput */}
        <div className="glass-card p-5">
          <div className="flex items-center gap-2 mb-4">
            <TrendingUp className="w-4 h-4 text-white/30" />
            <h3 className="text-sm font-semibold text-white/50">Operations / sec</h3>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <AreaChart data={chartData}>
              <defs>
                <linearGradient id="gradPut" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#10b981" stopOpacity={0.2} />
                  <stop offset="95%" stopColor="#10b981" stopOpacity={0} />
                </linearGradient>
                <linearGradient id="gradGet" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#818cf8" stopOpacity={0.2} />
                  <stop offset="95%" stopColor="#818cf8" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke={gridStroke} />
              <XAxis dataKey="time" tickFormatter={fmtTime} stroke={axisStroke} fontSize={10} />
              <YAxis stroke={axisStroke} fontSize={10} />
              <Tooltip contentStyle={tooltipStyle} labelFormatter={(l) => fmtTime(l as number)} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Area type="monotone" dataKey="putOps" name="PUT" stroke="#10b981" fill="url(#gradPut)" strokeWidth={2} />
              <Area type="monotone" dataKey="getOps" name="GET" stroke="#818cf8" fill="url(#gradGet)" strokeWidth={2} />
              <Line type="monotone" dataKey="deleteOps" name="DELETE" stroke="#f87171" strokeWidth={1.5} dot={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>

        {/* Latency */}
        <div className="glass-card p-5">
          <div className="flex items-center gap-2 mb-4">
            <Clock3 className="w-4 h-4 text-white/30" />
            <h3 className="text-sm font-semibold text-white/50">Latency (ms)</h3>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke={gridStroke} />
              <XAxis dataKey="time" tickFormatter={fmtTime} stroke={axisStroke} fontSize={10} />
              <YAxis stroke={axisStroke} fontSize={10} />
              <Tooltip contentStyle={tooltipStyle} labelFormatter={(l) => fmtTime(l as number)} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Line type="monotone" dataKey="p50" name="p50" stroke="#10b981" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p95" name="p95" stroke="#f59e0b" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="p99" name="p99" stroke="#f87171" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        {/* Raft Term & Elections */}
        <div className="glass-card p-5">
          <div className="flex items-center gap-2 mb-4">
            <Activity className="w-4 h-4 text-white/30" />
            <h3 className="text-sm font-semibold text-white/50">Raft State</h3>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke={gridStroke} />
              <XAxis dataKey="time" tickFormatter={fmtTime} stroke={axisStroke} fontSize={10} />
              <YAxis yAxisId="left" stroke={axisStroke} fontSize={10} />
              <YAxis yAxisId="right" orientation="right" stroke={axisStroke} fontSize={10} />
              <Tooltip contentStyle={tooltipStyle} labelFormatter={(l) => fmtTime(l as number)} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Line yAxisId="left" type="stepAfter" dataKey="term" name="Term" stroke="#a78bfa" strokeWidth={2} dot={false} />
              <Line yAxisId="left" type="monotone" dataKey="commitIndex" name="Commit Idx" stroke="#10b981" strokeWidth={1.5} dot={false} />
              <Line yAxisId="right" type="stepAfter" dataKey="elections" name="Elections" stroke="#f87171" strokeWidth={1.5} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        {/* Log Growth */}
        <div className="glass-card p-5">
          <div className="flex items-center gap-2 mb-4">
            <HardDrive className="w-4 h-4 text-white/30" />
            <h3 className="text-sm font-semibold text-white/50">Log & Storage</h3>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <AreaChart data={chartData}>
              <defs>
                <linearGradient id="gradLog" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#22d3ee" stopOpacity={0.2} />
                  <stop offset="95%" stopColor="#22d3ee" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke={gridStroke} />
              <XAxis dataKey="time" tickFormatter={fmtTime} stroke={axisStroke} fontSize={10} />
              <YAxis stroke={axisStroke} fontSize={10} />
              <Tooltip contentStyle={tooltipStyle} labelFormatter={(l) => fmtTime(l as number)} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Area type="monotone" dataKey="logEntries" name="Log Entries" stroke="#22d3ee" fill="url(#gradLog)" strokeWidth={2} />
              <Line type="monotone" dataKey="compactionPending" name="Compactions" stroke="#fb923c" strokeWidth={1.5} dot={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  );
}
