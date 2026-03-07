package com.minidb.mvcc;

/**
 * Read consistency mode controlling visibility of speculative MVCC versions.
 *
 * <p>This is the key user-facing knob of the Speculative MVCC Consensus system.
 * Clients choose per-read whether they want the lowest latency (including
 * speculative data) or strict linearizability (only committed data).</p>
 *
 * <h3>Comparison to Prior Work:</h3>
 * <table>
 *   <tr><th>System</th><th>Read Modes</th></tr>
 *   <tr><td>Standard Raft</td><td>Strong only (1 RTT minimum)</td></tr>
 *   <tr><td>Zyzzyva / SpecPaxos</td><td>Speculative (requires undo log)</td></tr>
 *   <tr><td><b>Speculative MVCC (ours)</b></td><td><b>Per-read tunable, zero-cost rollback</b></td></tr>
 * </table>
 */
public enum ReadMode {

    /**
     * Only return committed (Raft-majority-confirmed) versions.
     * Equivalent to standard Raft linearizable read semantics.
     * No speculative data is ever visible.
     */
    LINEARIZABLE,

    /**
     * Include speculative (not yet majority-replicated) versions.
     * Returns the freshest data with minimum latency, but the value
     * may be rolled back if the leader changes before consensus.
     *
     * <p>The response includes a {@code speculative} flag so clients
     * can distinguish speculative from committed results.</p>
     */
    SPECULATIVE
}
