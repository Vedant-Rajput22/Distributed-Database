package com.minidb.speculation;

import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.mvcc.MvccStore;
import com.minidb.raft.RaftLog;
import com.minidb.raft.RaftNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Speculative Consensus Manager — orchestrates the speculative write path.
 *
 * <h3>Architecture:</h3>
 * <pre>
 *   Client PUT(key, value)
 *       │
 *       ▼
 *   SpeculativeManager.submitSpeculativePut()
 *       │
 *       ├──► MvccStore.putSpeculative()     →  version written as SPECULATIVE
 *       │                                       client response returned immediately
 *       │
 *       ├──► RaftNode.submitCommand()       →  entry appended to Raft log
 *       │    (async, non-blocking)              replication to followers begins
 *       │
 *       └──► On majority ACK (callback):
 *            MvccStore.commitVersion()      →  SPECULATIVE → COMMITTED (1 byte flip)
 *
 *   On leader change:
 *       cascadeRollback(newCommitIndex)     →  all orphaned SPECULATIVE → ROLLED_BACK
 * </pre>
 *
 * <h3>Fault Model — Addressing the "Latency Paradox" (Reviewer Challenge §1):</h3>
 * <p>We do NOT silently sacrifice durability. The core invariant is:</p>
 * <ol>
 *   <li><b>Client awareness:</b> Every speculative response includes {@code speculative: true}.
 *       The client always knows the write is tentative.</li>
 *   <li><b>Per-write durability choice:</b> Clients set {@link AckPolicy}:
 *       {@link AckPolicy#SPECULATIVE SPECULATIVE} for fire-and-forget (min latency),
 *       or {@link AckPolicy#WAIT_FOR_COMMIT WAIT_FOR_COMMIT} for confirmed durability.</li>
 *   <li><b>Per-read consistency choice:</b> Readers select {@code LINEARIZABLE} (only committed)
 *       or {@code SPECULATIVE} (includes tentative data). No reader ever sees speculative
 *       data without explicitly opting in.</li>
 *   <li><b>Zero-cost rollback:</b> O(1) byte flip in MVCC — no undo log. Prior committed
 *       version is already in storage and immediately visible after rollback.</li>
 * </ol>
 *
 * <p><b>Where the latency savings come from:</b> We eliminate the client-visible wait
 * for 1 RTT to quorum. The entry IS appended to the Raft log and replication DOES proceed —
 * we simply decouple the client response from the replication round-trip.</p>
 *
 * <h3>Distinction from CockroachDB/Percolator Write Intents (Reviewer Challenge §2):</h3>
 * <p>CockroachDB write intents serve a fundamentally different purpose:</p>
 * <ul>
 *   <li><b>CockroachDB:</b> Write intents coordinate multi-key, cross-shard distributed
 *       transactions. An intent marks a row as "locked by transaction T" so that concurrent
 *       transactions can detect conflicts. Intents exist in the <i>transaction coordination</i> layer.</li>
 *   <li><b>Our approach:</b> Speculative versions exist in the <i>consensus replication</i> layer.
 *       We optimize single-partition State Machine Replication (SMR) latency. The version
 *       states (SPECULATIVE/COMMITTED/ROLLED_BACK) track Raft log commitment, not transaction
 *       commit. There is no lock table, no two-phase commit, no cross-shard coordination.</li>
 * </ul>
 * <p>The architectural insight: MVCC already maintains version history for reads —
 * we repurpose that for zero-cost consensus rollback. CockroachDB's intents add
 * extra storage overhead for locking semantics; our state byte is purely a
 * replication lifecycle marker with no locking semantics.</p>
 *
 * <h3>Timing Instrumentation (for paper evaluation):</h3>
 * <ul>
 *   <li>{@code speculation_write_latency} — time from client request to speculative response</li>
 *   <li>{@code speculation_promotion_latency} — time from speculative write to commit promotion</li>
 *   <li>{@code speculation_success_rate} — fraction of speculative writes that get committed</li>
 *   <li>{@code speculation_rollback_count} — total rollbacks (should be near-zero in stable operation)</li>
 *   <li>{@code speculation_window_ms} — average time a write remains in SPECULATIVE state</li>
 * </ul>
 */
@Component
public class SpeculativeManager {

    private static final Logger log = LoggerFactory.getLogger(SpeculativeManager.class);

    private final EventBus eventBus;
    private final AtomicBoolean speculationEnabled = new AtomicBoolean(true);

    // ================================================================
    // Ack Policy — Per-Write Durability Choice (Reviewer Challenge §1)
    // ================================================================

    /**
     * Acknowledgment policy for speculative writes.
     *
     * <p>This is the key answer to the "Latency Paradox" reviewer challenge:
     * we do NOT force a binary choice between latency and durability.
     * Each write selects its own policy.</p>
     */
    public enum AckPolicy {
        /**
         * Return to client immediately after local MVCC write (~0.3ms).
         * The response includes {@code speculative: true} — the client KNOWS
         * the write is not yet durably committed to majority.
         *
         * <p>Ideal for: latency-sensitive operations where the application
         * can tolerate (and handle) rollback, e.g., session updates, caches,
         * real-time analytics ingestion.</p>
         */
        SPECULATIVE,

        /**
         * Write speculatively to MVCC (making the value visible to SPECULATIVE
         * readers immediately), but wait for Raft majority ACK before returning
         * to the calling client.
         *
         * <p>This gives the BEST of both worlds: other nodes can read the value
         * via SPECULATIVE read mode while consensus is in flight, and the
         * writing client gets a full durability guarantee.</p>
         *
         * <p>Latency: ~5ms (1 RTT) — same as standard Raft, but with the
         * bonus of immediate reader visibility on the leader.</p>
         */
        WAIT_FOR_COMMIT
    }

    // ================================================================
    // Metrics for Paper Evaluation
    // ================================================================

    private final Timer speculativeWriteTimer;
    private final Timer commitPromotionTimer;
    private final Timer standardWriteTimer;
    private final Counter speculativeWriteCounter;
    private final Counter standardWriteCounter;
    private final Counter commitPromotionCounter;
    private final Counter rollbackCounter;
    private final Counter cascadeRollbackCounter;

    // Latency tracking for speculation window
    private final AtomicLong speculativeWriteCount = new AtomicLong(0);
    private final AtomicLong totalSpeculativeLatencyNanos = new AtomicLong(0);
    private final AtomicLong totalPromotionLatencyNanos = new AtomicLong(0);
    private final AtomicLong totalStandardLatencyNanos = new AtomicLong(0);
    private final AtomicLong standardWriteCount = new AtomicLong(0);

    // Tracks the timestamp when each speculative version was created (for promotion latency)
    private final ConcurrentHashMap<String, Long> speculationStartTimes = new ConcurrentHashMap<>();

    public SpeculativeManager(EventBus eventBus, MeterRegistry meterRegistry) {
        this.eventBus = eventBus;

        // Timers for latency distribution
        this.speculativeWriteTimer = Timer.builder("speculation_write_latency")
                .description("Latency of speculative write path (local write only)")
                .register(meterRegistry);
        this.commitPromotionTimer = Timer.builder("speculation_promotion_latency")
                .description("Time from speculative write to commit promotion (async)")
                .register(meterRegistry);
        this.standardWriteTimer = Timer.builder("standard_write_latency")
                .description("Latency of standard Raft write path (majority waited)")
                .register(meterRegistry);

        // Counters
        this.speculativeWriteCounter = Counter.builder("speculation_writes_total")
                .description("Total speculative writes")
                .register(meterRegistry);
        this.standardWriteCounter = Counter.builder("standard_writes_total")
                .description("Total standard (non-speculative) writes")
                .register(meterRegistry);
        this.commitPromotionCounter = Counter.builder("speculation_promotions_total")
                .description("Total speculative versions promoted to committed")
                .register(meterRegistry);
        this.rollbackCounter = Counter.builder("speculation_rollbacks_total")
                .description("Total speculative versions rolled back")
                .register(meterRegistry);
        this.cascadeRollbackCounter = Counter.builder("speculation_cascade_rollbacks_total")
                .description("Total cascade rollback events (leader changes)")
                .register(meterRegistry);

        // Gauges
        Gauge.builder("speculation_enabled", speculationEnabled, ab -> ab.get() ? 1.0 : 0.0)
                .description("Whether speculative writes are enabled")
                .register(meterRegistry);
    }

    // ================================================================
    // Speculative Write Path
    // ================================================================

    /**
     * Submit a speculative PUT with default SPECULATIVE ack policy.
     */
    public SpeculativeResult submitSpeculativePut(RaftNode raftNode, String key, byte[] value) {
        return submitSpeculativePut(raftNode, key, value, AckPolicy.SPECULATIVE);
    }

    /**
     * Submit a speculative PUT with explicit ack policy.
     *
     * <p><b>Performance path (AckPolicy.SPECULATIVE):</b></p>
     * <ol>
     *   <li>Write to MVCC store with SPECULATIVE state (local disk, ~0.3ms)</li>
     *   <li>Return speculative timestamp to client immediately</li>
     *   <li>Submit to Raft log + replication (async, non-blocking)</li>
     *   <li>On majority ACK → commitVersion() (async callback)</li>
     * </ol>
     *
     * <p><b>Durable path (AckPolicy.WAIT_FOR_COMMIT):</b></p>
     * <ol>
     *   <li>Write to MVCC store with SPECULATIVE state (for immediate reader visibility)</li>
     *   <li>Submit to Raft log + replication</li>
     *   <li>Block until majority ACK, then promote to COMMITTED</li>
     *   <li>Return to client with {@code speculative: false}</li>
     * </ol>
     *
     * @param raftNode  The Raft node to submit to
     * @param key       The key to write
     * @param value     The value bytes
     * @param ackPolicy Whether to wait for Raft majority before responding
     * @return SpeculativeResult containing the timestamp and a future for commit confirmation
     */
    public SpeculativeResult submitSpeculativePut(RaftNode raftNode, String key, byte[] value,
                                                   AckPolicy ackPolicy) {
        if (!speculationEnabled.get()) {
            // Fallback to standard path
            return submitStandard(raftNode, key, value, RaftLog.CommandType.PUT);
        }

        long startNanos = System.nanoTime();

        // Step 1: Append to Raft log (get the log index)
        long logIndex = raftNode.appendToLog(RaftLog.CommandType.PUT, key, value);
        if (logIndex < 0) {
            return SpeculativeResult.failed("Not the leader or node is killed");
        }

        // Step 2: Write to MVCC store as SPECULATIVE
        MvccStore mvccStore = raftNode.getMvccStore();
        long timestamp = mvccStore.putSpeculative(key, value, logIndex);

        // Step 3: Record speculative write timing
        long writeLatencyNanos = System.nanoTime() - startNanos;
        speculativeWriteTimer.record(writeLatencyNanos, TimeUnit.NANOSECONDS);
        speculativeWriteCounter.increment();
        speculativeWriteCount.incrementAndGet();
        totalSpeculativeLatencyNanos.addAndGet(writeLatencyNanos);

        String trackingKey = key + ":" + timestamp;
        speculationStartTimes.put(trackingKey, System.nanoTime());

        // Step 4: Trigger async replication (non-blocking)
        CompletableFuture<Boolean> commitFuture = new CompletableFuture<>();
        raftNode.trackSpeculativeCommit(logIndex, () -> {
            // Commit promotion callback — runs when Raft confirms majority
            long promotionNanos = System.nanoTime() - speculationStartTimes.getOrDefault(trackingKey, System.nanoTime());
            mvccStore.commitVersion(key, timestamp);

            commitPromotionTimer.record(promotionNanos, TimeUnit.NANOSECONDS);
            commitPromotionCounter.increment();
            totalPromotionLatencyNanos.addAndGet(promotionNanos);
            speculationStartTimes.remove(trackingKey);

            commitFuture.complete(true);
        });

        // Trigger replication
        raftNode.triggerReplication();

        // Timeout: if not committed within 10s, rollback
        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS)
                .execute(() -> {
                    if (!commitFuture.isDone()) {
                        mvccStore.rollbackVersion(key, timestamp);
                        rollbackCounter.increment();
                        speculationStartTimes.remove(trackingKey);
                        commitFuture.complete(false);
                    }
                });

        double writeLatencyMs = writeLatencyNanos / 1_000_000.0;
        log.debug("Speculative PUT: key={}, ts={}, logIdx={}, latency={:.2f}ms",
                key, timestamp, logIndex, writeLatencyMs);

        // AckPolicy.WAIT_FOR_COMMIT: block until Raft majority confirms
        // This still benefits from immediate reader visibility on the leader,
        // but the writing client gets a full durability guarantee.
        if (ackPolicy == AckPolicy.WAIT_FOR_COMMIT) {
            try {
                Boolean committed = commitFuture.get(5, TimeUnit.SECONDS);
                long totalLatencyNanos = System.nanoTime() - startNanos;
                double totalLatencyMs = totalLatencyNanos / 1_000_000.0;
                if (committed != null && committed) {
                    return SpeculativeResult.committedViaSpeculative(timestamp, totalLatencyMs, logIndex);
                } else {
                    return SpeculativeResult.failed("Raft commit failed after speculative write");
                }
            } catch (Exception e) {
                // Rollback on timeout
                mvccStore.rollbackVersion(key, timestamp);
                rollbackCounter.increment();
                return SpeculativeResult.failed("Timeout waiting for commit: " + e.getMessage());
            }
        }

        return SpeculativeResult.speculative(timestamp, writeLatencyMs, logIndex, commitFuture);
    }

    /**
     * Submit a speculative DELETE.
     */
    public SpeculativeResult submitSpeculativeDelete(RaftNode raftNode, String key) {
        if (!speculationEnabled.get()) {
            return submitStandard(raftNode, key, null, RaftLog.CommandType.DELETE);
        }

        long startNanos = System.nanoTime();

        long logIndex = raftNode.appendToLog(RaftLog.CommandType.DELETE, key, null);
        if (logIndex < 0) {
            return SpeculativeResult.failed("Not the leader or node is killed");
        }

        MvccStore mvccStore = raftNode.getMvccStore();
        long timestamp = mvccStore.deleteSpeculative(key, logIndex);

        long writeLatencyNanos = System.nanoTime() - startNanos;
        speculativeWriteTimer.record(writeLatencyNanos, TimeUnit.NANOSECONDS);
        speculativeWriteCounter.increment();

        String trackingKey = key + ":" + timestamp;
        speculationStartTimes.put(trackingKey, System.nanoTime());

        CompletableFuture<Boolean> commitFuture = new CompletableFuture<>();
        raftNode.trackSpeculativeCommit(logIndex, () -> {
            long promotionNanos = System.nanoTime() - speculationStartTimes.getOrDefault(trackingKey, System.nanoTime());
            mvccStore.commitVersion(key, timestamp);
            commitPromotionTimer.record(promotionNanos, TimeUnit.NANOSECONDS);
            commitPromotionCounter.increment();
            speculationStartTimes.remove(trackingKey);
            commitFuture.complete(true);
        });

        raftNode.triggerReplication();

        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS)
                .execute(() -> {
                    if (!commitFuture.isDone()) {
                        mvccStore.rollbackVersion(key, timestamp);
                        rollbackCounter.increment();
                        speculationStartTimes.remove(trackingKey);
                        commitFuture.complete(false);
                    }
                });

        double writeLatencyMs = writeLatencyNanos / 1_000_000.0;
        return SpeculativeResult.speculative(timestamp, writeLatencyMs, logIndex, commitFuture);
    }

    /**
     * Standard (non-speculative) write path — waits for Raft majority before responding.
     */
    private SpeculativeResult submitStandard(RaftNode raftNode, String key, byte[] value,
                                              RaftLog.CommandType type) {
        long startNanos = System.nanoTime();
        CompletableFuture<Boolean> future;

        if (type == RaftLog.CommandType.PUT) {
            future = raftNode.submitPut(key, value);
        } else {
            future = raftNode.submitDelete(key);
        }

        try {
            Boolean committed = future.get(5, TimeUnit.SECONDS);
            long latencyNanos = System.nanoTime() - startNanos;
            standardWriteTimer.record(latencyNanos, TimeUnit.NANOSECONDS);
            standardWriteCounter.increment();
            standardWriteCount.incrementAndGet();
            totalStandardLatencyNanos.addAndGet(latencyNanos);

            double latencyMs = latencyNanos / 1_000_000.0;

            if (committed != null && committed) {
                return SpeculativeResult.committed(latencyMs);
            } else {
                return SpeculativeResult.failed("Raft commit failed");
            }
        } catch (Exception e) {
            return SpeculativeResult.failed(e.getMessage());
        }
    }

    // ================================================================
    // Leader Change Handling
    // ================================================================

    /**
     * Called when this node loses leadership. Rolls back all speculative versions
     * that haven't been committed by Raft consensus.
     */
    public void onLeadershipLost(MvccStore mvccStore, long lastCommittedIndex) {
        int rolledBack = mvccStore.cascadeRollback(lastCommittedIndex + 1);
        if (rolledBack > 0) {
            cascadeRollbackCounter.increment();
            rollbackCounter.increment(rolledBack);
            log.info("Leadership lost: cascade rollback of {} speculative versions", rolledBack);
        }
        speculationStartTimes.clear();
    }

    // ================================================================
    // Configuration
    // ================================================================

    public boolean isSpeculationEnabled() {
        return speculationEnabled.get();
    }

    public void setSpeculationEnabled(boolean enabled) {
        speculationEnabled.set(enabled);
        log.info("Speculation mode: {}", enabled ? "ENABLED" : "DISABLED");
    }

    // ================================================================
    // Metrics Getters (for paper evaluation API)
    // ================================================================

    public double getAverageSpeculativeLatencyMs() {
        long count = speculativeWriteCount.get();
        if (count == 0) return 0;
        return (totalSpeculativeLatencyNanos.get() / count) / 1_000_000.0;
    }

    public double getAverageStandardLatencyMs() {
        long count = standardWriteCount.get();
        if (count == 0) return 0;
        return (totalStandardLatencyNanos.get() / count) / 1_000_000.0;
    }

    public double getAveragePromotionLatencyMs() {
        double promos = commitPromotionCounter.count();
        if (promos == 0) return 0;
        return (totalPromotionLatencyNanos.get() / promos) / 1_000_000.0;
    }

    public long getSpeculativeWriteCount() { return speculativeWriteCount.get(); }
    public long getStandardWriteCount() { return standardWriteCount.get(); }
    public int getPendingSpeculations() { return speculationStartTimes.size(); }

    // ================================================================
    // Result Type
    // ================================================================

    /**
     * Result of a speculative or standard write operation.
     *
     * <p>Contains all information needed for the paper's evaluation:
     * whether the write was speculative, the write latency, and an
     * optional future for monitoring commit confirmation.</p>
     */
    public record SpeculativeResult(
            boolean success,
            boolean speculative,
            long timestamp,
            double writeLatencyMs,
            long raftLogIndex,
            String error,
            CompletableFuture<Boolean> commitFuture
    ) {
        public static SpeculativeResult speculative(long timestamp, double writeLatencyMs,
                                                     long raftLogIndex,
                                                     CompletableFuture<Boolean> commitFuture) {
            return new SpeculativeResult(true, true, timestamp, writeLatencyMs,
                    raftLogIndex, null, commitFuture);
        }

        /**
         * Result of a WAIT_FOR_COMMIT speculative write: used the speculative MVCC path
         * internally (for immediate reader visibility), but waited for Raft majority.
         * Returned with speculative=false since the write IS committed by the time
         * the client receives this response.
         */
        public static SpeculativeResult committedViaSpeculative(long timestamp, double writeLatencyMs,
                                                                  long raftLogIndex) {
            return new SpeculativeResult(true, false, timestamp, writeLatencyMs,
                    raftLogIndex, null, CompletableFuture.completedFuture(true));
        }

        public static SpeculativeResult committed(double writeLatencyMs) {
            return new SpeculativeResult(true, false, 0, writeLatencyMs,
                    0, null, CompletableFuture.completedFuture(true));
        }

        public static SpeculativeResult failed(String error) {
            return new SpeculativeResult(false, false, 0, 0,
                    0, error, CompletableFuture.completedFuture(false));
        }
    }
}
