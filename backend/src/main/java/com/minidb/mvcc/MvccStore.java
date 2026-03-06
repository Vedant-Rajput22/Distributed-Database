package com.minidb.mvcc;

import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.storage.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-Version Concurrency Control store on top of the StorageEngine.
 * Each write creates a new version keyed by <user_key>:<timestamp>.
 * Reads at a given timestamp return the latest version <= that timestamp.
 */
public class MvccStore {

    private static final Logger log = LoggerFactory.getLogger(MvccStore.class);
    private static final byte[] TOMBSTONE_MARKER = "__TOMBSTONE__".getBytes(StandardCharsets.UTF_8);

    private final StorageEngine storage;
    private final EventBus eventBus;
    private final AtomicLong timestampGenerator = new AtomicLong(System.currentTimeMillis());

    public MvccStore(StorageEngine storage, EventBus eventBus) {
        this.storage = storage;
        this.eventBus = eventBus;
    }

    /**
     * Put a key-value pair with an auto-generated timestamp.
     * @return the timestamp used
     */
    public long put(String key, byte[] value) {
        long ts = nextTimestamp();
        return put(key, value, ts);
    }

    /**
     * Put a key-value pair with a specific timestamp.
     */
    public long put(String key, byte[] value, long timestamp) {
        byte[] mvccKey = KeyEncoding.encode(key, timestamp);
        storage.put(mvccKey, value);
        log.debug("MVCC PUT: key={}, ts={}, valueLen={}", key, timestamp, value.length);

        if (eventBus != null) {
            eventBus.publish(ClusterEvent.mvccVersionCreated(key, timestamp, value.length));
        }
        return timestamp;
    }

    /**
     * Get the latest value for a key (at current time).
     */
    public MvccResult get(String key) {
        return get(key, Long.MAX_VALUE);
    }

    /**
     * Snapshot read: get the latest value with timestamp <= readTimestamp.
     */
    public MvccResult get(String key, long readTimestamp) {
        byte[] prefix = KeyEncoding.encodePrefix(key);
        byte[] upperBound = KeyEncoding.encodePrefixUpperBound(key);

        List<Map.Entry<byte[], byte[]>> entries = storage.scan(prefix, upperBound, 0);

        for (Map.Entry<byte[], byte[]> entry : entries) {
            long entryTs = KeyEncoding.decodeTimestamp(entry.getKey());
            if (entryTs <= readTimestamp) {
                if (Arrays.equals(entry.getValue(), TOMBSTONE_MARKER)) {
                    return MvccResult.notFound(key);
                }
                return MvccResult.found(key, entry.getValue(), entryTs);
            }
        }
        return MvccResult.notFound(key);
    }

    /**
     * Delete a key by writing a tombstone.
     * @return the timestamp used
     */
    public long delete(String key) {
        long ts = nextTimestamp();
        return delete(key, ts);
    }

    /**
     * Delete a key at a specific timestamp.
     */
    public long delete(String key, long timestamp) {
        byte[] mvccKey = KeyEncoding.encode(key, timestamp);
        storage.put(mvccKey, TOMBSTONE_MARKER);
        log.debug("MVCC DELETE (tombstone): key={}, ts={}", key, timestamp);
        return timestamp;
    }

    /**
     * Scan a range of keys, returning the latest version of each key.
     */
    public List<MvccResult> scan(String startKey, String endKey, int limit, long readTimestamp) {
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
                // Skip non-MVCC entries
                continue;
            }
            // Skip internal/system keys (e.g., Raft log entries)
            if (userKey.startsWith("__")) continue;

            if (entryTs > readTimestamp) continue;
            if (latestVersions.containsKey(userKey)) continue; // already have newer version

