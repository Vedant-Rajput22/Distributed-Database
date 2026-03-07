package com.minidb.mvcc;

/**
 * Three-state MVCC version lifecycle for speculative consensus.
 *
 * <p>In standard Raft, a write is only visible after majority replication (1+ RTT).
 * Speculative MVCC writes the value immediately with state SPECULATIVE, then
 * promotes to COMMITTED once Raft confirms majority replication. If the leader
 * loses leadership before consensus, orphaned speculative versions are rolled
 * back to ROLLED_BACK (invisible to all reads).</p>
 *
 * <h3>State Transitions:</h3>
 * <pre>
 *   SPECULATIVE ──(majority ACK)──► COMMITTED
 *   SPECULATIVE ──(leader change / timeout)──► ROLLED_BACK
 * </pre>
 *
 * <h3>Visibility Rules:</h3>
 * <ul>
 *   <li>{@code COMMITTED} — visible to all read modes</li>
 *   <li>{@code SPECULATIVE} — visible only in {@link ReadMode#SPECULATIVE} mode</li>
 *   <li>{@code ROLLED_BACK} — invisible to all reads; cleaned up by GC</li>
 * </ul>
 *
 * <h3>Key Insight:</h3>
 * <p>MVCC already stores every version of every key. "Rolling back" a speculative
 * write costs O(1) — just flip the state byte. No undo log, no checkpoint, no
 * state snapshot required. The prior committed version is already in storage.</p>
 */
public enum VersionState {

    /**
     * Version written optimistically before Raft consensus completes.
     * The leader has appended the entry to its local log and responded
     * to the client, but majority replication is still in progress.
     */
    SPECULATIVE((byte) 0x01),

    /**
     * Version confirmed by Raft majority replication.
     * This is equivalent to a standard Raft-committed write.
     */
    COMMITTED((byte) 0x02),

    /**
     * Version invalidated due to leader change or consensus failure.
     * Invisible to all reads. Will be purged by garbage collection.
     */
    ROLLED_BACK((byte) 0x03);

    private final byte code;

    VersionState(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static VersionState fromCode(byte code) {
        return switch (code) {
            case 0x01 -> SPECULATIVE;
            case 0x02 -> COMMITTED;
            case 0x03 -> ROLLED_BACK;
            default -> COMMITTED; // legacy entries without state byte are committed
        };
    }

    /**
     * Check if this version is visible under the given read mode.
     */
    public boolean isVisibleUnder(ReadMode mode) {
        return switch (this) {
            case COMMITTED -> true;                          // always visible
            case SPECULATIVE -> mode == ReadMode.SPECULATIVE; // only in speculative mode
            case ROLLED_BACK -> false;                        // never visible
        };
    }
}
