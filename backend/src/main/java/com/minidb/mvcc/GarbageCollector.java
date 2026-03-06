package com.minidb.mvcc;

import com.minidb.config.RaftConfig;
import com.minidb.raft.RaftNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Background garbage collector that periodically purges old MVCC versions.
 * Uses RaftNode's MvccStore (lazy to avoid circular dependency).
 */
@Component
public class GarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(GarbageCollector.class);

    private final RaftNode raftNode;
    private final RaftConfig raftConfig;
    private final AtomicLong totalPurged = new AtomicLong(0);

    public GarbageCollector(@Lazy RaftNode raftNode, RaftConfig raftConfig) {
        this.raftNode = raftNode;
        this.raftConfig = raftConfig;
    }

    @Scheduled(fixedDelayString = "${minidb.mvcc.gc-interval-ms:60000}")
    public void runGc() {
        try {
            MvccStore mvccStore = raftNode.getMvccStore();
            if (mvccStore == null) return;
            int purged = mvccStore.garbageCollect(raftConfig.getMvccRetentionMs());
            totalPurged.addAndGet(purged);
            if (purged > 0) {
                log.info("GC cycle complete: purged {} versions, total purged: {}",
                        purged, totalPurged.get());
            }
        } catch (Exception e) {
            log.error("GC cycle failed", e);
        }
    }

    public long getTotalPurged() {
        return totalPurged.get();
    }
}
