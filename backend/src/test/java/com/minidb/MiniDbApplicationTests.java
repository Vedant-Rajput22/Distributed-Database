package com.minidb;

import com.minidb.config.RaftConfig;
import com.minidb.events.EventBus;
import com.minidb.mvcc.KeyEncoding;
import com.minidb.raft.RaftLog;
import com.minidb.storage.RocksDbEngine;
import com.minidb.storage.StorageEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic unit tests for the Mini Distributed Database.
 */
class MiniDbApplicationTests {

    @TempDir
    Path tempDir;

    private StorageEngine storage;

    @BeforeEach
    void setUp() {
        storage = new RocksDbEngine(tempDir.resolve("testdb").toString());
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void contextLoads() {
        // Verifies the test setup works
        assertNotNull(storage);
        assertTrue(storage.isHealthy());
    }

    @Test
    void testPutAndGet() {
        storage.put("hello".getBytes(), "world".getBytes());
        byte[] value = storage.get("hello".getBytes());
        assertNotNull(value);
        assertEquals("world", new String(value));
    }

    @Test
    void testDelete() {
        storage.put("key1".getBytes(), "val1".getBytes());
        assertNotNull(storage.get("key1".getBytes()));

        storage.delete("key1".getBytes());
        assertNull(storage.get("key1".getBytes()));
    }

    @Test
    void testScan() {
        storage.put("user:1".getBytes(), "Alice".getBytes());
        storage.put("user:2".getBytes(), "Bob".getBytes());
        storage.put("user:3".getBytes(), "Charlie".getBytes());
        storage.put("post:1".getBytes(), "Hello".getBytes());

        var results = storage.scan("user:".getBytes(), "user:~".getBytes(), 100);
        assertEquals(3, results.size());
    }

    @Test
    void testKeyEncoding() {
        long ts1 = 1000;
        long ts2 = 2000;
        byte[] key1 = KeyEncoding.encode("mykey", ts1);
        byte[] key2 = KeyEncoding.encode("mykey", ts2);

        // Newer timestamp should sort BEFORE older in descending order
        assertTrue(compareBytes(key2, key1) < 0,
                "Newer timestamp should sort before older (descending)");

        assertEquals("mykey", KeyEncoding.decodeUserKey(key1));
        assertEquals(ts1, KeyEncoding.decodeTimestamp(key1));
    }

    @Test
    void testRaftLog() {
        RaftLog raftLog = new RaftLog(storage);
        assertEquals(0, raftLog.getLastIndex());

        raftLog.append(new RaftLog.Entry(1, 1, RaftLog.CommandType.PUT, "key1", "val1".getBytes()));
        raftLog.append(new RaftLog.Entry(2, 1, RaftLog.CommandType.PUT, "key2", "val2".getBytes()));

        assertEquals(2, raftLog.getLastIndex());
        assertEquals(1, raftLog.getTermAt(1));
        assertEquals(1, raftLog.getTermAt(2));

        var entry = raftLog.getEntry(1);
        assertNotNull(entry);
    }

    private int compareBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Byte.compareUnsigned(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }
}