            if (Arrays.equals(entry.getValue(), TOMBSTONE_MARKER)) {
                // tombstoned key - skip
                latestVersions.put(userKey, null);
            } else {
                latestVersions.put(userKey, MvccResult.found(userKey, entry.getValue(), entryTs));
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
     * Get all versions of a key (for MVCC version browser).
     */
    public List<MvccResult> getVersionHistory(String key) {
        byte[] prefix = KeyEncoding.encodePrefix(key);
        byte[] upperBound = KeyEncoding.encodePrefixUpperBound(key);

        List<Map.Entry<byte[], byte[]>> entries = storage.scan(prefix, upperBound, 0);
        List<MvccResult> versions = new ArrayList<>();

        for (Map.Entry<byte[], byte[]> entry : entries) {
            long entryTs = KeyEncoding.decodeTimestamp(entry.getKey());
            boolean isTombstone = Arrays.equals(entry.getValue(), TOMBSTONE_MARKER);
            if (isTombstone) {
                versions.add(MvccResult.tombstone(key, entryTs));
            } else {
                versions.add(MvccResult.found(key, entry.getValue(), entryTs));
            }
        }
        return versions;
    }

    /**
     * Garbage collect old versions. Keeps the latest version and removes versions
     * with timestamps older than (now - retentionMs).
     * @return number of versions purged
     */
    public int garbageCollect(long retentionMs) {
        long cutoffTimestamp = System.currentTimeMillis() - retentionMs;
        int purged = 0;

        // Scan all entries
        byte[] startKey = new byte[]{0};
        byte[] endKey = new byte[]{(byte) 0xFF};
        List<Map.Entry<byte[], byte[]>> entries = storage.scan(startKey, endKey, 0);

        Map<String, List<byte[]>> keyVersions = new LinkedHashMap<>();
        for (Map.Entry<byte[], byte[]> entry : entries) {
            try {
                String userKey = KeyEncoding.decodeUserKey(entry.getKey());
                keyVersions.computeIfAbsent(userKey, k -> new ArrayList<>()).add(entry.getKey());
            } catch (Exception e) {
                // Skip non-MVCC keys (e.g., Raft metadata)
            }
        }

        for (Map.Entry<String, List<byte[]>> kv : keyVersions.entrySet()) {
            List<byte[]> versions = kv.getValue();
            if (versions.size() <= 1) continue; // Keep at least one version

            // versions are sorted newest first (due to key encoding)
            for (int i = 1; i < versions.size(); i++) {
                try {
                    long ts = KeyEncoding.decodeTimestamp(versions.get(i));
                    if (ts < cutoffTimestamp) {
                        storage.delete(versions.get(i));
                        purged++;
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
        }

        if (purged > 0) {
            log.info("MVCC GC: purged {} old versions (cutoff={})", purged, cutoffTimestamp);
            if (eventBus != null) {
                eventBus.publish(ClusterEvent.mvccGarbageCollection(purged, cutoffTimestamp));
            }
        }
        return purged;
    }

    public long nextTimestamp() {
        return timestampGenerator.incrementAndGet();
    }

    /**
     * Check if a value is a tombstone marker.
     */
    public static boolean isTombstone(byte[] value) {
        return Arrays.equals(value, TOMBSTONE_MARKER);
    }

    /**
     * Result of an MVCC read operation.
     */
    public static class MvccResult {
        private final String key;
        private final byte[] value;
        private final long timestamp;
        private final boolean found;
        private final boolean tombstone;

        private MvccResult(String key, byte[] value, long timestamp, boolean found, boolean tombstone) {
            this.key = key;
            this.value = value;
            this.timestamp = timestamp;
            this.found = found;
            this.tombstone = tombstone;
        }

        public static MvccResult found(String key, byte[] value, long timestamp) {
            return new MvccResult(key, value, timestamp, true, false);
        }

        public static MvccResult notFound(String key) {
            return new MvccResult(key, null, 0, false, false);
        }

        public static MvccResult tombstone(String key, long timestamp) {
            return new MvccResult(key, null, timestamp, false, true);
        }

        public String getKey() { return key; }
        public byte[] getValue() { return value; }
        public long getTimestamp() { return timestamp; }
        public boolean isFound() { return found; }
        public boolean isTombstone() { return tombstone; }

        public String getValueAsString() {
            return value != null ? new String(value, StandardCharsets.UTF_8) : null;
        }
    }
}
