package com.minidb.raft;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomized election timer for Raft.
 * Uses a random timeout between minMs and maxMs to prevent split votes.
 */
public class ElectionTimer {

    private final int minMs;
    private final int maxMs;

    public ElectionTimer(int minMs, int maxMs) {
        this.minMs = minMs;
        this.maxMs = maxMs;
    }

    /**
     * Generate a random election timeout.
     */
    public int randomTimeout() {
        return ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
    }

    public int getMinMs() { return minMs; }
    public int getMaxMs() { return maxMs; }
}
