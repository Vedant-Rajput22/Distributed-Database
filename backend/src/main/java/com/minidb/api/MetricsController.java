package com.minidb.api;

import com.minidb.mvcc.GarbageCollector;
import com.minidb.raft.RaftNode;
import com.minidb.raft.SnapshotManager;
import com.minidb.storage.StorageEngine;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for metrics and observability data.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final RaftNode raftNode;
    private final StorageEngine storage;
    private final GarbageCollector gc;

    public MetricsController(RaftNode raftNode, StorageEngine storage, GarbageCollector gc) {
        this.raftNode = raftNode;
        this.storage = storage;
        this.gc = gc;
    }

    @GetMapping("/overview")
    public Map<String, Object> getOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("nodeId", raftNode.getNodeId());
        overview.put("role", raftNode.getRole().name());
        overview.put("term", raftNode.getCurrentTerm());
        overview.put("commitIndex", raftNode.getCommitIndex());
        overview.put("lastApplied", raftNode.getLastApplied());
        overview.put("logSize", raftNode.getRaftLog().getLastIndex());
        overview.put("uptime", raftNode.getUptime());
        overview.put("killed", raftNode.isKilled());
        return overview;
    }

    @GetMapping("/storage")
    public Map<String, String> getStorageStats() {
        return storage.getStats();
    }

    @GetMapping("/snapshot")
    public Map<String, Object> getSnapshotInfo() {
        SnapshotManager sm = raftNode.getSnapshotManager();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("lastSnapshotIndex", sm.getLastSnapshotIndex());
        info.put("lastSnapshotTerm", sm.getLastSnapshotTerm());
        info.put("lastSnapshotSize", sm.getLastSnapshotSize());
        info.put("lastSnapshotTime", sm.getLastSnapshotTime());
        return info;
    }

    @GetMapping("/gc")
    public Map<String, Object> getGcInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("totalPurged", gc.getTotalPurged());
        return info;
    }
}
