package com.minidb.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a cluster event that can be published to the UI.
 */
public class ClusterEvent {

    public enum Category {
        RAFT, REPLICATION, KV, HEARTBEAT, SNAPSHOT, TXN, MVCC, CHAOS
    }

    private final String id;
    private final Instant timestamp;
    private final Category category;
    private final String type;
    private final String nodeId;
    private final String message;
    private final Map<String, Object> data;

    public ClusterEvent(Category category, String type, String nodeId,
                        String message, Map<String, Object> data) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.category = category;
        this.type = type;
        this.nodeId = nodeId;
        this.message = message;
        this.data = data;
    }

    // Factory methods for different event types

    public static ClusterEvent raftStateChange(String nodeId, String oldRole, String newRole, long term) {
        return new ClusterEvent(Category.RAFT, "STATE_CHANGE", nodeId,
                String.format("Node %s: %s → %s (term %d)", nodeId, oldRole, newRole, term),
                Map.of("oldRole", oldRole, "newRole", newRole, "term", term));
    }

    public static ClusterEvent electionStarted(String nodeId, long term) {
        return new ClusterEvent(Category.RAFT, "ELECTION_STARTED", nodeId,
                String.format("Node %s started election (term %d)", nodeId, term),
                Map.of("term", term));
    }

    public static ClusterEvent voteGranted(String voterId, String candidateId, long term) {
        return new ClusterEvent(Category.RAFT, "VOTE_GRANTED", voterId,
                String.format("Node %s granted vote to %s (term %d)", voterId, candidateId, term),
                Map.of("voterId", voterId, "candidateId", candidateId, "term", term));
    }

    public static ClusterEvent voteRejected(String voterId, String candidateId, long term) {
        return new ClusterEvent(Category.RAFT, "VOTE_REJECTED", voterId,
                String.format("Node %s rejected vote for %s (term %d)", voterId, candidateId, term),
                Map.of("voterId", voterId, "candidateId", candidateId, "term", term));
    }

    public static ClusterEvent leaderElected(String nodeId, long term) {
        return new ClusterEvent(Category.RAFT, "LEADER_ELECTED", nodeId,
                String.format("Node %s elected LEADER (term %d)", nodeId, term),
                Map.of("term", term));
    }

    public static ClusterEvent heartbeatSent(String leaderId, String followerId, long latencyMs) {
        return new ClusterEvent(Category.HEARTBEAT, "HEARTBEAT_SENT", leaderId,
                String.format("Heartbeat %s → %s (%.1fms)", leaderId, followerId, (double) latencyMs),
                Map.of("leaderId", leaderId, "followerId", followerId, "latencyMs", latencyMs));
    }

    public static ClusterEvent heartbeatReceived(String followerId, String leaderId) {
        return new ClusterEvent(Category.HEARTBEAT, "HEARTBEAT_RECEIVED", followerId,
                String.format("Heartbeat received from %s", leaderId),
                Map.of("leaderId", leaderId));
    }

    public static ClusterEvent appendEntriesSent(String leaderId, String followerId,
                                                   int entryCount, long prevLogIndex) {
        return new ClusterEvent(Category.REPLICATION, "APPEND_ENTRIES_SENT", leaderId,
                String.format("AppendEntries %s → %s (entries: %d, prevIdx: %d)",
                        leaderId, followerId, entryCount, prevLogIndex),
                Map.of("leaderId", leaderId, "followerId", followerId,
                        "entryCount", entryCount, "prevLogIndex", prevLogIndex));
    }

    public static ClusterEvent appendEntriesAcked(String followerId, long matchIndex) {
        return new ClusterEvent(Category.REPLICATION, "APPEND_ENTRIES_ACKED", followerId,
                String.format("AppendEntries ACK from %s (matchIndex: %d)", followerId, matchIndex),
                Map.of("followerId", followerId, "matchIndex", matchIndex));
    }

    public static ClusterEvent appendEntriesRejected(String followerId, long term) {
        return new ClusterEvent(Category.REPLICATION, "APPEND_ENTRIES_REJECTED", followerId,
                String.format("AppendEntries REJECTED by %s (term: %d)", followerId, term),
                Map.of("followerId", followerId, "term", term));
    }

    public static ClusterEvent kvOperation(String op, String key, long latencyMs, boolean success) {
        return new ClusterEvent(Category.KV, "KV_OPERATION", null,
                String.format("%s %s %s (%.1fms)", op, key, success ? "committed" : "failed", (double) latencyMs),
                Map.of("operation", op, "key", key, "latencyMs", latencyMs, "success", success));
    }

    public static ClusterEvent mvccVersionCreated(String key, long timestamp, int valueSize) {
        return new ClusterEvent(Category.MVCC, "VERSION_CREATED", null,
                String.format("MVCC version created: %s @ ts=%d (%d bytes)", key, timestamp, valueSize),
                Map.of("key", key, "timestamp", timestamp, "valueSize", valueSize));
    }

    public static ClusterEvent mvccGarbageCollection(int purgedCount, long cutoffTimestamp) {
        return new ClusterEvent(Category.MVCC, "GC_COMPLETED", null,
                String.format("MVCC GC: purged %d versions older than %d", purgedCount, cutoffTimestamp),
                Map.of("purgedCount", purgedCount, "cutoffTimestamp", cutoffTimestamp));
    }

    public static ClusterEvent snapshotCreated(String nodeId, long lastIndex, long sizeBytes) {
        return new ClusterEvent(Category.SNAPSHOT, "SNAPSHOT_CREATED", nodeId,
                String.format("Snapshot created on %s (idx: %d, %s)",
                        nodeId, lastIndex, formatBytes(sizeBytes)),
                Map.of("lastIndex", lastIndex, "sizeBytes", sizeBytes));
    }

    public static ClusterEvent snapshotInstallProgress(String nodeId, long offset, long total) {
        return new ClusterEvent(Category.SNAPSHOT, "SNAPSHOT_INSTALL_PROGRESS", nodeId,
                String.format("Snapshot install on %s: %d/%d bytes", nodeId, offset, total),
                Map.of("offset", offset, "total", total,
                        "progress", total > 0 ? (double) offset / total : 0));
    }

    public static ClusterEvent txnPhase(String txnId, String phase, String key, boolean success) {
        return new ClusterEvent(Category.TXN, "TXN_" + phase.toUpperCase(), null,
                String.format("2PC %s: txn=%s, key=%s, %s",
                        phase, txnId, key, success ? "SUCCESS" : "FAILED"),
                Map.of("txnId", txnId, "phase", phase, "key", key, "success", success));
    }

    public static ClusterEvent chaosAction(String action, String nodeId) {
        return new ClusterEvent(Category.CHAOS, "CHAOS_" + action.toUpperCase(), nodeId,
                String.format("Chaos: %s on %s", action, nodeId),
                Map.of("action", action, "nodeId", nodeId));
    }

    public static ClusterEvent nodeDown(String nodeId) {
        return new ClusterEvent(Category.CHAOS, "NODE_DOWN", nodeId,
                String.format("Node %s is DOWN", nodeId),
                Map.of("nodeId", nodeId));
    }

    public static ClusterEvent nodeRecovered(String nodeId) {
        return new ClusterEvent(Category.CHAOS, "NODE_RECOVERED", nodeId,
                String.format("Node %s recovered", nodeId),
                Map.of("nodeId", nodeId));
    }

    // ================================================================
    // Speculative MVCC Events (Novel Contribution)
    // ================================================================

    public static ClusterEvent speculativeVersionCreated(String key, long timestamp,
                                                          int valueSize, long raftLogIndex) {
        return new ClusterEvent(Category.MVCC, "SPECULATIVE_VERSION_CREATED", null,
                String.format("Speculative write: %s @ ts=%d (raftIdx=%d, %d bytes)",
                        key, timestamp, raftLogIndex, valueSize),
                Map.of("key", key, "timestamp", timestamp, "valueSize", valueSize,
                        "raftLogIndex", raftLogIndex, "speculative", true));
    }

    public static ClusterEvent speculativeCommitted(String key, long timestamp) {
        return new ClusterEvent(Category.MVCC, "SPECULATIVE_COMMITTED", null,
                String.format("Speculative → COMMITTED: %s @ ts=%d", key, timestamp),
                Map.of("key", key, "timestamp", timestamp, "speculative", false));
    }

    public static ClusterEvent speculativeRolledBack(String key, long timestamp) {
        return new ClusterEvent(Category.MVCC, "SPECULATIVE_ROLLED_BACK", null,
                String.format("Speculative → ROLLED_BACK: %s @ ts=%d", key, timestamp),
                Map.of("key", key, "timestamp", timestamp, "rolledBack", true));
    }

    public static ClusterEvent cascadeRollback(int count, long fromRaftIndex) {
        return new ClusterEvent(Category.MVCC, "CASCADE_ROLLBACK", null,
                String.format("Cascade rollback: %d speculative versions (raftIdx ≥ %d)",
                        count, fromRaftIndex),
                Map.of("count", count, "fromRaftIndex", fromRaftIndex));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // Getters
    public String getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public Category getCategory() { return category; }
    public String getType() { return type; }
    public String getNodeId() { return nodeId; }
    public String getMessage() { return message; }
    public Map<String, Object> getData() { return data; }
}
