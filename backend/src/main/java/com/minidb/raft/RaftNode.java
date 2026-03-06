package com.minidb.raft;

import com.minidb.config.RaftConfig;
import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.mvcc.MvccStore;
import com.minidb.storage.StorageEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core Raft state machine implementing leader election, log replication,
 * and commit tracking. Implements the Raft protocol from the paper.
 */
@Component
public class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    // Persistent state (saved to RocksDB)
    private volatile long currentTerm = 0;
    private volatile String votedFor = null;

    // Volatile state
    private volatile Role role = Role.FOLLOWER;
    private volatile String leaderId = null;
    private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;

    // Leader state
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    // Election state
    private volatile long lastHeartbeatTime = System.currentTimeMillis();
    private volatile int currentElectionTimeout;

    // Components
    private final RaftConfig config;
    private final RaftLog raftLog;
    private final MvccStore mvccStore;
    private final StorageEngine storage;
    private final EventBus eventBus;
    private final ElectionTimer electionTimer;
    private final SnapshotManager snapshotManager;
    private final ReentrantLock lock = new ReentrantLock();

    // Client request tracking: logIndex -> CompletableFuture
    private final Map<Long, CompletableFuture<Boolean>> pendingRequests = new ConcurrentHashMap<>();

    // Peer communication (set by gRPC service)
    private volatile RaftPeerClient peerClient;

    // Chaos simulation
    private volatile boolean killed = false;
    private final Set<String> partitionedPeers = ConcurrentHashMap.newKeySet();

    // Peer health tracking: peer address -> last successful contact timestamp
    private final Map<String, Long> peerLastSeen = new ConcurrentHashMap<>();
    private final Map<String, Boolean> peerAlive = new ConcurrentHashMap<>();
    private static final long PEER_DEAD_THRESHOLD_MS = 5000; // 5 seconds without contact = dead

    // Metrics
    private final Counter electionsTotal;
    private final Counter logEntriesTotal;

    // Scheduled executor for heartbeats and election timer
    private ScheduledExecutorService scheduler;

    // Startup time
    private final long startTime = System.currentTimeMillis();

    public RaftNode(RaftConfig config, StorageEngine storage, EventBus eventBus,
                    MeterRegistry meterRegistry) {
        this.config = config;
        this.storage = storage;
        this.eventBus = eventBus;
        this.raftLog = new RaftLog(storage);
        this.mvccStore = new MvccStore(storage, eventBus);
        this.electionTimer = new ElectionTimer(config.getElectionTimeoutMin(),
                config.getElectionTimeoutMax());
        this.snapshotManager = new SnapshotManager(storage, raftLog, config);

        // Register metrics
        this.electionsTotal = Counter.builder("raft_elections_total")
                .description("Total number of elections")
                .register(meterRegistry);
        this.logEntriesTotal = Counter.builder("raft_log_entries_total")
                .description("Total log entries appended")
                .register(meterRegistry);
        Gauge.builder("raft_term", () -> currentTerm)
                .description("Current Raft term")
                .register(meterRegistry);
        Gauge.builder("raft_commit_index", () -> commitIndex)
                .description("Current commit index")
                .register(meterRegistry);
        Gauge.builder("raft_last_applied", () -> lastApplied)
                .description("Last applied index")
                .register(meterRegistry);
    }

    @PostConstruct
    public void start() {
        // Load persisted state
        loadPersistedState();

        // Reset election timeout
        currentElectionTimeout = electionTimer.randomTimeout();
        lastHeartbeatTime = System.currentTimeMillis();

        // Start scheduler
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("raft-scheduler-" + config.getNodeId());
            return t;
        });

        // Election timer check
        scheduler.scheduleAtFixedRate(this::checkElectionTimeout,
                100, 50, TimeUnit.MILLISECONDS);

        // Apply committed entries
        scheduler.scheduleAtFixedRate(this::applyCommittedEntries,
                100, 10, TimeUnit.MILLISECONDS);

        log.info("RaftNode started: id={}, peers={}", config.getNodeId(), config.getPeers());
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        log.info("RaftNode stopped: id={}", config.getNodeId());
    }

    public void setPeerClient(RaftPeerClient peerClient) {
        this.peerClient = peerClient;
    }

    // ============================================================
    // Leader Election
    // ============================================================

    private void checkElectionTimeout() {
        if (killed || role == Role.LEADER) return;

        long elapsed = System.currentTimeMillis() - lastHeartbeatTime;
        if (elapsed >= currentElectionTimeout) {
            startElection();
        }
    }

    private void startElection() {
        lock.lock();
        try {
            if (killed) return;

            currentTerm++;
            Role oldRole = role;
            role = Role.CANDIDATE;
            votedFor = config.getNodeId();
            persistState();

            log.info("[{}] Starting election for term {}", config.getNodeId(), currentTerm);
            eventBus.publish(ClusterEvent.electionStarted(config.getNodeId(), currentTerm));
            if (oldRole != Role.CANDIDATE) {
                eventBus.publish(ClusterEvent.raftStateChange(config.getNodeId(),
                        oldRole.name(), Role.CANDIDATE.name(), currentTerm));
            }
            electionsTotal.increment();

            currentElectionTimeout = electionTimer.randomTimeout();
            lastHeartbeatTime = System.currentTimeMillis();

            // Vote for self
            int votesReceived = 1;
            int peersCount = config.getPeers().size();
            int majority = (peersCount + 1) / 2 + 1;

            if (peersCount == 0) {
                // Single node cluster - become leader immediately
                becomeLeader();
                return;
            }

            long savedTerm = currentTerm;
            long lastLogIndex = raftLog.getLastIndex();
            long lastLogTerm = raftLog.getLastTerm();

            // Request votes from peers in parallel
            if (peerClient != null) {
                List<CompletableFuture<RequestVoteResult>> futures = new ArrayList<>();
                for (String peer : config.getPeers()) {
                    if (partitionedPeers.contains(peer)) continue;
                    futures.add(peerClient.requestVote(peer, savedTerm,
                            config.getNodeId(), lastLogIndex, lastLogTerm));
                }

                // Count votes asynchronously
                CompletableFuture.runAsync(() -> {
                    int votes = 1; // self-vote
                    for (CompletableFuture<RequestVoteResult> f : futures) {
                        try {
                            RequestVoteResult result = f.get(
                                    config.getElectionTimeoutMax(), TimeUnit.MILLISECONDS);
                            if (result != null) {
                                if (result.term() > savedTerm) {
                                    stepDown(result.term());
                                    return;
                                }
                                if (result.voteGranted()) {
                                    votes++;
                                }
                            }
                        } catch (Exception e) {
                            log.debug("[{}] Vote request failed: {}", config.getNodeId(), e.getMessage());
                        }
                    }

                    lock.lock();
                    try {
                        if (role == Role.CANDIDATE && currentTerm == savedTerm && votes >= majority) {
                            becomeLeader();
                        }
                    } finally {
                        lock.unlock();
                    }
                });
            }
        } finally {
            lock.unlock();
        }
    }

    private void becomeLeader() {
        Role oldRole = role;
        role = Role.LEADER;
        leaderId = config.getNodeId();

        log.info("[{}] Became LEADER for term {}", config.getNodeId(), currentTerm);
        eventBus.publish(ClusterEvent.leaderElected(config.getNodeId(), currentTerm));
        eventBus.publish(ClusterEvent.raftStateChange(config.getNodeId(),
                oldRole.name(), Role.LEADER.name(), currentTerm));

        // Initialize leader state
        long lastLogIdx = raftLog.getLastIndex();
        for (String peer : config.getPeers()) {
            nextIndex.put(peer, lastLogIdx + 1);
            matchIndex.put(peer, 0L);
        }

        // Append a no-op entry to commit entries from previous terms
        RaftLog.Entry noop = new RaftLog.Entry(
                raftLog.getLastIndex() + 1, currentTerm,
                RaftLog.CommandType.NOOP, "", null);
        raftLog.append(noop);

        // Start heartbeat timer
        startHeartbeats();
    }

    private void startHeartbeats() {
        scheduler.scheduleAtFixedRate(() -> {
            if (role == Role.LEADER && !killed) {
                sendHeartbeats();
            }
        }, 0, config.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeats() {
        if (peerClient == null) return;

        for (String peer : config.getPeers()) {
            if (partitionedPeers.contains(peer)) continue;

            long prevLogIndex = nextIndex.getOrDefault(peer, 1L) - 1;
            long prevLogTerm = prevLogIndex > 0 ? raftLog.getTermAt(prevLogIndex) : 0;

            // Gather entries to replicate
            List<RaftLog.Entry> entries = raftLog.getEntriesFrom(
                    nextIndex.getOrDefault(peer, 1L), 100);

            long startTime = System.currentTimeMillis();
            peerClient.appendEntries(peer, currentTerm, config.getNodeId(),
                    prevLogIndex, prevLogTerm, entries, commitIndex)
                    .thenAccept(result -> {
                        long latency = System.currentTimeMillis() - startTime;
                        if (result == null) {
                            // gRPC call failed (peer returned error or is unreachable)
                            peerAlive.put(peer, false);
                            log.debug("[{}] AppendEntries to {} returned null (unreachable)",
                                    config.getNodeId(), peer);
                            return;
                        }

                        if (result.term() > currentTerm) {
                            stepDown(result.term());
                            return;
                        }

                        if (result.success()) {
                            // Mark peer as alive
                            peerLastSeen.put(peer, System.currentTimeMillis());
                            peerAlive.put(peer, true);

                            if (!entries.isEmpty()) {
                                long newMatchIndex = entries.get(entries.size() - 1).index();
                                matchIndex.put(peer, newMatchIndex);
                                nextIndex.put(peer, newMatchIndex + 1);
                                eventBus.publish(ClusterEvent.appendEntriesAcked(peer, newMatchIndex));
                                tryAdvanceCommitIndex();
                            }
                            eventBus.publish(ClusterEvent.heartbeatSent(
                                    config.getNodeId(), peer, latency));
                        } else {
                            // Peer responded but rejected — still alive
                            peerLastSeen.put(peer, System.currentTimeMillis());
                            peerAlive.put(peer, true);
                            // Decrement nextIndex and retry
                            long newNext = Math.max(1, nextIndex.getOrDefault(peer, 1L) - 1);
                            nextIndex.put(peer, newNext);
                            eventBus.publish(ClusterEvent.appendEntriesRejected(peer, result.term()));
                        }
                    })
                    .exceptionally(e -> {
                        // Peer unreachable — mark as down
                        peerAlive.put(peer, false);
                        log.debug("[{}] AppendEntries to {} failed: {}",
                                config.getNodeId(), peer, e.getMessage());
                        return null;
                    });
        }
    }

    /**
     * Try to advance commitIndex based on matchIndex values.
     */
    private void tryAdvanceCommitIndex() {
        long lastLogIndex = raftLog.getLastIndex();
        for (long n = lastLogIndex; n > commitIndex; n--) {
            if (raftLog.getTermAt(n) != currentTerm) continue;

            int replicatedCount = 1; // self
            for (String peer : config.getPeers()) {
                if (matchIndex.getOrDefault(peer, 0L) >= n) {
                    replicatedCount++;
                }
            }
            int majority = (config.getPeers().size() + 1) / 2 + 1;
            if (replicatedCount >= majority) {
                commitIndex = n;
                log.debug("[{}] Advanced commitIndex to {}", config.getNodeId(), commitIndex);
                break;
            }
        }
    }

    // ============================================================
    // RPC Handlers
    // ============================================================

    /**
     * Handle RequestVote RPC.
     */
    public RequestVoteResult handleRequestVote(long term, String candidateId,
                                                long lastLogIndex, long lastLogTerm) {
        lock.lock();
        try {
            if (killed) return new RequestVoteResult(currentTerm, false);

            if (term > currentTerm) {
                stepDown(term);
            }

            if (term < currentTerm) {
                eventBus.publish(ClusterEvent.voteRejected(config.getNodeId(), candidateId, term));
                return new RequestVoteResult(currentTerm, false);
            }

            boolean logUpToDate = isLogUpToDate(lastLogIndex, lastLogTerm);
            boolean canVote = (votedFor == null || votedFor.equals(candidateId)) && logUpToDate;

            if (canVote) {
                votedFor = candidateId;
                persistState();
                lastHeartbeatTime = System.currentTimeMillis();
                log.info("[{}] Voted for {} in term {}", config.getNodeId(), candidateId, currentTerm);
                eventBus.publish(ClusterEvent.voteGranted(config.getNodeId(), candidateId, term));
                return new RequestVoteResult(currentTerm, true);
            }

            eventBus.publish(ClusterEvent.voteRejected(config.getNodeId(), candidateId, term));
            return new RequestVoteResult(currentTerm, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handle AppendEntries RPC.
     */
    public AppendEntriesResult handleAppendEntries(long term, String leaderIdIn,
                                                     long prevLogIndex, long prevLogTerm,
                                                     List<RaftLog.Entry> entries,
                                                     long leaderCommit) {
        lock.lock();
        try {
            if (killed) return new AppendEntriesResult(currentTerm, false, 0);

            if (term > currentTerm) {
                stepDown(term);
            }

            if (term < currentTerm) {
                return new AppendEntriesResult(currentTerm, false, 0);
            }

            // Valid leader heartbeat
            lastHeartbeatTime = System.currentTimeMillis();
            leaderId = leaderIdIn;

            // Track that we successfully heard from the leader
            // Find peer identifier that matches the leader
            for (String peer : config.getPeers()) {
                String peerId = peer.contains("@") ? peer.split("@")[0] : peer;
                if (peerId.equals(leaderIdIn)) {
                    peerLastSeen.put(peer, System.currentTimeMillis());
                    peerAlive.put(peer, true);
                    break;
                }
            }

            if (role != Role.FOLLOWER) {
                Role oldRole = role;
                role = Role.FOLLOWER;
                eventBus.publish(ClusterEvent.raftStateChange(config.getNodeId(),
                        oldRole.name(), Role.FOLLOWER.name(), currentTerm));
            }

            eventBus.publish(ClusterEvent.heartbeatReceived(config.getNodeId(), leaderIdIn));

            // Check log consistency
            if (prevLogIndex > 0) {
                long termAtPrev = raftLog.getTermAt(prevLogIndex);
                if (termAtPrev == -1 || termAtPrev != prevLogTerm) {
                    return new AppendEntriesResult(currentTerm, false, 0);
                }
            }

            // Append new entries
            if (entries != null && !entries.isEmpty()) {
                for (RaftLog.Entry entry : entries) {
                    long existingTerm = raftLog.getTermAt(entry.index());
                    if (existingTerm != -1 && existingTerm != entry.term()) {
                        // Conflicting entry - delete it and all after
                        raftLog.truncateFrom(entry.index());
                    }
                    if (raftLog.getTermAt(entry.index()) == -1) {
                        raftLog.append(entry);
                        logEntriesTotal.increment();
                    }
                }
            }

            // Update commit index
            if (leaderCommit > commitIndex) {
                commitIndex = Math.min(leaderCommit, raftLog.getLastIndex());
            }

            return new AppendEntriesResult(currentTerm, true, raftLog.getLastIndex());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handle InstallSnapshot RPC.
     */
    public long handleInstallSnapshot(long term, String leaderIdIn,
                                       long lastIncludedIndex, long lastIncludedTerm,
                                       byte[] data, long offset, boolean done) {
        lock.lock();
        try {
            if (killed) return currentTerm;

            if (term > currentTerm) {
                stepDown(term);
            }

            if (term < currentTerm) {
                return currentTerm;
            }

            lastHeartbeatTime = System.currentTimeMillis();
            leaderId = leaderIdIn;

            eventBus.publish(ClusterEvent.snapshotInstallProgress(
                    config.getNodeId(), offset, offset + data.length));

            snapshotManager.receiveSnapshotChunk(data, offset, done);

            if (done) {
                snapshotManager.applyReceivedSnapshot(lastIncludedIndex, lastIncludedTerm);
                commitIndex = lastIncludedIndex;
                lastApplied = lastIncludedIndex;
                log.info("[{}] Snapshot installed up to index {}", config.getNodeId(), lastIncludedIndex);
            }

            return currentTerm;
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Client Request Handling
    // ============================================================

    /**
     * Submit a Put command. Returns a future that completes when committed.
     */
    public CompletableFuture<Boolean> submitPut(String key, byte[] value) {
        return submitCommand(RaftLog.CommandType.PUT, key, value);
    }

    /**
     * Submit a Delete command. Returns a future that completes when committed.
     */
    public CompletableFuture<Boolean> submitDelete(String key) {
        return submitCommand(RaftLog.CommandType.DELETE, key, null);
    }

    private CompletableFuture<Boolean> submitCommand(RaftLog.CommandType type,
                                                      String key, byte[] value) {
        if (role != Role.LEADER) {
            return CompletableFuture.completedFuture(false);
        }
        if (killed) {
            return CompletableFuture.completedFuture(false);
        }

        lock.lock();
        try {
            RaftLog.Entry entry = new RaftLog.Entry(
                    raftLog.getLastIndex() + 1, currentTerm, type, key, value);
            raftLog.append(entry);
            logEntriesTotal.increment();

            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pendingRequests.put(entry.index(), future);

            // Trigger immediate replication
            sendHeartbeats();

            // Timeout pending requests
            CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
                    .execute(() -> future.completeExceptionally(
                            new TimeoutException("Command not committed within timeout")));

            return future;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Linearizable read using ReadIndex protocol.
     */
    public CompletableFuture<MvccStore.MvccResult> consistentGet(String key) {
        if (role != Role.LEADER) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Not the leader"));
        }

        long readIndex = commitIndex;

        // Confirm leadership with a heartbeat round
        return confirmLeadership().thenApply(confirmed -> {
            if (!confirmed) {
                throw new CompletionException(
                        new IllegalStateException("Lost leadership during read"));
            }
            // Wait for lastApplied >= readIndex
            while (lastApplied < readIndex) {
                Thread.onSpinWait();
            }
            return mvccStore.get(key);
        });
    }

    /**
     * Confirm leadership by getting acknowledgment from majority.
     */
    private CompletableFuture<Boolean> confirmLeadership() {
        if (config.getPeers().isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        if (peerClient == null) {
            return CompletableFuture.completedFuture(role == Role.LEADER);
        }

        List<CompletableFuture<Boolean>> heartbeatFutures = new ArrayList<>();
        for (String peer : config.getPeers()) {
            if (partitionedPeers.contains(peer)) continue;

            long prevLogIndex = nextIndex.getOrDefault(peer, 1L) - 1;
            long prevLogTerm = prevLogIndex > 0 ? raftLog.getTermAt(prevLogIndex) : 0;

            heartbeatFutures.add(
                    peerClient.appendEntries(peer, currentTerm, config.getNodeId(),
                                    prevLogIndex, prevLogTerm, List.of(), commitIndex)
                            .thenApply(result -> result != null && result.success())
                            .exceptionally(e -> false)
            );
        }

        return CompletableFuture.allOf(heartbeatFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    int acks = 1; // self
                    for (CompletableFuture<Boolean> f : heartbeatFutures) {
                        try {
                            if (f.get()) acks++;
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    int majority = (config.getPeers().size() + 1) / 2 + 1;
                    return acks >= majority;
                });
    }

    // ============================================================
    // State Machine Application
    // ============================================================

    private void applyCommittedEntries() {
        if (killed) return;

        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLog.Entry entry = raftLog.getEntry(lastApplied);
            if (entry == null) break;

            applyEntry(entry);

            // Complete pending client request
            CompletableFuture<Boolean> future = pendingRequests.remove(entry.index());
            if (future != null) {
                future.complete(true);
            }
        }
    }

    private void applyEntry(RaftLog.Entry entry) {
        switch (entry.type()) {
            case PUT -> {
                if (entry.key() != null && entry.value() != null) {
                    mvccStore.put(entry.key(), entry.value(), mvccStore.nextTimestamp());
                    log.debug("[{}] Applied PUT: key={}", config.getNodeId(), entry.key());
                }
            }
            case DELETE -> {
                if (entry.key() != null) {
                    mvccStore.delete(entry.key(), mvccStore.nextTimestamp());
                    log.debug("[{}] Applied DELETE: key={}", config.getNodeId(), entry.key());
                }
            }
            case NOOP -> {
                log.debug("[{}] Applied NOOP at index {}", config.getNodeId(), entry.index());
            }
            default -> {
                log.debug("[{}] Skipping entry type: {}", config.getNodeId(), entry.type());
            }
        }
    }

    // ============================================================
    // State Transitions
    // ============================================================

    private void stepDown(long newTerm) {
        Role oldRole = role;
        currentTerm = newTerm;
        role = Role.FOLLOWER;
        votedFor = null;
        leaderId = null;
        persistState();

        if (oldRole != Role.FOLLOWER) {
            log.info("[{}] Stepping down to FOLLOWER (term {})", config.getNodeId(), newTerm);
            eventBus.publish(ClusterEvent.raftStateChange(config.getNodeId(),
                    oldRole.name(), Role.FOLLOWER.name(), currentTerm));
        }
    }

    private boolean isLogUpToDate(long lastLogIndex, long lastLogTerm) {
        long myLastTerm = raftLog.getLastTerm();
        long myLastIndex = raftLog.getLastIndex();

        if (lastLogTerm != myLastTerm) {
            return lastLogTerm > myLastTerm;
        }
        return lastLogIndex >= myLastIndex;
    }

    // ============================================================
    // Persistence
    // ============================================================

    private void persistState() {
        try {
            storage.put("__raft_term__".getBytes(StandardCharsets.UTF_8),
                    ByteBuffer.allocate(8).putLong(currentTerm).array());
            if (votedFor != null) {
                storage.put("__raft_voted_for__".getBytes(StandardCharsets.UTF_8),
                        votedFor.getBytes(StandardCharsets.UTF_8));
            } else {
                storage.delete("__raft_voted_for__".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error("Failed to persist Raft state", e);
        }
    }

    private void loadPersistedState() {
        try {
            byte[] termBytes = storage.get("__raft_term__".getBytes(StandardCharsets.UTF_8));
            if (termBytes != null) {
                currentTerm = ByteBuffer.wrap(termBytes).getLong();
            }
            byte[] votedForBytes = storage.get("__raft_voted_for__".getBytes(StandardCharsets.UTF_8));
            if (votedForBytes != null) {
                votedFor = new String(votedForBytes, StandardCharsets.UTF_8);
            }
            log.info("[{}] Loaded persisted state: term={}, votedFor={}",
                    config.getNodeId(), currentTerm, votedFor);
        } catch (Exception e) {
            log.warn("Failed to load persisted state, starting fresh", e);
        }
    }

    // ============================================================
    // Chaos Engineering
    // ============================================================

    public void kill() {
        killed = true;
        eventBus.publish(ClusterEvent.nodeDown(config.getNodeId()));
        log.info("[{}] Node KILLED (simulated)", config.getNodeId());
    }

    public void recover() {
        killed = false;
        lastHeartbeatTime = System.currentTimeMillis();
        currentElectionTimeout = electionTimer.randomTimeout();
        eventBus.publish(ClusterEvent.nodeRecovered(config.getNodeId()));
        log.info("[{}] Node RECOVERED", config.getNodeId());
    }

    public void addPartition(String peerId) {
        partitionedPeers.add(peerId);
        eventBus.publish(ClusterEvent.chaosAction("PARTITION", peerId));
    }

    public void removePartition(String peerId) {
        partitionedPeers.remove(peerId);
        eventBus.publish(ClusterEvent.chaosAction("HEAL_PARTITION", peerId));
    }

    public boolean isPartitioned(String peerId) {
        return partitionedPeers.contains(peerId);
    }

    // ============================================================
    // Getters
    // ============================================================

    public String getNodeId() { return config.getNodeId(); }
    public long getCurrentTerm() { return currentTerm; }
    public Role getRole() { return role; }
    public String getLeaderId() { return leaderId; }
    public long getCommitIndex() { return commitIndex; }
    public long getLastApplied() { return lastApplied; }
    public String getVotedFor() { return votedFor; }
    public boolean isKilled() { return killed; }
    public boolean isLeader() { return role == Role.LEADER && !killed; }
    public RaftLog getRaftLog() { return raftLog; }
    public MvccStore getMvccStore() { return mvccStore; }
    public SnapshotManager getSnapshotManager() { return snapshotManager; }
    public Map<String, Long> getNextIndex() { return Collections.unmodifiableMap(nextIndex); }
    public Map<String, Long> getMatchIndex() { return Collections.unmodifiableMap(matchIndex); }
    public Set<String> getPartitionedPeers() { return Collections.unmodifiableSet(partitionedPeers); }
    public long getUptime() { return System.currentTimeMillis() - startTime; }
    public List<String> getPeers() { return config.getPeers(); }

    /**
     * Check if a peer is alive based on heartbeat tracking and chaos notifications.
     * All roles first check the explicit peerAlive map (set by chaos kill/recover
     * broadcasts). Leaders additionally use heartbeat response tracking.
     */
    public boolean isPeerAlive(String peer) {
        // First check explicit alive/dead marking (from chaos operations — works for ALL roles)
        Boolean alive = peerAlive.get(peer);
        if (alive != null) return alive;

        // If we're the leader, also use heartbeat response tracking
        if (role == Role.LEADER) {
            Long lastSeen = peerLastSeen.get(peer);
            if (lastSeen == null) return true; // assume alive until proven otherwise
            return (System.currentTimeMillis() - lastSeen) < PEER_DEAD_THRESHOLD_MS;
        }

        // For followers with no explicit info, assume alive unless partitioned
        return !partitionedPeers.contains(peer);
    }

    public Map<String, Long> getPeerLastSeen() { return Collections.unmodifiableMap(peerLastSeen); }
    public Map<String, Boolean> getPeerAliveMap() { return Collections.unmodifiableMap(peerAlive); }

    /**
     * Immediately mark a peer as dead — used after a chaos kill is forwarded so the
     * topology reflects the change without waiting for the next heartbeat cycle.
     */
    public void markPeerDead(String peer) {
        peerAlive.put(peer, false);
        log.info("[{}] Peer {} marked as dead (chaos kill)", config.getNodeId(), peer);
    }

    /**
     * Mark a peer as alive — used after a chaos recover is forwarded so the
     * topology reflects the change immediately.
     */
    public void markPeerAlive(String peer) {
        peerAlive.put(peer, true);
        peerLastSeen.put(peer, System.currentTimeMillis());
        log.info("[{}] Peer {} marked as alive (chaos recover)", config.getNodeId(), peer);
    }

    // Record types for RPC results
    public record RequestVoteResult(long term, boolean voteGranted) {}
    public record AppendEntriesResult(long term, boolean success, long matchIndex) {}
}
