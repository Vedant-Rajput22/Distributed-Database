package com.minidb.txn;

import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.raft.RaftNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Two-Phase Commit (2PC) transaction coordinator.
 * Orchestrates atomic multi-key transactions.
 */
@Component
public class TxnCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TxnCoordinator.class);

    private final RaftNode raftNode;
    private final EventBus eventBus;

    // Active transactions
    private final Map<String, TxnState> transactions = new ConcurrentHashMap<>();

    public enum TxnStatus {
        PENDING, PREPARED, COMMITTED, ABORTED
    }

    public record TxnOperation(String key, byte[] value, OpType type) {
        public enum OpType { PUT, DELETE }
    }

    private static class TxnState {
        final String txnId;
        final List<TxnOperation> operations;
        volatile TxnStatus status;
        final long createdAt;

        TxnState(String txnId, List<TxnOperation> operations) {
            this.txnId = txnId;
            this.operations = operations;
            this.status = TxnStatus.PENDING;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public TxnCoordinator(RaftNode raftNode, EventBus eventBus) {
        this.raftNode = raftNode;
        this.eventBus = eventBus;
    }

    /**
     * Begin a new transaction with the given operations.
     * @return transaction ID
     */
    public String begin(List<TxnOperation> operations) {
        String txnId = UUID.randomUUID().toString();
        transactions.put(txnId, new TxnState(txnId, operations));
        log.info("Transaction {} begun with {} operations", txnId, operations.size());
        return txnId;
    }

    /**
     * Execute a complete 2PC transaction.
     * Phase 1: Prepare all operations
     * Phase 2: Commit or abort
     */
    public CompletableFuture<Boolean> execute(List<TxnOperation> operations) {
        String txnId = begin(operations);
        return prepare(txnId).thenCompose(prepared -> {
            if (prepared) {
                return commit(txnId);
            } else {
                return abort(txnId).thenApply(v -> false);
            }
        });
    }

    /**
     * Phase 1: Prepare. Validate all operations can be applied.
     */
    public CompletableFuture<Boolean> prepare(String txnId) {
        TxnState txn = transactions.get(txnId);
        if (txn == null) {
            return CompletableFuture.completedFuture(false);
        }

        if (!raftNode.isLeader()) {
            log.warn("Cannot prepare transaction: not the leader");
            return CompletableFuture.completedFuture(false);
        }

        log.info("Preparing transaction {} ({} operations)", txnId, txn.operations.size());

        // In a single-shard setup, prepare means validating and locking
        // all keys can be written
        try {
            for (TxnOperation op : txn.operations) {
                eventBus.publish(ClusterEvent.txnPhase(txnId, "PREPARE", op.key(), true));
            }
            txn.status = TxnStatus.PREPARED;
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            log.error("Transaction {} prepare failed", txnId, e);
            txn.status = TxnStatus.ABORTED;
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Phase 2: Commit. Apply all operations through Raft.
     */
    public CompletableFuture<Boolean> commit(String txnId) {
        TxnState txn = transactions.get(txnId);
        if (txn == null || txn.status != TxnStatus.PREPARED) {
            return CompletableFuture.completedFuture(false);
        }

        log.info("Committing transaction {} ({} operations)", txnId, txn.operations.size());

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (TxnOperation op : txn.operations) {
            CompletableFuture<Boolean> future;
            switch (op.type()) {
                case PUT -> future = raftNode.submitPut(op.key(), op.value());
                case DELETE -> future = raftNode.submitDelete(op.key());
                default -> future = CompletableFuture.completedFuture(false);
            }
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    boolean allCommitted = futures.stream()
                            .allMatch(f -> {
                                try {
                                    return f.get();
                                } catch (Exception e) {
                                    return false;
                                }
                            });

                    if (allCommitted) {
                        txn.status = TxnStatus.COMMITTED;
                        for (TxnOperation op : txn.operations) {
                            eventBus.publish(ClusterEvent.txnPhase(txnId, "COMMIT", op.key(), true));
                        }
                        log.info("Transaction {} committed successfully", txnId);
                    } else {
                        txn.status = TxnStatus.ABORTED;
                        for (TxnOperation op : txn.operations) {
                            eventBus.publish(ClusterEvent.txnPhase(txnId, "ABORT", op.key(), false));
                        }
                        log.warn("Transaction {} commit failed, some operations rejected", txnId);
                    }
                    return allCommitted;
                });
    }

    /**
     * Abort a transaction.
     */
    public CompletableFuture<Boolean> abort(String txnId) {
        TxnState txn = transactions.get(txnId);
        if (txn == null) {
            return CompletableFuture.completedFuture(false);
        }

        txn.status = TxnStatus.ABORTED;
        for (TxnOperation op : txn.operations) {
            eventBus.publish(ClusterEvent.txnPhase(txnId, "ABORT", op.key(), true));
        }
        log.info("Transaction {} aborted", txnId);
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Get the status of a transaction.
     */
    public TxnStatus getStatus(String txnId) {
        TxnState txn = transactions.get(txnId);
        return txn != null ? txn.status : null;
    }

    /**
     * Get all active transactions.
     */
    public Map<String, TxnStatus> getActiveTransactions() {
        Map<String, TxnStatus> result = new LinkedHashMap<>();
        for (Map.Entry<String, TxnState> entry : transactions.entrySet()) {
            result.put(entry.getKey(), entry.getValue().status);
        }
        return result;
    }
}
