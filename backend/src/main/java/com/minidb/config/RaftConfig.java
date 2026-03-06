package com.minidb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class RaftConfig {

    @Value("${minidb.node.id:node-1}")
    private String nodeId;

    @Value("${minidb.cluster.peers:}")
    private List<String> peers;

    @Value("${minidb.raft.election-timeout-min:3000}")
    private int electionTimeoutMin;

    @Value("${minidb.raft.election-timeout-max:5000}")
    private int electionTimeoutMax;

    @Value("${minidb.raft.heartbeat-interval:1000}")
    private int heartbeatInterval;

    @Value("${minidb.raft.snapshot-interval:1000}")
    private int snapshotInterval;

    @Value("${minidb.mvcc.retention-ms:300000}")
    private long mvccRetentionMs;

    @Value("${minidb.mvcc.gc-interval-ms:60000}")
    private long mvccGcIntervalMs;

    public String getNodeId() { return nodeId; }
    public List<String> getPeers() { return peers; }
    public int getElectionTimeoutMin() { return electionTimeoutMin; }
    public int getElectionTimeoutMax() { return electionTimeoutMax; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public int getSnapshotInterval() { return snapshotInterval; }
    public long getMvccRetentionMs() { return mvccRetentionMs; }
    public long getMvccGcIntervalMs() { return mvccGcIntervalMs; }
}
