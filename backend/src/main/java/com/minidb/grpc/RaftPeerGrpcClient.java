package com.minidb.grpc;

import com.minidb.config.RaftConfig;
import com.minidb.proto.*;
import com.minidb.raft.RaftLog;
import com.minidb.raft.RaftNode;
import com.minidb.raft.RaftPeerClient;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/**
 * gRPC client for communicating with Raft peers.
 */
@Component
public class RaftPeerGrpcClient implements RaftPeerClient {

    private static final Logger log = LoggerFactory.getLogger(RaftPeerGrpcClient.class);

    private final RaftConfig config;
    private final RaftNode raftNode;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, RaftServiceGrpc.RaftServiceFutureStub> stubs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RaftPeerGrpcClient(RaftConfig config, RaftNode raftNode) {
        this.config = config;
        this.raftNode = raftNode;
    }

    @PostConstruct
    public void init() {
        raftNode.setPeerClient(this);

        for (String peer : config.getPeers()) {
            try {
                connectToPeer(peer);
            } catch (Exception e) {
                log.warn("Failed to connect to peer {}: {}", peer, e.getMessage());
            }
        }
        log.info("Peer client initialized with {} peers", config.getPeers().size());
    }

    private void connectToPeer(String peer) {
        // peer format: "host:port" or "node-id@host:port"
        String address = peer.contains("@") ? peer.split("@")[1] : peer;
        String[] parts = address.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9090;

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(64 * 1024 * 1024) // 64MB for snapshots
                .build();

        channels.put(peer, channel);
        stubs.put(peer, RaftServiceGrpc.newFutureStub(channel));
        log.info("Connected to peer: {} at {}:{}", peer, host, port);
    }

    @Override
    public CompletableFuture<RaftNode.RequestVoteResult> requestVote(
            String peerId, long term, String candidateId,
            long lastLogIndex, long lastLogTerm) {

        RaftServiceGrpc.RaftServiceFutureStub stub = stubs.get(peerId);
        if (stub == null) {
            return CompletableFuture.completedFuture(null);
        }

        RequestVoteRequest request = RequestVoteRequest.newBuilder()
                .setTerm(term)
                .setCandidateId(candidateId)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        CompletableFuture<RaftNode.RequestVoteResult> future = new CompletableFuture<>();
        var grpcFuture = stub.requestVote(request);

        grpcFuture.addListener(() -> {
            try {
                RequestVoteResponse response = grpcFuture.get();
                future.complete(new RaftNode.RequestVoteResult(
                        response.getTerm(), response.getVoteGranted()));
            } catch (Exception e) {
                future.complete(null);
            }
        }, executor);

        return future;
    }

    @Override
    public CompletableFuture<RaftNode.AppendEntriesResult> appendEntries(
            String peerId, long term, String leaderId,
            long prevLogIndex, long prevLogTerm,
            List<RaftLog.Entry> entries, long leaderCommit) {

        RaftServiceGrpc.RaftServiceFutureStub stub = stubs.get(peerId);
        if (stub == null) {
            return CompletableFuture.completedFuture(null);
        }

        AppendEntriesRequest.Builder builder = AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId(leaderId)
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .setLeaderCommit(leaderCommit);

        for (RaftLog.Entry entry : entries) {
            CommandType protoType = switch (entry.type()) {
                case PUT -> CommandType.PUT;
                case DELETE -> CommandType.DELETE;
                case TXN_PREPARE -> CommandType.TXN_PREPARE;
                case TXN_COMMIT -> CommandType.TXN_COMMIT;
                case TXN_ABORT -> CommandType.TXN_ABORT;
                default -> CommandType.NOOP;
            };

            LogEntry.Builder entryBuilder = LogEntry.newBuilder()
                    .setIndex(entry.index())
                    .setTerm(entry.term())
                    .setCommandType(protoType)
                    .setKey(entry.key() != null ? entry.key() : "");

            if (entry.value() != null) {
                entryBuilder.setValue(ByteString.copyFrom(entry.value()));
            }

            builder.addEntries(entryBuilder.build());
        }

        CompletableFuture<RaftNode.AppendEntriesResult> future = new CompletableFuture<>();
        var grpcFuture = stub.appendEntries(builder.build());

        grpcFuture.addListener(() -> {
            try {
                AppendEntriesResponse response = grpcFuture.get();
                future.complete(new RaftNode.AppendEntriesResult(
                        response.getTerm(), response.getSuccess(), response.getMatchIndex()));
            } catch (Exception e) {
                future.complete(null);
            }
        }, executor);

        return future;
    }

    @Override
    public CompletableFuture<Long> installSnapshot(
            String peerId, long term, String leaderId,
            long lastIncludedIndex, long lastIncludedTerm,
            byte[] data, long offset, boolean done) {

        RaftServiceGrpc.RaftServiceFutureStub stub = stubs.get(peerId);
        if (stub == null) {
            return CompletableFuture.completedFuture(-1L);
        }

        InstallSnapshotRequest request = InstallSnapshotRequest.newBuilder()
                .setTerm(term)
                .setLeaderId(leaderId)
                .setLastIncludedIndex(lastIncludedIndex)
                .setLastIncludedTerm(lastIncludedTerm)
                .setOffset(offset)
                .setData(ByteString.copyFrom(data))
                .setDone(done)
                .build();

        CompletableFuture<Long> future = new CompletableFuture<>();
        var grpcFuture = stub.installSnapshot(request);

        grpcFuture.addListener(() -> {
            try {
                InstallSnapshotResponse response = grpcFuture.get();
                future.complete(response.getTerm());
            } catch (Exception e) {
                future.complete(-1L);
            }
        }, executor);

        return future;
    }

    @PreDestroy
    public void shutdown() {
        channels.values().forEach(ManagedChannel::shutdown);
        executor.shutdown();
    }
}
