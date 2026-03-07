package com.minidb.grpc;

import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.mvcc.MvccStore;
import com.minidb.raft.RaftNode;
import com.minidb.speculation.SpeculativeManager;
import com.minidb.proto.*;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * gRPC service implementation for the Key-Value API.
 * Routes Put/Delete through Raft consensus, serves reads from MVCC store.
 */
@GrpcService
public class KvGrpcService extends KvServiceGrpc.KvServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(KvGrpcService.class);

    private final RaftNode raftNode;
    private final EventBus eventBus;
    private final SpeculativeManager speculativeManager;
    private final Counter kvOpsCounter;
    private final Timer kvLatencyTimer;

    public KvGrpcService(RaftNode raftNode, EventBus eventBus,
                         SpeculativeManager speculativeManager,
                         MeterRegistry meterRegistry) {
        this.raftNode = raftNode;
        this.eventBus = eventBus;
        this.speculativeManager = speculativeManager;
        this.kvOpsCounter = Counter.builder("kv_operations_total")
                .description("Total KV operations")
                .register(meterRegistry);
        this.kvLatencyTimer = Timer.builder("kv_operation_latency_seconds")
                .description("KV operation latency")
                .register(meterRegistry);
    }

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
        long start = System.currentTimeMillis();
        kvOpsCounter.increment();

        if (!raftNode.isLeader()) {
            responseObserver.onNext(PutResponse.newBuilder()
                    .setSuccess(false)
                    .setError("Not the leader. Leader: " + raftNode.getLeaderId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        raftNode.submitPut(request.getKey(), request.getValue().toByteArray())
                .thenAccept(success -> {
                    long latency = System.currentTimeMillis() - start;
                    eventBus.publish(ClusterEvent.kvOperation("PUT", request.getKey(), latency, success));

                    responseObserver.onNext(PutResponse.newBuilder()
                            .setSuccess(success)
                            .setTimestamp(System.currentTimeMillis())
                            .build());
                    responseObserver.onCompleted();
                })
                .exceptionally(e -> {
                    long latency = System.currentTimeMillis() - start;
                    eventBus.publish(ClusterEvent.kvOperation("PUT", request.getKey(), latency, false));

                    responseObserver.onNext(PutResponse.newBuilder()
                            .setSuccess(false)
                            .setError(e.getMessage())
                            .build());
                    responseObserver.onCompleted();
                    return null;
                });
    }

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        long start = System.currentTimeMillis();
        kvOpsCounter.increment();

        try {
            MvccStore mvccStore = raftNode.getMvccStore();
            MvccStore.MvccResult result;

            if (request.getTimestamp() > 0) {
                result = mvccStore.get(request.getKey(), request.getTimestamp());
            } else {
                result = mvccStore.get(request.getKey());
            }

            long latency = System.currentTimeMillis() - start;
            eventBus.publish(ClusterEvent.kvOperation("GET", request.getKey(), latency, result.isFound()));

            GetResponse.Builder builder = GetResponse.newBuilder()
                    .setFound(result.isFound());
            if (result.isFound()) {
                builder.setValue(com.google.protobuf.ByteString.copyFrom(result.getValue()))
                       .setTimestamp(result.getTimestamp());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(GetResponse.newBuilder()
                    .setFound(false)
                    .setError(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void consistentGet(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        long start = System.currentTimeMillis();
        kvOpsCounter.increment();

        raftNode.consistentGet(request.getKey())
                .thenAccept(result -> {
                    long latency = System.currentTimeMillis() - start;
                    eventBus.publish(ClusterEvent.kvOperation("CONSISTENT_GET",
                            request.getKey(), latency, result.isFound()));

                    GetResponse.Builder builder = GetResponse.newBuilder()
                            .setFound(result.isFound());
                    if (result.isFound()) {
                        builder.setValue(com.google.protobuf.ByteString.copyFrom(result.getValue()))
                               .setTimestamp(result.getTimestamp());
                    }
                    responseObserver.onNext(builder.build());
                    responseObserver.onCompleted();
                })
                .exceptionally(e -> {
                    responseObserver.onNext(GetResponse.newBuilder()
                            .setFound(false)
                            .setError(e.getMessage())
                            .build());
                    responseObserver.onCompleted();
                    return null;
                });
    }

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
        long start = System.currentTimeMillis();
        kvOpsCounter.increment();

        if (!raftNode.isLeader()) {
            responseObserver.onNext(DeleteResponse.newBuilder()
                    .setSuccess(false)
                    .setError("Not the leader. Leader: " + raftNode.getLeaderId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        raftNode.submitDelete(request.getKey())
                .thenAccept(success -> {
                    long latency = System.currentTimeMillis() - start;
                    eventBus.publish(ClusterEvent.kvOperation("DELETE", request.getKey(), latency, success));

                    responseObserver.onNext(DeleteResponse.newBuilder()
                            .setSuccess(success)
                            .build());
                    responseObserver.onCompleted();
                })
                .exceptionally(e -> {
                    responseObserver.onNext(DeleteResponse.newBuilder()
                            .setSuccess(false)
                            .setError(e.getMessage())
                            .build());
                    responseObserver.onCompleted();
                    return null;
                });
    }

    @Override
    public void scan(ScanRequest request, StreamObserver<ScanResponse> responseObserver) {
        long start = System.currentTimeMillis();
        kvOpsCounter.increment();

        try {
            MvccStore mvccStore = raftNode.getMvccStore();
            long ts = request.getTimestamp() > 0 ? request.getTimestamp() : Long.MAX_VALUE;
            List<MvccStore.MvccResult> results = mvccStore.scan(
                    request.getStartKey(), request.getEndKey(),
                    request.getLimit(), ts);

            ScanResponse.Builder builder = ScanResponse.newBuilder();
            for (MvccStore.MvccResult result : results) {
                builder.addPairs(KvPair.newBuilder()
                        .setKey(result.getKey())
                        .setValue(com.google.protobuf.ByteString.copyFrom(result.getValue()))
                        .setTimestamp(result.getTimestamp())
                        .build());
            }

            long latency = System.currentTimeMillis() - start;
            eventBus.publish(ClusterEvent.kvOperation("SCAN",
                    request.getStartKey() + "→" + request.getEndKey(), latency, true));

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(ScanResponse.newBuilder()
                    .setError(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ================================================================
    // Speculative MVCC Consensus gRPC Handlers
    // ================================================================

    @Override
    public void speculativePut(SpeculativePutRequest request,
                                StreamObserver<SpeculativePutResponse> responseObserver) {
        kvOpsCounter.increment();

        if (!raftNode.isLeader()) {
            responseObserver.onNext(SpeculativePutResponse.newBuilder()
                    .setSuccess(false)
                    .setError("Not the leader. Leader: " + raftNode.getLeaderId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        SpeculativeManager.SpeculativeResult result =
                speculativeManager.submitSpeculativePut(raftNode,
                        request.getKey(), request.getValue().toByteArray());

        SpeculativePutResponse.Builder builder = SpeculativePutResponse.newBuilder()
                .setSuccess(result.success())
                .setSpeculative(result.speculative())
                .setWriteLatencyMs(result.writeLatencyMs());

        if (result.success()) {
            builder.setTimestamp(result.timestamp())
                   .setRaftLogIndex(result.raftLogIndex());
        } else {
            builder.setError(result.error());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void speculativeDelete(SpeculativeDeleteRequest request,
                                   StreamObserver<SpeculativePutResponse> responseObserver) {
        kvOpsCounter.increment();

        if (!raftNode.isLeader()) {
            responseObserver.onNext(SpeculativePutResponse.newBuilder()
                    .setSuccess(false)
                    .setError("Not the leader. Leader: " + raftNode.getLeaderId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        SpeculativeManager.SpeculativeResult result =
                speculativeManager.submitSpeculativeDelete(raftNode, request.getKey());

        SpeculativePutResponse.Builder builder = SpeculativePutResponse.newBuilder()
                .setSuccess(result.success())
                .setSpeculative(result.speculative())
                .setWriteLatencyMs(result.writeLatencyMs());

        if (result.success()) {
            builder.setTimestamp(result.timestamp())
                   .setRaftLogIndex(result.raftLogIndex());
        } else {
            builder.setError(result.error());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
