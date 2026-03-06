import { useEffect, useCallback, useRef } from 'react';
import { useClusterStore } from '../stores/clusterStore';
import type { ClusterStatus, NodeInfo, ClusterEvent, MetricsOverview } from '../types/events';

/** Return the effective API base: either a direct URL or the default /api proxy */
function getApiBase() {
  return useClusterStore.getState().apiBaseUrl || '/api';
}

export function useClusterState() {
  const {
    setClusterStatus,
    setNodes,
    setEvents,
    setMetrics,
    setSnapshotInfo,
    setStorageStats,
    addMetricsSample,
    setConnected,
  } = useClusterStore();

  const apiBaseUrl = useClusterStore((s) => s.apiBaseUrl);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchClusterStatus = useCallback(async () => {
    const base = getApiBase();
    try {
      const res = await fetch(`${base}/cluster/status`);
      if (res.ok) {
        const data: ClusterStatus = await res.json();
        setClusterStatus(data);
        setConnected(true);
      }
    } catch (e) {
      console.debug('Failed to fetch cluster status');
      setConnected(false);
    }
  }, [setClusterStatus, setConnected]);

  const fetchNodes = useCallback(async () => {
    const base = getApiBase();
    try {
      const res = await fetch(`${base}/nodes`);
      if (res.ok) {
        const data: NodeInfo[] = await res.json();
        setNodes(data);
      }
    } catch (e) {
      console.debug('Failed to fetch nodes');
    }
  }, [setNodes]);

  const fetchEvents = useCallback(async (limit = 100) => {
    const base = getApiBase();
    try {
      const res = await fetch(`${base}/events?limit=${limit}`);
      if (res.ok) {
        const data: ClusterEvent[] = await res.json();
        setEvents(data);
      }
    } catch (e) {
      console.debug('Failed to fetch events');
    }
  }, [setEvents]);

  const fetchMetrics = useCallback(async () => {
    const base = getApiBase();
    try {
      const [overviewRes, snapshotRes, storageRes] = await Promise.all([
        fetch(`${base}/metrics/overview`),
        fetch(`${base}/metrics/snapshot`),
        fetch(`${base}/metrics/storage`),
      ]);

      if (overviewRes.ok) {
        const overview = await overviewRes.json();
        setMetrics(overview);

        addMetricsSample({
          timestamp: Date.now(),
          opsPerSec: (overview.putOps ?? 0) + (overview.getOps ?? 0) + (overview.deleteOps ?? 0),
          putOps: overview.putOps ?? 0,
          getOps: overview.getOps ?? 0,
          deleteOps: overview.deleteOps ?? 0,
          latencyP50: overview.latencyP50 ?? 0,
          latencyP95: overview.latencyP95 ?? 0,
          latencyP99: overview.latencyP99 ?? 0,
          elections: overview.elections ?? 0,
          logEntries: overview.logSize ?? 0,
          term: overview.term ?? 0,
          commitIndex: overview.commitIndex ?? 0,
          compactionPending: overview.compactionPending ?? 0,
        });
      }
      if (snapshotRes.ok) {
        setSnapshotInfo(await snapshotRes.json());
      }
      if (storageRes.ok) {
        setStorageStats(await storageRes.json());
      }
    } catch (e) {
      console.debug('Failed to fetch metrics');
    }
  }, [setMetrics, setSnapshotInfo, setStorageStats, addMetricsSample]);

  const startPolling = useCallback(
    (intervalMs = 1000) => {
      const poll = async () => {
        await Promise.all([fetchClusterStatus(), fetchNodes(), fetchMetrics()]);
      };
      poll();
      intervalRef.current = setInterval(poll, intervalMs);
    },
    [fetchClusterStatus, fetchNodes, fetchMetrics],
  );

  const stopPolling = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  // Restart polling whenever apiBaseUrl changes
  useEffect(() => {
    startPolling(1000);
    fetchEvents(200);
    return () => stopPolling();
  }, [startPolling, stopPolling, fetchEvents, apiBaseUrl]);

  return {
    fetchClusterStatus,
    fetchNodes,
    fetchEvents,
    fetchMetrics,
  };
}