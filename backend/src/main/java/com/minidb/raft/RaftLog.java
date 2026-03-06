package com.minidb.raft;

import com.minidb.storage.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Persistent Raft log backed by the StorageEngine (RocksDB).
 * Log entries are stored with keys: __raft_log__:<index> (big-endian).
 */
public class RaftLog {

    private static final Logger log = LoggerFactory.getLogger(RaftLog.class);
    private static final String LOG_PREFIX = "__raft_log__:";

    private final StorageEngine storage;
    private volatile long lastIndex = 0;
    private volatile long lastTerm = 0;

    // In-memory cache of recent entries for performance
    private final NavigableMap<Long, Entry> cache = new TreeMap<>();
    private static final int MAX_CACHE_SIZE = 10000;

    public enum CommandType {
        NOOP, PUT, DELETE, TXN_PREPARE, TXN_COMMIT, TXN_ABORT
    }

    public record Entry(long index, long term, CommandType type, String key, byte[] value) {
        public byte[] serialize() {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 DataOutputStream dos = new DataOutputStream(bos)) {
                dos.writeLong(index);
                dos.writeLong(term);
                dos.writeInt(type.ordinal());
                dos.writeUTF(key != null ? key : "");
                if (value != null) {
                    dos.writeInt(value.length);
                    dos.write(value);
                } else {
                    dos.writeInt(-1);
                }
                return bos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException("Failed to serialize log entry", e);
            }
        }

        public static Entry deserialize(byte[] data) {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
                long index = dis.readLong();
                long term = dis.readLong();
                CommandType type = CommandType.values()[dis.readInt()];
                String key = dis.readUTF();
                if (key.isEmpty()) key = null;
                int valueLen = dis.readInt();
                byte[] value = null;
                if (valueLen >= 0) {
                    value = new byte[valueLen];
                    dis.readFully(value);
                }
                return new Entry(index, term, type, key, value);
            } catch (IOException e) {
                throw new RuntimeException("Failed to deserialize log entry", e);
            }
        }
    }

    public RaftLog(StorageEngine storage) {
        this.storage = storage;
        loadLastEntry();
    }

    /**
     * Append an entry to the log.
     */
    public void append(Entry entry) {
        byte[] key = logKey(entry.index());
        storage.put(key, entry.serialize());

        cache.put(entry.index(), entry);
        while (cache.size() > MAX_CACHE_SIZE) {
            cache.pollFirstEntry();
        }

        lastIndex = entry.index();
        lastTerm = entry.term();

        log.debug("Log append: index={}, term={}, type={}, key={}",
                entry.index(), entry.term(), entry.type(), entry.key());
    }

    /**
     * Get a log entry by index.
     */
    public Entry getEntry(long index) {
        Entry cached = cache.get(index);
        if (cached != null) return cached;

        byte[] data = storage.get(logKey(index));
        if (data == null) return null;

        Entry entry = Entry.deserialize(data);
        cache.put(index, entry);
        return entry;
    }

    /**
     * Get the term at a given log index. Returns -1 if not found.
     */
    public long getTermAt(long index) {
        if (index <= 0) return 0;
        Entry entry = getEntry(index);
        return entry != null ? entry.term() : -1;
    }

    /**
     * Get entries from startIndex (inclusive), up to maxCount.
     */
    public List<Entry> getEntriesFrom(long startIndex, int maxCount) {
        List<Entry> entries = new ArrayList<>();
        for (long i = startIndex; i <= lastIndex && entries.size() < maxCount; i++) {
            Entry entry = getEntry(i);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Get all entries (for UI display).
     */
    public List<Entry> getAllEntries() {
        return getEntriesFrom(1, (int) lastIndex);
    }

    /**
     * Truncate the log from the given index onwards (inclusive).
     */
    public void truncateFrom(long fromIndex) {
        for (long i = fromIndex; i <= lastIndex; i++) {
            storage.delete(logKey(i));
            cache.remove(i);
        }
        if (fromIndex <= 1) {
            lastIndex = 0;
            lastTerm = 0;
        } else {
            lastIndex = fromIndex - 1;
            Entry last = getEntry(lastIndex);
            lastTerm = last != null ? last.term() : 0;
        }
        log.info("Log truncated from index {}", fromIndex);
    }

    /**
     * Get the last log index.
     */
    public long getLastIndex() {
        return lastIndex;
    }

    /**
     * Get the term of the last log entry.
     */
    public long getLastTerm() {
        return lastTerm;
    }

    /**
     * Load the last entry from storage to initialize lastIndex and lastTerm.
     */
    private void loadLastEntry() {
        // Scan all log keys to find the last one
        byte[] prefix = LOG_PREFIX.getBytes(StandardCharsets.UTF_8);
        byte[] upper = (LOG_PREFIX + "\uffff").getBytes(StandardCharsets.UTF_8);
        List<Map.Entry<byte[], byte[]>> entries = storage.scan(prefix, upper, 0);

        for (Map.Entry<byte[], byte[]> kv : entries) {
            try {
                Entry entry = Entry.deserialize(kv.getValue());
                if (entry.index() > lastIndex) {
                    lastIndex = entry.index();
                    lastTerm = entry.term();
                }
                cache.put(entry.index(), entry);
            } catch (Exception e) {
                // Skip malformed entries
            }
        }

        if (lastIndex > 0) {
            log.info("Log loaded: lastIndex={}, lastTerm={}", lastIndex, lastTerm);
        }
    }

    private byte[] logKey(long index) {
        return (LOG_PREFIX + String.format("%020d", index)).getBytes(StandardCharsets.UTF_8);
    }
}
