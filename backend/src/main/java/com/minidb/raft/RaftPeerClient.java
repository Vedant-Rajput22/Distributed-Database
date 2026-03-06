package com.minidb.raft;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for communicating with Raft peers via gRPC.
 */
public interface RaftPeerClient {

    /**
     * Send RequestVote RPC to a peer.
     */
    CompletableFuture<RaftNode.RequestVoteResult> requestVote(
            String peerId, long term, String candidateId,
            long lastLogIndex, long lastLogTerm);

    /**
     * Send AppendEntries RPC to a peer.
     */
    CompletableFuture<RaftNode.AppendEntriesResult> appendEntries(
            String peerId, long term, String leaderId,
            long prevLogIndex, long prevLogTerm,
            List<RaftLog.Entry> entries, long leaderCommit);

    /**
     * Send InstallSnapshot RPC to a peer.
     */
    CompletableFuture<Long> installSnapshot(
            String peerId, long term, String leaderId,
            long lastIncludedIndex, long lastIncludedTerm,
            byte[] data, long offset, boolean done);
}
