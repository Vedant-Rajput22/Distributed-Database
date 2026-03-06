package com.minidb.api;

import com.minidb.config.RaftConfig;
import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.mvcc.MvccStore;
import com.minidb.raft.RaftNode;
import com.minidb.txn.TxnCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * REST controller for KV operations from the dashboard UI.
 * Automatically forwards writes to the Raft leader.
 */
@RestController
@RequestMapping("/api/kv")
public class KvController {

    private static final Logger log = LoggerFactory.getLogger(KvController.class);

    private final RaftNode raftNode;
    private final RaftConfig raftConfig;
    private final TxnCoordinator txnCoordinator;
    private final EventBus eventBus;
    private final HttpClient httpClient;

    public KvController(RaftNode raftNode, RaftConfig raftConfig,
                        TxnCoordinator txnCoordinator, EventBus eventBus) {
        this.raftNode = raftNode;
        this.raftConfig = raftConfig;
        this.txnCoordinator = txnCoordinator;
        this.eventBus = eventBus;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Resolve the HTTP base URL for the leader node by matching leader ID
     * to the peer config format "node-id@host:grpcPort".
     * Convention: HTTP port = gRPC port - 1010 (9090→8080, 9092→8082, 9094→8084).
     */
    private String getLeaderHttpUrl() {
        String leaderId = raftNode.getLeaderId();
        if (leaderId == null) return null;
        for (String peer : raftConfig.getPeers()) {
            String peerNodeId = peer.contains("@") ? peer.split("@")[0] : peer;
            if (peerNodeId.equals(leaderId)) {
                String address = peer.contains("@") ? peer.split("@")[1] : peer;
                String[] parts = address.split(":");
                String host = parts[0];
                int grpcPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 9090;
                int httpPort = grpcPort - 1010;
                return "http://" + host + ":" + httpPort;
            }
        }
        return null;
    }

    /**
     * Forward a POST request body to the leader's endpoint.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> forwardToLeader(String path, String jsonBody) {
        String leaderUrl = getLeaderHttpUrl();
        if (leaderUrl == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "No leader available");
            return err;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(leaderUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // Parse JSON response manually (avoid extra dependency)
            return parseJson(response.body());
        } catch (Exception e) {
            log.warn("Failed to forward to leader at {}: {}", leaderUrl, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "Failed to forward to leader: " + e.getMessage());
            return err;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("raw", json);
            return result;
        }
    }

    @PostMapping("/put")
    public Map<String, Object> put(@RequestBody Map<String, String> request) {
        if (!raftNode.isLeader()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return forwardToLeader("/api/kv/put", mapper.writeValueAsString(request));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", "Forward failed: " + e.getMessage());
                return err;
            }
        }

        long start = System.currentTimeMillis();
        String key = request.get("key");
        String value = request.get("value");

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Boolean committed = raftNode.submitPut(key, value.getBytes(StandardCharsets.UTF_8))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);

            long latency = System.currentTimeMillis() - start;
            result.put("success", committed);
            result.put("latencyMs", latency);
            eventBus.publish(ClusterEvent.kvOperation("PUT", key, latency, committed));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("latencyMs", latency);
            eventBus.publish(ClusterEvent.kvOperation("PUT", key, latency, false));
        }
        return result;
    }

    @PostMapping("/get")
    public Map<String, Object> get(@RequestBody Map<String, Object> request) {
        long start = System.currentTimeMillis();
        String key = (String) request.get("key");
        Long timestamp = request.containsKey("timestamp") ?
                ((Number) request.get("timestamp")).longValue() : 0L;

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            MvccStore.MvccResult mvccResult;
            if (timestamp > 0) {
                mvccResult = raftNode.getMvccStore().get(key, timestamp);
            } else {
                mvccResult = raftNode.getMvccStore().get(key);
            }

            long latency = System.currentTimeMillis() - start;
            result.put("found", mvccResult.isFound());
            if (mvccResult.isFound()) {
                result.put("value", mvccResult.getValueAsString());
                result.put("timestamp", mvccResult.getTimestamp());
            }
            result.put("latencyMs", latency);
            eventBus.publish(ClusterEvent.kvOperation("GET", key, latency, mvccResult.isFound()));
        } catch (Exception e) {
            result.put("found", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody Map<String, String> request) {
        if (!raftNode.isLeader()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return forwardToLeader("/api/kv/delete", mapper.writeValueAsString(request));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", "Forward failed: " + e.getMessage());
                return err;
            }
        }

        long start = System.currentTimeMillis();
        String key = request.get("key");

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Boolean committed = raftNode.submitDelete(key)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);

            long latency = System.currentTimeMillis() - start;
            result.put("success", committed);
            result.put("latencyMs", latency);
            eventBus.publish(ClusterEvent.kvOperation("DELETE", key, latency, committed));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("error", e.getMessage());
            eventBus.publish(ClusterEvent.kvOperation("DELETE", key, latency, false));
        }
        return result;
    }

    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestBody Map<String, Object> request) {
        long start = System.currentTimeMillis();
        // Accept both "prefix"/"startKey" from frontend
        String startKey = (String) request.getOrDefault("prefix",
                request.getOrDefault("startKey", ""));
        String endKey = (String) request.getOrDefault("endKey", null);
        int limit = request.containsKey("limit") ?
                ((Number) request.get("limit")).intValue() : 100;
        long timestamp = request.containsKey("timestamp") ?
                ((Number) request.get("timestamp")).longValue() : 0L;

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<MvccStore.MvccResult> results = raftNode.getMvccStore()
                    .scan(startKey, endKey, limit, timestamp);

            List<Map<String, Object>> pairs = new ArrayList<>();
            for (MvccStore.MvccResult r : results) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("key", r.getKey());
                pair.put("value", r.getValueAsString());
                pair.put("timestamp", r.getTimestamp());
                pairs.add(pair);
            }

            long latency = System.currentTimeMillis() - start;
            result.put("pairs", pairs);
            result.put("count", pairs.size());
            result.put("latencyMs", latency);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/versions/{key}")
    public Map<String, Object> getVersionHistory(@PathVariable String key) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<MvccStore.MvccResult> versions = raftNode.getMvccStore().getVersionHistory(key);
            List<Map<String, Object>> versionList = new ArrayList<>();
            int versionNum = versions.size();
            for (MvccStore.MvccResult v : versions) {
                Map<String, Object> ver = new LinkedHashMap<>();
                ver.put("version", versionNum--);
                ver.put("timestamp", v.getTimestamp());
                ver.put("value", v.getValueAsString());
                ver.put("tombstone", v.isTombstone());
                versionList.add(ver);
            }
            result.put("key", key);
            result.put("versions", versionList);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/txn")
    public Map<String, Object> executeTransaction(@RequestBody Map<String, Object> request) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> ops = (List<Map<String, String>>) request.get("operations");
            List<TxnCoordinator.TxnOperation> operations = new ArrayList<>();

            for (Map<String, String> op : ops) {
                String opType = op.get("type");
                String key = op.get("key");
                byte[] value = op.containsKey("value") ?
                        op.get("value").getBytes(StandardCharsets.UTF_8) : null;

                TxnCoordinator.TxnOperation.OpType type =
                        "DELETE".equalsIgnoreCase(opType) ?
                                TxnCoordinator.TxnOperation.OpType.DELETE :
                                TxnCoordinator.TxnOperation.OpType.PUT;

                operations.add(new TxnCoordinator.TxnOperation(key, value, type));
            }

            Boolean committed = txnCoordinator.execute(operations)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);

            long latency = System.currentTimeMillis() - start;
            result.put("success", committed);
            result.put("latencyMs", latency);
            result.put("operationCount", operations.size());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
