package com.minidb.raft;

import com.minidb.config.RaftConfig;
import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.storage.RocksDbEngine;
import com.minidb.storage.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages snapshot creation (RocksDB checkpoints) and installation.
 * Not a Spring bean — manually instantiated by RaftNode.
 */
public class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);

    private final StorageEngine storage;
    private final RaftLog raftLog;
    private final RaftConfig config;

    private volatile long lastSnapshotIndex = 0;
    private volatile long lastSnapshotTerm = 0;
    private volatile long lastSnapshotSize = 0;
    private volatile long lastSnapshotTime = 0;

    // Temporary buffer for receiving snapshot chunks
    private ByteArrayOutputStream snapshotBuffer;

    public SnapshotManager(StorageEngine storage, RaftLog raftLog, RaftConfig config) {
        this.storage = storage;
        this.raftLog = raftLog;
        this.config = config;
    }

    /**
     * Create a snapshot (RocksDB checkpoint) at the current state.
     */
    public synchronized void createSnapshot(long lastIncludedIndex, long lastIncludedTerm) {
        String snapshotPath = getSnapshotDir();
        try {
            storage.createCheckpoint(snapshotPath);
            lastSnapshotIndex = lastIncludedIndex;
            lastSnapshotTerm = lastIncludedTerm;
            lastSnapshotTime = System.currentTimeMillis();
            lastSnapshotSize = calculateDirSize(Path.of(snapshotPath));

            log.info("Snapshot created: index={}, term={}, size={}",
                    lastIncludedIndex, lastIncludedTerm, lastSnapshotSize);
        } catch (Exception e) {
            log.error("Failed to create snapshot", e);
        }
    }

    /**
     * Get snapshot data as a byte array (for InstallSnapshot RPC).
     */
    public byte[] getSnapshotData() {
        String snapshotDir = getSnapshotDir();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            // Simple approach: tar-like packing of snapshot files
            Path dir = Path.of(snapshotDir);
            if (!Files.exists(dir)) return new byte[0];

            Files.walk(dir).filter(Files::isRegularFile).forEach(file -> {
                try {
                    String relativePath = dir.relativize(file).toString();
                    byte[] fileData = Files.readAllBytes(file);
                    DataOutputStream dos = new DataOutputStream(bos);
                    dos.writeUTF(relativePath);
                    dos.writeInt(fileData.length);
                    dos.write(fileData);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("Failed to read snapshot data", e);
            return new byte[0];
        }
    }

    /**
     * Receive a chunk of snapshot data.
     */
    public synchronized void receiveSnapshotChunk(byte[] data, long offset, boolean done) {
        if (offset == 0) {
            snapshotBuffer = new ByteArrayOutputStream();
        }
        if (snapshotBuffer != null) {
            snapshotBuffer.write(data, 0, data.length);
        }
    }

    /**
     * Apply a received snapshot.
     */
    public synchronized void applyReceivedSnapshot(long lastIncludedIndex, long lastIncludedTerm) {
        if (snapshotBuffer == null) return;

        String snapshotDir = getSnapshotDir() + "-received";
        try {
            byte[] fullData = snapshotBuffer.toByteArray();
            snapshotBuffer = null;

            // Unpack snapshot files
            Path dir = Path.of(snapshotDir);
            Files.createDirectories(dir);

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(fullData));
            while (dis.available() > 0) {
                String relativePath = dis.readUTF();
                int fileLen = dis.readInt();
                byte[] fileData = new byte[fileLen];
                dis.readFully(fileData);
                Path filePath = dir.resolve(relativePath);
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, fileData);
            }

            // Replace current DB with snapshot
            if (storage instanceof RocksDbEngine rocksDb) {
                rocksDb.loadFromSnapshot(snapshotDir);
            }

            lastSnapshotIndex = lastIncludedIndex;
            lastSnapshotTerm = lastIncludedTerm;
            lastSnapshotTime = System.currentTimeMillis();

            log.info("Received snapshot applied: index={}, term={}",
                    lastIncludedIndex, lastIncludedTerm);
        } catch (Exception e) {
            log.error("Failed to apply received snapshot", e);
        }
    }

    /**
     * Check if a snapshot should be triggered based on log size.
     */
    public boolean shouldSnapshot() {
        return raftLog.getLastIndex() - lastSnapshotIndex >= config.getSnapshotInterval();
    }

    private String getSnapshotDir() {
        if (storage instanceof RocksDbEngine rocksDb) {
            return rocksDb.getDbPath() + "-snapshot";
        }
        return "data/snapshot-" + config.getNodeId();
    }

    private long calculateDirSize(Path path) {
        try {
            return Files.walk(path)
                    .filter(Files::isRegularFile)
                    .mapToLong(f -> f.toFile().length())
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    // Getters
    public long getLastSnapshotIndex() { return lastSnapshotIndex; }
    public long getLastSnapshotTerm() { return lastSnapshotTerm; }
    public long getLastSnapshotSize() { return lastSnapshotSize; }
    public long getLastSnapshotTime() { return lastSnapshotTime; }
}
