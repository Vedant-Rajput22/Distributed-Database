package com.minidb.mvcc;

import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.storage.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Speculative MVCC Store — the core contribution of the Speculative MVCC Consensus system.
 *
 * <h3>Key Insight:</h3>
 * <p>Standard Raft has a latency floor: every write requires at least 1 network RTT
 * (leader → majority ACK → client response). Speculative execution can eliminate this,
 * but prior approaches (Zyzzyva, SpecPaxos) require expensive undo logs for rollback.</p>
 *
 * <p><b>Our observation:</b> MVCC already stores every version of every key. Rolling back
 * a speculative write is simply marking it as ROLLED_BACK — an O(1) byte flip. The
 * prior committed version is already in storage and immediately visible.</p>
 *
 * <h3>Three-State Version Lifecycle:</h3>
 * <pre>
 *   put(key, value, SPECULATIVE)  →  version written immediately, client notified
 *                                      │
 *         ┌────────────────────────────┤
 *         │                            │
 *   commitVersion(key, ts)        rollbackVersion(key, ts)
 *   (Raft majority confirms)     (leader change / timeout)
 *         │                            │
 *    COMMITTED (visible to all)   ROLLED_BACK (invisible, GC'd)
 * </pre>
 *
 * <h3>Visibility Rules:</h3>
 * <ul>
 *   <li>LINEARIZABLE reads: skip SPECULATIVE and ROLLED_BACK versions</li>
 *   <li>SPECULATIVE reads: include SPECULATIVE, skip ROLLED_BACK versions</li>
 * </ul>
 */
public class MvccStore {

    private static final Logger log = LoggerFactory.getLogger(MvccStore.class);
    private static final byte[] TOMBSTONE_MARKER = "__TOMBSTONE__".getBytes(StandardCharsets.UTF_8);

    private final StorageEngine storage;
    private final EventBus eventBus;
    private final AtomicLong timestampGenerator = new AtomicLong(System.currentTimeMillis());

    // ================================================================
    // Speculation Tracking
    // ================================================================

    /**
     * Tracks speculative versions: mvccKey → raftLogIndex.
     * Used for cascade rollback when leadership changes.
     */
    private final ConcurrentHashMap<String, SpeculativeEntry> speculativeVersions = new ConcurrentHashMap<>();

    /**
     * Speculation metrics — tracked for paper evaluation.
     */
    private final AtomicLong totalSpeculativeWrites = new AtomicLong(0);
    private final AtomicLong totalCommitPromotions = new AtomicLong(0);
    private final AtomicLong totalRollbacks = new AtomicLong(0);
    private final AtomicLong totalStandardWrites = new AtomicLong(0);

    /**
     * Tracks a speculative version for commit promotion or rollback.
     */
    public record SpeculativeEntry(String userKey, long timestamp, long raftLogIndex, long createdAt) {}

    public MvccStore(StorageEngine storage, EventBus eventBus) {
        this.storage = storage;
        this.eventBus = eventBus;
    }

    // ================================================================
    // Standard Write Path (backward compatible, used by applyCommittedEntries)
    // ================================================================

    /**
     * Put a key-value pair with an auto-generated timestamp (committed immediately).
     * This is the standard Raft path — used when applying committed log entries.
     * @return the timestamp used
     */
    public long put(String key, byte[] value) {
        long ts = nextTimestamp();
        return put(key, value, ts);
    }

    /**
     * Put a key-value pair at a specific timestamp (committed immediately).
     * Used by the standard Raft apply path (applyCommittedEntries).
     */
    public long put(String key, byte[] value, long timestamp) {
        byte[] mvccKey = KeyEncoding.encode(key, timestamp);
        byte[] encodedValue = KeyEncoding.encodeValue(value, VersionState.COMMITTED);
        storage.put(mvccKey, encodedValue);
        totalStandardWrites.incrementAndGet();
        log.debug("MVCC PUT (committed): key={}, ts={}, valueLen={}", key, timestamp, value.length);

        if (eventBus != null) {
            eventBus.publish(ClusterEvent.mvccVersionCreated(key, timestamp, value.length));
        }
        return timestamp;
    }

    // ================================================================
    // Speculative Write Path (Novel Contribution)
    // ================================================================

    /**
     * Write a speculative version — applied BEFORE Raft consensus completes.
     *
     * <p>This is the key performance optimization. The leader writes the value
     * immediately with SPECULATIVE state and responds to the client. Raft
     * replication proceeds asynchronously. Once majority ACK arrives,
     * {@link #commitVersion(String, long)} promotes the version to COMMITTED.</p>
     *
     * <p><b>Latency improvement:</b> Client sees ~0.5ms (local write) instead of
     * ~5-10ms (1 RTT for majority ACK).</p>
     *
     * @param key           User key
     * @param value         Value bytes
     * @param raftLogIndex  The Raft log index for this entry (for rollback tracking)
     * @return the timestamp used for this speculative version
     */
    public long putSpeculative(String key, byte[] value, long raftLogIndex) {
        long ts = nextTimestamp();
        byte[] mvccKey = KeyEncoding.encode(key, ts);
        byte[] encodedValue = KeyEncoding.encodeValue(value, VersionState.SPECULATIVE);
        storage.put(mvccKey, encodedValue);

        // Track for commit promotion or rollback
        String mvccKeyStr = key + ":" + ts;
        speculativeVersions.put(mvccKeyStr, new SpeculativeEntry(key, ts, raftLogIndex, System.currentTimeMillis()));
        totalSpeculativeWrites.incrementAndGet();

        log.debug("MVCC PUT (speculative): key={}, ts={}, raftIdx={}", key, ts, raftLogIndex);

        if (eventBus != null) {
            eventBus.publish(ClusterEvent.speculativeVersionCreated(key, ts, value.length, raftLogIndex));
        }
        return ts;
    }

    /**
     * Write a speculative delete (tombstone) — applied BEFORE Raft consensus.
     */
    public long deleteSpeculative(String key, long raftLogIndex) {
        long ts = nextTimestamp();
        byte[] mvccKey = KeyEncoding.encode(key, ts);
        byte[] encodedValue = KeyEncoding.encodeValue(TOMBSTONE_MARKER, VersionState.SPECULATIVE);
        storage.put(mvccKey, encodedValue);

        String mvccKeyStr = key + ":" + ts;
        speculativeVersions.put(mvccKeyStr, new SpeculativeEntry(key, ts, raftLogIndex, System.currentTimeMillis()));
        totalSpeculativeWrites.incrementAndGet();

        log.debug("MVCC DELETE (speculative tombstone): key={}, ts={}, raftIdx={}", key, ts, raftLogIndex);
        return ts;
    }

    /**
     * Promote a speculative version to COMMITTED after Raft majority ACK.
     *
     * <p><b>Cost:</b> O(1) — reads the existing value, flips state byte, writes back.
     * Compared to undo-log rollback which is O(n) in entry size.</p>
     *
     * @return true if the version was found and promoted
     */
    public boolean commitVersion(String key, long timestamp) {
        byte[] mvccKey = KeyEncoding.encode(key, timestamp);
        byte[] encodedValue = storage.get(mvccKey);
        if (encodedValue == null) return false;

        VersionState currentState = KeyEncoding.decodeVersionState(encodedValue);
        if (currentState == VersionState.COMMITTED) return true; // already committed
        if (currentState == VersionState.ROLLED_BACK) return false; // already rolled back

        // Flip state byte: SPECULATIVE → COMMITTED
        byte[] committed = KeyEncoding.changeState(encodedValue, VersionState.COMMITTED);
        storage.put(mvccKey, committed);

        // Remove from speculative tracking
        String mvccKeyStr = key + ":" + timestamp;
        speculativeVersions.remove(mvccKeyStr);
        totalCommitPromotions.incrementAndGet();

        log.debug("MVCC COMMIT promotion: key={}, ts={}", key, timestamp);

        if (eventBus != null) {
            eventBus.publish(ClusterEvent.speculativeCommitted(key, timestamp));
        }
        return true;
    }

    /**
     * Roll back a speculative version — O(1) state byte flip.
     *
     * <p>This is what makes speculative MVCC consensus practical. In prior
     * speculative execution systems (Zyzzyva, SpecPaxos), rollback requires:
     * <ul>
     *   <li>Maintaining a separate undo log (O(n) space)</li>
     *   <li>Replaying the undo log in reverse (O(n) time)</li>
     *   <li>Coordinating across replicas for undo ordering</li>
     * </ul>
     *
     * <p>With MVCC, rollback is simply flipping one byte. The prior committed
     * version is already stored and becomes immediately visible.</p>
     *
     * @return true if the version was found and rolled back
     */
    public boolean rollbackVersion(String key, long timestamp) {
        byte[] mvccKey = KeyEncoding.encode(key, timestamp);
        byte[] encodedValue = storage.get(mvccKey);
        if (encodedValue == null) return false;

        VersionState currentState = KeyEncoding.decodeVersionState(encodedValue);
        if (currentState != VersionState.SPECULATIVE) return false; // only rollback speculative

        // Flip state byte: SPECULATIVE → ROLLED_BACK
        byte[] rolledBack = KeyEncoding.changeState(encodedValue, VersionState.ROLLED_BACK);
        storage.put(mvccKey, rolledBack);

        String mvccKeyStr = key + ":" + timestamp;
        speculativeVersions.remove(mvccKeyStr);
        totalRollbacks.incrementAndGet();

        log.info("MVCC ROLLBACK: key={}, ts={} (zero-cost: prior version unmasked)", key, timestamp);

        if (eventBus != null) {
            eventBus.publish(ClusterEvent.speculativeRolledBack(key, timestamp));
        }
        return true;
    }

    /**
     * Cascade rollback: roll back ALL speculative versions with raftLogIndex > threshold.
     *
     * <p>Called when leadership changes. The new leader's log may not contain
     * entries that were speculatively applied by the old leader. All orphaned
     * speculative versions must be invalidated.</p>
     *
     * @param fromRaftIndex  Roll back speculative versions for entries at or after this index
     * @return number of versions rolled back
     */
    public int cascadeRollback(long fromRaftIndex) {
        int rolledBackCount = 0;
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, SpeculativeEntry> entry : speculativeVersions.entrySet()) {
            SpeculativeEntry spec = entry.getValue();
            if (spec.raftLogIndex() >= fromRaftIndex) {
                rollbackVersion(spec.userKey(), spec.timestamp());
                toRemove.add(entry.getKey());
                rolledBackCount++;
            }
        }

        toRemove.forEach(speculativeVersions::remove);

        if (rolledBackCount > 0) {
            log.info("CASCADE ROLLBACK: {} speculative versions rolled back (fromIdx={})",
                    rolledBackCount, fromRaftIndex);
            if (eventBus != null) {
                eventBus.publish(ClusterEvent.cascadeRollback(rolledBackCount, fromRaftIndex));
            }
        }
        return rolledBackCount;
    }

    // ================================================================
    // Read Path with Visibility Filtering
    // ================================================================

    /**
     * Get the latest value for a key using LINEARIZABLE mode (default).
     * Only committed versions are visible.
     */
    public MvccResult get(String key) {
        return get(key, Long.MAX_VALUE, ReadMode.LINEARIZABLE);
    }

    /**
     * Snapshot read (backward compatible): get the latest committed value at timestamp.
     */
    public MvccResult get(String key, long readTimestamp) {
        return get(key, readTimestamp, ReadMode.LINEARIZABLE);
    }

    /**
     * Get the latest value for a key with explicit read mode.
     * Convenience overload for speculative reads at current time.
     */
    public MvccResult get(String key, ReadMode readMode) {
        return get(key, Long.MAX_VALUE, readMode);
    }

    /**
     * Read with explicit read mode — the key API for speculative reads.
     *
     * <p>In SPECULATIVE mode, the response includes whether the returned
     * value is speculative, so clients can display a "pending" indicator
     * and optionally subscribe for commit/rollback notifications.</p>
     */
    public MvccResult get(String key, long readTimestamp, ReadMode readMode) {
        byte[] prefix = KeyEncoding.encodePrefix(key);
        byte[] upperBound = KeyEncoding.encodePrefixUpperBound(key);

        List<Map.Entry<byte[], byte[]>> entries = storage.scan(prefix, upperBound, 0);

        for (Map.Entry<byte[], byte[]> entry : entries) {
            long entryTs = KeyEncoding.decodeTimestamp(entry.getKey());
            if (entryTs > readTimestamp) continue;

            byte[] rawValue = entry.getValue();
            VersionState state = KeyEncoding.decodeVersionState(rawValue);
            byte[] userValue = KeyEncoding.decodeValue(rawValue);

            // Visibility check based on read mode
            if (!state.isVisibleUnder(readMode)) continue;

            // Check for tombstone
            if (isTombstoneValue(userValue)) {
                return MvccResult.notFound(key);
            }

            boolean isSpeculative = (state == VersionState.SPECULATIVE);
            return MvccResult.found(key, userValue, entryTs, isSpeculative);
        }
        return MvccResult.notFound(key);
    }

    /**
     * Delete a key by writing a committed tombstone.
     * @return the timestamp used
     */
    public long delete(String key) {
        long ts = nextTimestamp();
        return delete(key, ts);
    }

    /**
     * Delete a key at a specific timestamp (committed).
     */
    public long delete(String key, long timestamp) {
        byte[] mvccKey = KeyEncoding.encode(key, timestamp);
        byte[] encodedValue = KeyEncoding.encodeValue(TOMBSTONE_MARKER, VersionState.COMMITTED);
        storage.put(mvccKey, encodedValue);
        log.debug("MVCC DELETE (tombstone): key={}, ts={}", key, timestamp);
        return timestamp;
    }

    /**
     * Scan a range of keys, returning the latest version of each key.
     * Uses LINEARIZABLE mode by default.
     */
    public List<MvccResult> scan(String startKey, String endKey, int limit, long readTimestamp) {
        return scan(startKey, endKey, limit, readTimestamp, ReadMode.LINEARIZABLE);
    }

    /**
     * Scan with explicit read mode.
     */
    public List<MvccResult> scan(String startKey, String endKey, int limit,
                                  long readTimestamp, ReadMode readMode) {
        if (readTimestamp <= 0) readTimestamp = Long.MAX_VALUE;

        byte[] scanStart = KeyEncoding.encodePrefix(startKey);
        byte[] scanEnd = endKey != null ? KeyEncoding.encodePrefixUpperBound(endKey) : null;

        List<Map.Entry<byte[], byte[]>> entries = storage.scan(scanStart, scanEnd, 0);
        Map<String, MvccResult> latestVersions = new LinkedHashMap<>();

        for (Map.Entry<byte[], byte[]> entry : entries) {
            String userKey;
            long entryTs;
            try {
                userKey = KeyEncoding.decodeUserKey(entry.getKey());
                entryTs = KeyEncoding.decodeTimestamp(entry.getKey());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (userKey.startsWith("__")) continue;
            if (entryTs > readTimestamp) continue;
            if (latestVersions.containsKey(userKey)) continue;

            byte[] rawValue = entry.getValue();
            VersionState state = KeyEncoding.decodeVersionState(rawValue);
            byte[] userValue = KeyEncoding.decodeValue(rawValue);

            // Visibility check
            if (!state.isVisibleUnder(readMode)) continue;

            if (isTombstoneValue(userValue)) {
                latestVersions.put(userKey, null);
            } else {
                boolean isSpeculative = (state == VersionState.SPECULATIVE);
                latestVersions.put(userKey, MvccResult.found(userKey, userValue, entryTs, isSpeculative));
            }
        }

        List<MvccResult> results = new ArrayList<>();
        for (MvccResult r : latestVersions.values()) {
            if (r != null) {
                results.add(r);
                if (limit > 0 && results.size() >= limit) break;
            }
        }
        return results;
    }

    /**
     * Get all versions of a key (for MVCC version browser + speculation visualization).
     * Shows version state for each entry.
     */
    public List<MvccResult> getVersionHistory(String key) {
        byte[] prefix = KeyEncoding.encodePrefix(key);
        byte[] upperBound = KeyEncoding.encodePrefixUpperBound(key);

        List<Map.Entry<byte[], byte[]>> entries = storage.scan(prefix, upperBound, 0);
        List<MvccResult> versions = new ArrayList<>();

        for (Map.Entry<byte[], byte[]> entry : entries) {
            long entryTs = KeyEncoding.decodeTimestamp(entry.getKey());
            byte[] rawValue = entry.getValue();
            VersionState state = KeyEncoding.decodeVersionState(rawValue);
            byte[] userValue = KeyEncoding.decodeValue(rawValue);

            if (isTombstoneValue(userValue)) {
                versions.add(MvccResult.tombstone(key, entryTs, state));
            } else {
                boolean isSpeculative = (state == VersionState.SPECULATIVE);
                MvccResult result = MvccResult.found(key, userValue, entryTs, isSpeculative);
                result.setVersionState(state);
                versions.add(result);
            }
        }
        return versions;
    }

    // ================================================================
    // Garbage Collection (extended for speculation)
    // ================================================================

    /**
     * Garbage collect old versions. Extended to also purge ROLLED_BACK versions
     * immediately (no retention needed — they are already invisible).
     *
     * @return number of versions purged
     */
    public int garbageCollect(long retentionMs) {
        long cutoffTimestamp = System.currentTimeMillis() - retentionMs;
        int purged = 0;

        byte[] startKey = new byte[]{0};
        byte[] endKey = new byte[]{(byte) 0xFF};
        List<Map.Entry<byte[], byte[]>> entries = storage.scan(startKey, endKey, 0);

        Map<String, List<byte[]>> keyVersions = new LinkedHashMap<>();
        for (Map.Entry<byte[], byte[]> entry : entries) {
            try {
                String userKey = KeyEncoding.decodeUserKey(entry.getKey());
                keyVersions.computeIfAbsent(userKey, k -> new ArrayList<>()).add(entry.getKey());
            } catch (Exception e) {
                // Skip non-MVCC keys
            }
        }

        for (Map.Entry<String, List<byte[]>> kv : keyVersions.entrySet()) {
            List<byte[]> versions = kv.getValue();
            if (versions.size() <= 1) continue;

            for (int i = 1; i < versions.size(); i++) {
                try {
                    byte[] mvccKeyBytes = versions.get(i);
                    long ts = KeyEncoding.decodeTimestamp(mvccKeyBytes);
                    byte[] rawValue = storage.get(mvccKeyBytes);

                    if (rawValue != null) {
                        VersionState state = KeyEncoding.decodeVersionState(rawValue);

                        // Always purge ROLLED_BACK versions immediately
                        if (state == VersionState.ROLLED_BACK) {
                            storage.delete(mvccKeyBytes);
                            purged++;
                            continue;
                        }
                    }

                    // Standard retention-based cleanup for committed versions
                    if (ts < cutoffTimestamp) {
                        storage.delete(mvccKeyBytes);
                        purged++;
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
        }

        if (purged > 0) {
            log.info("MVCC GC: purged {} versions (rolled_back + old committed, cutoff={})",
                    purged, cutoffTimestamp);
            if (eventBus != null) {
                eventBus.publish(ClusterEvent.mvccGarbageCollection(purged, cutoffTimestamp));
            }
        }
        return purged;
    }

    // ================================================================
    // Utility Methods
    // ================================================================

    public long nextTimestamp() {
        return timestampGenerator.incrementAndGet();
    }

    /**
     * Check if a (decoded) value is a tombstone marker.
     */
    private boolean isTombstoneValue(byte[] value) {
        return Arrays.equals(value, TOMBSTONE_MARKER);
    }

    /**
     * Check if a raw (state-encoded) value is a tombstone.
     */
    public static boolean isTombstone(byte[] encodedValue) {
        byte[] decoded = KeyEncoding.decodeValue(encodedValue);
        return Arrays.equals(decoded, TOMBSTONE_MARKER);
    }

    // ================================================================
    // Speculation Metrics (for paper evaluation)
    // ================================================================

    public long getTotalSpeculativeWrites() { return totalSpeculativeWrites.get(); }
    public long getSpeculativeWriteCount() { return totalSpeculativeWrites.get(); }
    public long getTotalCommitPromotions() { return totalCommitPromotions.get(); }
    public long getCommitPromotionCount() { return totalCommitPromotions.get(); }
    public long getTotalRollbacks() { return totalRollbacks.get(); }
    public long getRollbackCount() { return totalRollbacks.get(); }
    public long getTotalStandardWrites() { return totalStandardWrites.get(); }
    public int getPendingSpeculativeCount() { return speculativeVersions.size(); }
    public Map<String, SpeculativeEntry> getPendingSpeculativeVersions() {
        return Collections.unmodifiableMap(speculativeVersions);
    }

    /**
     * Speculation success rate: ratio of committed speculative writes to total speculative writes.
     * A high rate (>95%) indicates speculation is effective; low rate means too many rollbacks.
     */
    public double getSpeculationSuccessRate() {
        long total = totalSpeculativeWrites.get();
        if (total == 0) return 1.0;
        return (double) totalCommitPromotions.get() / total;
    }

    // ================================================================
    // MvccResult — Extended with speculation metadata
    // ================================================================

    /**
     * Result of an MVCC read operation, extended with speculation state.
     */
    public static class MvccResult {
        private final String key;
        private final byte[] value;
        private final long timestamp;
        private final boolean found;
        private final boolean tombstone;
        private final boolean speculative;
        private VersionState versionState;

        private MvccResult(String key, byte[] value, long timestamp,
                           boolean found, boolean tombstone, boolean speculative) {
            this.key = key;
            this.value = value;
            this.timestamp = timestamp;
            this.found = found;
            this.tombstone = tombstone;
            this.speculative = speculative;
            this.versionState = speculative ? VersionState.SPECULATIVE : VersionState.COMMITTED;
        }

        public static MvccResult found(String key, byte[] value, long timestamp) {
            return new MvccResult(key, value, timestamp, true, false, false);
        }

        public static MvccResult found(String key, byte[] value, long timestamp, boolean speculative) {
            return new MvccResult(key, value, timestamp, true, false, speculative);
        }

        public static MvccResult notFound(String key) {
            return new MvccResult(key, null, 0, false, false, false);
        }

        public static MvccResult tombstone(String key, long timestamp) {
            return new MvccResult(key, null, timestamp, false, true, false);
        }

        public static MvccResult tombstone(String key, long timestamp, VersionState state) {
            MvccResult r = new MvccResult(key, null, timestamp, false, true,
                    state == VersionState.SPECULATIVE);
            r.versionState = state;
            return r;
        }

        public String getKey() { return key; }
        public byte[] getValue() { return value; }
        public long getTimestamp() { return timestamp; }
        public boolean isFound() { return found; }
        public boolean isTombstone() { return tombstone; }

        /** Whether this result came from a speculative (not yet consensus-committed) version. */
        public boolean isSpeculative() { return speculative; }

        /** The precise version state (SPECULATIVE, COMMITTED, or ROLLED_BACK). */
        public VersionState getVersionState() { return versionState; }
        public void setVersionState(VersionState state) { this.versionState = state; }

        public String getValueAsString() {
            return value != null ? new String(value, StandardCharsets.UTF_8) : null;
        }
    }
}
