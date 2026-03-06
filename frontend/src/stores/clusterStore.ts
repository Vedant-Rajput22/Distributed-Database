import { create } from 'zustand';
import type {
  ClusterStatus,
  NodeInfo,
  ClusterEvent,
  LogEntry,
  KvPair,
  MetricsOverview,
  SnapshotInfo,
  StorageStats,
} from '../types/events';

interface MetricsSample {
  timestamp: number;
  opsPerSec: number;
  putOps: number;
  getOps: number;
  deleteOps: number;
  latencyP50: number;
  latencyP95: number;
  latencyP99: number;
  elections: number;
  logEntries: number;
  term: number;
  commitIndex: number;
  compactionPending: number;
}

interface ClusterStore {
  // Cluster state
  clusterStatus: ClusterStatus | null;
  nodes: NodeInfo[];
  events: ClusterEvent[];
  logEntries: LogEntry[];
  kvPairs: KvPair[];
  metrics: MetricsOverview | null;
  snapshotInfo: SnapshotInfo | null;
  storageStats: StorageStats | null;
  metricsHistory: MetricsSample[];

  // UI state
  activeTab: string;
  darkMode: boolean;
  eventFilters: Set<string>;
  connected: boolean;
  apiBaseUrl: string; // e.g. '' (proxy), 'http://localhost:8082'

  // Actions
  setClusterStatus: (status: ClusterStatus) => void;
  setNodes: (nodes: NodeInfo[]) => void;
  addEvent: (event: ClusterEvent) => void;
  setEvents: (events: ClusterEvent[]) => void;
  setLogEntries: (entries: LogEntry[]) => void;
  setKvPairs: (pairs: KvPair[]) => void;
  setMetrics: (metrics: MetricsOverview) => void;
  setSnapshotInfo: (info: SnapshotInfo) => void;
  setStorageStats: (stats: StorageStats) => void;
  addMetricsSample: (sample: MetricsSample) => void;
  setActiveTab: (tab: string) => void;
  toggleDarkMode: () => void;
  toggleEventFilter: (category: string) => void;
  setConnected: (connected: boolean) => void;
  setApiBaseUrl: (url: string) => void;
}

const MAX_EVENTS = 500;
const MAX_METRICS_HISTORY = 300;

export const useClusterStore = create<ClusterStore>((set) => ({
  // Initial state
  clusterStatus: null,
  nodes: [],
  events: [],
  logEntries: [],
  kvPairs: [],
  metrics: null,
  snapshotInfo: null,
  storageStats: null,
  metricsHistory: [],
  activeTab: 'topology',
  darkMode: true,
  eventFilters: new Set(['RAFT', 'REPLICATION', 'KV', 'HEARTBEAT', 'SNAPSHOT', 'TXN']),
  connected: false,
  apiBaseUrl: '',

  // Actions
  setClusterStatus: (status) => set({ clusterStatus: status }),

  setNodes: (nodes) => set({ nodes }),

  addEvent: (event) =>
    set((state) => ({
      events: [event, ...state.events].slice(0, MAX_EVENTS),
    })),

  setEvents: (events) => set({ events }),

  setLogEntries: (entries) => set({ logEntries: entries }),

  setKvPairs: (pairs) => set({ kvPairs: pairs }),

  setMetrics: (metrics) => set({ metrics }),

  setSnapshotInfo: (info) => set({ snapshotInfo: info }),

  setStorageStats: (stats) => set({ storageStats: stats }),

  addMetricsSample: (sample) =>
    set((state) => ({
      metricsHistory: [...state.metricsHistory, sample].slice(-MAX_METRICS_HISTORY),
    })),

  setActiveTab: (tab) => set({ activeTab: tab }),

  toggleDarkMode: () => set((state) => ({ darkMode: !state.darkMode })),

  toggleEventFilter: (category) =>
    set((state) => {
      const newFilters = new Set(state.eventFilters);
      if (newFilters.has(category)) {
        newFilters.delete(category);
      } else {
        newFilters.add(category);
      }
      return { eventFilters: newFilters };
    }),

  setConnected: (connected) => set({ connected }),

  setApiBaseUrl: (url) => set({ apiBaseUrl: url }),
}));
