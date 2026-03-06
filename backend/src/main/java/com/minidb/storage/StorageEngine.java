package com.minidb.storage;

import java.util.List;
import java.util.Map;

/**
 * Storage engine interface abstracting the underlying key-value store.
 * All keys and values are byte arrays for maximum flexibility.
 */
public interface StorageEngine {

    /**
     * Store a key-value pair.
     */
    void put(byte[] key, byte[] value);

    /**
     * Retrieve the value for a key, or null if not found.
     */
    byte[] get(byte[] key);

    /**
     * Delete a key.
     */
    void delete(byte[] key);

    /**
     * Range scan from startKey (inclusive) to endKey (exclusive).
     * If limit <= 0, returns all matches.
     */
    List<Map.Entry<byte[], byte[]>> scan(byte[] startKey, byte[] endKey, int limit);

    /**
     * Write a batch of key-value pairs atomically.
     */
    void writeBatch(List<Map.Entry<byte[], byte[]>> entries);

    /**
     * Create a checkpoint (snapshot) at the given path.
     */
    void createCheckpoint(String checkpointPath);

    /**
     * Check if the storage engine is healthy and operational.
     */
    boolean isHealthy();

    /**
     * Get storage statistics.
     */
    Map<String, String> getStats();

    /**
     * Close the storage engine and release resources.
     */
    void close();
}
