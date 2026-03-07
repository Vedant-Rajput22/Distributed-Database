export type NodeRole = 'LEADER' | 'FOLLOWER' | 'CANDIDATE';

export interface NodeInfo {
  id: string;
  role?: NodeRole;
  term?: number;
  commitIndex?: number;
  lastApplied?: number;
  logSize?: number;
  isUp?: boolean;
  isAlive?: boolean;
  isPartitioned?: boolean;
  lastSeen?: number | null;
  uptime?: number;
  isLeader?: boolean;
  nextIndex?: number;
  matchIndex?: number;
}

export interface ClusterStatus {
  nodeId: string;
  role: NodeRole;
  term: number;
  leaderId: string | null;
  commitIndex: number;
  lastApplied: number;
  votedFor: string | null;
  logSize: number;
  killed: boolean;
  uptime: number;
  peers: string[];
  partitionedPeers: string[];
  peerHealth?: Record<string, boolean>;
}

export interface LogEntry {
  index: number;
  term: number;
  type: string;
  key: string | null;
  committed: boolean;
  applied: boolean;
}

export interface NodeLogResponse {
  nodeId: string;
  entries: LogEntry[];
  totalEntries: number;
  page: number;
  size: number;
  commitIndex: number;
  lastApplied: number;
}

export interface KvPair {
  key: string;
  value: string;
  timestamp: number;
  version?: number;
}

export interface MvccVersion {
  version: number;
  timestamp: number;
  value: string | null;
  tombstone: boolean;
  speculative?: boolean;
  versionState?: 'SPECULATIVE' | 'COMMITTED' | 'ROLLED_BACK';
}

// ── Speculation Types ──

export interface SpeculationMetrics {
  speculationEnabled: boolean;
  avgSpeculativeLatencyMs: number;
  avgStandardLatencyMs: number;
  avgPromotionLatencyMs: number;
  speculativeWriteCount: number;
  standardWriteCount: number;
  pendingSpeculations: number;
  mvccSpeculativeWrites: number;
  mvccCommitPromotions: number;
  mvccRollbacks: number;
  mvccSpeculationSuccessRate: number;
}

export interface BenchmarkResult {
  mode: string;
  numOps: number;
  concurrency: number;
  valueSizeBytes: number;
  standard?: LatencyReport;
  speculative?: LatencyReport;
  baselineOptimizations?: {
    writeBatching: boolean;
    batchWindowUs: number;
    description: string;
  };
  error?: string;
}

export interface LatencyReport {
  label: string;
  totalOps: number;
  successCount: number;
  failCount: number;
  totalTimeMs: number;
  throughputOpsPerSec: number;
  writeLatency?: PercentileStats;
  promotionLatency?: PercentileStats;
}

export interface PercentileStats {
  count: number;
  p50Ms: number;
  p75Ms: number;
  p90Ms: number;
  p95Ms: number;
  p99Ms: number;
  p999Ms: number;
  minMs: number;
  maxMs: number;
  avgMs: number;
}

export interface GcStressResult {
  numVersions: number;
  rollbackPercent: number;
  rolledBackCount: number;
  committedCount: number;
  createTimeMs: number;
  rollbackTimeMs: number;
  perVersionRollbackUs: number;
  gc?: {
    purgedCount: number;
    gcDurationMs: number;
    perVersionGcUs: number;
  };
  throughputComparison?: {
    withoutGcOpsPerSec: number;
    duringGcOpsPerSec: number;
    throughputImpactPercent: number;
    latencyWithoutGc?: PercentileStats;
    latencyDuringGc?: PercentileStats;
  };
  verdict?: string;
}

export type EventCategory =
  | 'RAFT'
  | 'REPLICATION'
  | 'KV'
  | 'HEARTBEAT'
  | 'SNAPSHOT'
  | 'TXN'
  | 'MVCC'
  | 'CHAOS';

export interface ClusterEvent {
  id: string;
  timestamp: string;
  category: EventCategory;
  type: string;
  nodeId: string | null;
  message: string;
  data: Record<string, unknown>;
}

export interface MetricsOverview {
  nodeId: string;
  role: NodeRole;
  term: number;
  commitIndex: number;
  lastApplied: number;
  logSize: number;
  uptime: number;
  killed: boolean;
  elections?: number;
  logEntries?: number;
  putOps?: number;
  getOps?: number;
  deleteOps?: number;
  latencyP50?: number;
  latencyP95?: number;
  latencyP99?: number;
  compactionPending?: number;
}

export interface SnapshotInfo {
  lastSnapshotIndex: number;
  lastSnapshotTerm: number;
  lastSnapshotSize: number;
  lastSnapshotTime: number;
}

export interface StorageStats {
  [key: string]: string;
}

export interface TxnOperation {
  type: 'PUT' | 'DELETE';
  key: string;
  value?: string;
}
