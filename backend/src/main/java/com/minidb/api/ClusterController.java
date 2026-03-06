package com.minidb.api;

import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.raft.RaftLog;
import com.minidb.raft.RaftNode;
import com.minidb.raft.SnapshotManager;
import com.minidb.storage.StorageEngine;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for cluster status and node information.
 */
@RestController
@RequestMapping("/api")
public class ClusterController {

    private final RaftNode raftNode;
    private final EventBus eventBus;
    private final StorageEngine storage;

    public ClusterController(RaftNode raftNode, EventBus eventBus, StorageEngine storage) {
        this.raftNode = raftNode;
        this.eventBus = eventBus;
        this.storage = storage;
    }

    @GetMapping("/cluster/status")
    public Map<String, Object> getClusterStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeId", raftNode.getNodeId());
        status.put("role", raftNode.getRole().name());
        status.put("term", raftNode.getCurrentTerm());
        status.put("leaderId", raftNode.getLeaderId());
        status.put("commitIndex", raftNode.getCommitIndex());
        status.put("lastApplied", raftNode.getLastApplied());
        status.put("votedFor", raftNode.getVotedFor());
        status.put("logSize", raftNode.getRaftLog().getLastIndex());
        status.put("killed", raftNode.isKilled());
        status.put("uptime", raftNode.getUptime());
        status.put("peers", raftNode.getPeers().stream()
                .map(p -> p.contains("@") ? p.split("@")[0] : p)
                .collect(Collectors.toList()));
        status.put("partitionedPeers", raftNode.getPartitionedPeers());

        // Include peer health map: peerDisplayId -> alive boolean
        Map<String, Boolean> peerHealth = new LinkedHashMap<>();
        for (String peer : raftNode.getPeers()) {
            String displayId = peer.contains("@") ? peer.split("@")[0] : peer;
            peerHealth.put(displayId, raftNode.isPeerAlive(peer));
        }
        status.put("peerHealth", peerHealth);
        return status;
    }

    @GetMapping("/nodes")
    public List<Map<String, Object>> getNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();

        // Current node
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("id", raftNode.getNodeId());
        self.put("role", raftNode.getRole().name());
        self.put("term", raftNode.getCurrentTerm());
        self.put("commitIndex", raftNode.getCommitIndex());
        self.put("lastApplied", raftNode.getLastApplied());
        self.put("logSize", raftNode.getRaftLog().getLastIndex());
        self.put("isUp", !raftNode.isKilled());
        self.put("uptime", raftNode.getUptime());
        self.put("isLeader", raftNode.isLeader());
        nodes.add(self);

        // Peers (what we know about them)
        for (String peer : raftNode.getPeers()) {
            Map<String, Object> peerInfo = new LinkedHashMap<>();
            // Extract display name from peer format "node-id@host:port" or fallback to address
            String displayId = peer.contains("@") ? peer.split("@")[0] : peer;
            peerInfo.put("id", displayId);
            peerInfo.put("address", peer.contains("@") ? peer.split("@")[1] : peer);
            peerInfo.put("role", displayId.equals(raftNode.getLeaderId()) ? "LEADER" : "FOLLOWER");
            peerInfo.put("nextIndex", raftNode.getNextIndex().getOrDefault(peer, 0L));
            peerInfo.put("matchIndex", raftNode.getMatchIndex().getOrDefault(peer, 0L));

            // Use actual heartbeat-based health detection
            boolean alive = raftNode.isPeerAlive(peer);
            boolean partitioned = raftNode.isPartitioned(peer);
            peerInfo.put("isUp", alive && !partitioned);
            peerInfo.put("isPartitioned", partitioned);
            peerInfo.put("isAlive", alive);

            // Include last seen timestamp for the frontend
            Long lastSeen = raftNode.getPeerLastSeen().get(peer);
            peerInfo.put("lastSeen", lastSeen);

            nodes.add(peerInfo);
        }

        return nodes;
    }

    @GetMapping("/node/{id}/log")
    public Map<String, Object> getNodeLog(@PathVariable String id,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!id.equals(raftNode.getNodeId())) {
            result.put("error", "Can only view local node's log");
            return result;
        }

        RaftLog raftLog = raftNode.getRaftLog();
        long totalEntries = raftLog.getLastIndex();
        long startIdx = Math.max(1, totalEntries - (long)(page + 1) * size + 1);
        long endIdx = Math.min(totalEntries, startIdx + size - 1);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (long i = endIdx; i >= startIdx; i--) {
            RaftLog.Entry entry = raftLog.getEntry(i);
            if (entry != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("index", entry.index());
                e.put("term", entry.term());
                e.put("type", entry.type().name());
                e.put("key", entry.key());
                e.put("committed", entry.index() <= raftNode.getCommitIndex());
                e.put("applied", entry.index() <= raftNode.getLastApplied());
                entries.add(e);
            }
        }

        result.put("nodeId", id);
        result.put("entries", entries);
        result.put("totalEntries", totalEntries);
        result.put("page", page);
        result.put("size", size);
        result.put("commitIndex", raftNode.getCommitIndex());
        result.put("lastApplied", raftNode.getLastApplied());
        return result;
    }

    @GetMapping("/node/{id}/state")
    public Map<String, Object> getNodeState(@PathVariable String id) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("nodeId", raftNode.getNodeId());
        state.put("currentTerm", raftNode.getCurrentTerm());
        state.put("votedFor", raftNode.getVotedFor());
        state.put("commitIndex", raftNode.getCommitIndex());
        state.put("lastApplied", raftNode.getLastApplied());
        state.put("role", raftNode.getRole().name());
        state.put("leaderId", raftNode.getLeaderId());
        state.put("logLastIndex", raftNode.getRaftLog().getLastIndex());
        state.put("logLastTerm", raftNode.getRaftLog().getLastTerm());

        SnapshotManager sm = raftNode.getSnapshotManager();
        state.put("lastSnapshotIndex", sm.getLastSnapshotIndex());
        state.put("lastSnapshotTerm", sm.getLastSnapshotTerm());
        state.put("lastSnapshotSize", sm.getLastSnapshotSize());
        state.put("lastSnapshotTime", sm.getLastSnapshotTime());

        state.put("storageStats", storage.getStats());
        return state;
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("nodeId", raftNode.getNodeId());
        metrics.put("role", raftNode.getRole().name());
        metrics.put("term", raftNode.getCurrentTerm());
        metrics.put("commitIndex", raftNode.getCommitIndex());
        metrics.put("logSize", raftNode.getRaftLog().getLastIndex());
        metrics.put("storageStats", storage.getStats());
        metrics.put("uptime", raftNode.getUptime());
        return metrics;
    }

    @GetMapping("/events")
    public List<ClusterEvent> getEvents(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search) {
        if (search != null && !search.isEmpty()) {
            return eventBus.searchEvents(search, limit);
        }
        if (category != null && !category.isEmpty()) {
            try {
                return eventBus.getEventsByCategory(
                        ClusterEvent.Category.valueOf(category.toUpperCase()), limit);
            } catch (IllegalArgumentException e) {
                return eventBus.getRecentEvents(limit);
            }
        }
        return eventBus.getRecentEvents(limit);
    }
}
