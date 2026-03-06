package com.minidb.storage;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * RocksDB-backed storage engine implementation.
 */
public class RocksDbEngine implements StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(RocksDbEngine.class);

    private final String dbPath;
    private RocksDB db;
    private Options options;
    private volatile boolean closed = false;

    static {
        RocksDB.loadLibrary();
    }

    public RocksDbEngine(String dbPath) {
        this.dbPath = dbPath;
        open();
    }

    private void open() {
        try {
            File dir = new File(dbPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            options = new Options()
                    .setCreateIfMissing(true)
                    .setWriteBufferSize(64 * 1024 * 1024)       // 64MB write buffer
                    .setMaxWriteBufferNumber(3)
                    .setTargetFileSizeBase(64 * 1024 * 1024)    // 64MB SST files
                    .setMaxBackgroundJobs(4)
                    .setCompressionType(CompressionType.SNAPPY_COMPRESSION)
                    .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                    .setStatistics(new Statistics());

            db = RocksDB.open(options, dbPath);
            log.info("RocksDB opened at: {}", dbPath);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open RocksDB at " + dbPath, e);
        }
    }

    @Override
    public void put(byte[] key, byte[] value) {
        checkOpen();
        try {
            db.put(key, value);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB put failed", e);
        }
    }

    @Override
    public byte[] get(byte[] key) {
        checkOpen();
        try {
            return db.get(key);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB get failed", e);
        }
    }

    @Override
    public void delete(byte[] key) {
        checkOpen();
        try {
            db.delete(key);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB delete failed", e);
        }
    }

    @Override
    public List<Map.Entry<byte[], byte[]>> scan(byte[] startKey, byte[] endKey, int limit) {
        checkOpen();
        List<Map.Entry<byte[], byte[]>> results = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            iter.seek(startKey);
            int count = 0;
            while (iter.isValid()) {
                byte[] key = iter.key();
                if (endKey != null && compareBytes(key, endKey) >= 0) {
                    break;
                }
                if (limit > 0 && count >= limit) {
                    break;
                }
                results.add(Map.entry(key, iter.value()));
                count++;
                iter.next();
            }
        }
        return results;
    }

    @Override
    public void writeBatch(List<Map.Entry<byte[], byte[]>> entries) {
        checkOpen();
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            for (Map.Entry<byte[], byte[]> entry : entries) {
                if (entry.getValue() == null) {
                    batch.delete(entry.getKey());
                } else {
                    batch.put(entry.getKey(), entry.getValue());
                }
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB writeBatch failed", e);
        }
    }

    @Override
    public void createCheckpoint(String checkpointPath) {
        checkOpen();
        try (Checkpoint checkpoint = Checkpoint.create(db)) {
            File dir = new File(checkpointPath);
            if (dir.exists()) {
                deleteDirectory(dir);
            }
            checkpoint.createCheckpoint(checkpointPath);
            log.info("RocksDB checkpoint created at: {}", checkpointPath);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to create checkpoint", e);
        }
    }

    @Override
    public boolean isHealthy() {
        if (closed || db == null) return false;
        try {
            db.get("__health_check__".getBytes());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, String> getStats() {
        Map<String, String> stats = new HashMap<>();
        if (closed || db == null) return stats;
        try {
            stats.put("estimate-num-keys", db.getProperty("rocksdb.estimate-num-keys"));
            stats.put("estimate-live-data-size", db.getProperty("rocksdb.estimate-live-data-size"));
            stats.put("num-files-at-level0", db.getProperty("rocksdb.num-files-at-level0"));
            stats.put("compaction-pending", db.getProperty("rocksdb.compaction-pending"));
            stats.put("block-cache-usage", db.getProperty("rocksdb.block-cache-usage"));
            stats.put("cur-size-all-mem-tables", db.getProperty("rocksdb.cur-size-all-mem-tables"));
        } catch (RocksDBException e) {
            log.warn("Failed to get RocksDB stats", e);
        }
        return stats;
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed) {
            closed = true;
            if (db != null) {
                db.close();
                log.info("RocksDB closed");
            }
            if (options != null) {
                options.close();
            }
        }
    }

    public RocksDB getDb() {
        return db;
    }

    public String getDbPath() {
        return dbPath;
    }

    /**
     * Load from a snapshot directory by closing current DB, replacing files, and reopening.
     */
    public void loadFromSnapshot(String snapshotPath) {
        close();
        deleteDirectory(new File(dbPath));
        // Copy snapshot to dbPath
        try {
            copyDirectory(Path.of(snapshotPath), Path.of(dbPath));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load snapshot from " + snapshotPath, e);
        }
        closed = false;
        open();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("RocksDB is closed");
        }
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            int cmp = Byte.compareUnsigned(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }

    private static void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        dir.delete();
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        java.nio.file.Files.walk(source).forEach(s -> {
            try {
                Path t = target.resolve(source.relativize(s));
                if (java.nio.file.Files.isDirectory(s)) {
                    java.nio.file.Files.createDirectories(t);
                } else {
                    java.nio.file.Files.copy(s, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
