package com.minidb.grpc;

import com.minidb.raft.RaftLog;
import com.minidb.raft.RaftNode;
import com.minidb.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * gRPC service implementation for Raft consensus RPCs.
 */
@GrpcService
public class RaftGrpcService extends RaftServiceGrpc.RaftServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(RaftGrpcService.class);

    private final RaftNode raftNode;

    public RaftGrpcService(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @Override
    public void requestVote(RequestVoteRequest request,
                            StreamObserver<RequestVoteResponse> responseObserver) {
        // Simulate crash — killed nodes don't respond at all
        if (raftNode.isKilled()) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Node is killed (simulated)")
                    .asRuntimeException());
            return;
        }

        RaftNode.RequestVoteResult result = raftNode.handleRequestVote(
                request.getTerm(),
                request.getCandidateId(),
                request.getLastLogIndex(),
                request.getLastLogTerm());

        responseObserver.onNext(RequestVoteResponse.newBuilder()
                .setTerm(result.term())
                .setVoteGranted(result.voteGranted())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void appendEntries(AppendEntriesRequest request,
                              StreamObserver<AppendEntriesResponse> responseObserver) {
        // Simulate crash — killed nodes don't respond at all
        if (raftNode.isKilled()) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Node is killed (simulated)")
                    .asRuntimeException());
            return;
        }
        // Convert proto entries to internal format
        List<RaftLog.Entry> entries = new ArrayList<>();
        for (LogEntry protoEntry : request.getEntriesList()) {
            RaftLog.CommandType type = switch (protoEntry.getCommandType()) {
                case PUT -> RaftLog.CommandType.PUT;
                case DELETE -> RaftLog.CommandType.DELETE;
                case TXN_PREPARE -> RaftLog.CommandType.TXN_PREPARE;
                case TXN_COMMIT -> RaftLog.CommandType.TXN_COMMIT;
                case TXN_ABORT -> RaftLog.CommandType.TXN_ABORT;
                default -> RaftLog.CommandType.NOOP;
            };

            entries.add(new RaftLog.Entry(
                    protoEntry.getIndex(),
                    protoEntry.getTerm(),
                    type,
                    protoEntry.getKey(),
                    protoEntry.getValue().toByteArray()));
        }

        RaftNode.AppendEntriesResult result = raftNode.handleAppendEntries(
                request.getTerm(),
                request.getLeaderId(),
                request.getPrevLogIndex(),
                request.getPrevLogTerm(),
                entries,
                request.getLeaderCommit());

        responseObserver.onNext(AppendEntriesResponse.newBuilder()
                .setTerm(result.term())
                .setSuccess(result.success())
                .setMatchIndex(result.matchIndex())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void installSnapshot(InstallSnapshotRequest request,
                                StreamObserver<InstallSnapshotResponse> responseObserver) {
        // Simulate crash — killed nodes don't respond at all
        if (raftNode.isKilled()) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Node is killed (simulated)")
                    .asRuntimeException());
            return;
        }

        long resultTerm = raftNode.handleInstallSnapshot(
                request.getTerm(),
                request.getLeaderId(),
                request.getLastIncludedIndex(),
                request.getLastIncludedTerm(),
                request.getData().toByteArray(),
                request.getOffset(),
                request.getDone());

        responseObserver.onNext(InstallSnapshotResponse.newBuilder()
                .setTerm(resultTerm)
                .build());
        responseObserver.onCompleted();
    }
}
