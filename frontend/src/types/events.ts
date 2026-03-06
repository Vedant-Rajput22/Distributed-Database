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
