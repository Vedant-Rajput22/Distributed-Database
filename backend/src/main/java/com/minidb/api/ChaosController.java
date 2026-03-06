package com.minidb.api;

import com.minidb.config.RaftConfig;
import com.minidb.events.ClusterEvent;
import com.minidb.events.EventBus;
import com.minidb.raft.RaftNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * REST controller for chaos engineering actions.
 * Forwards kill/recover to the target node's HTTP endpoint.
 */
@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final RaftNode raftNode;
    private final RaftConfig raftConfig;
    private final EventBus eventBus;
    private final HttpClient httpClient;
    private final int grpcPort;

    public ChaosController(RaftNode raftNode, RaftConfig raftConfig, EventBus eventBus,
                           @org.springframework.beans.factory.annotation.Value("${grpc.server.port:9090}") int grpcPort) {
        this.raftNode = raftNode;
        this.raftConfig = raftConfig;
        this.eventBus = eventBus;
        this.grpcPort = grpcPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Check if the given id refers to THIS node.
     * Handles "node-1" style, "localhost:9090" address-style, and "node-1@localhost:9090" full format.
     */
    private boolean isSelfNode(String id) {
        if (id.equals(raftNode.getNodeId())) return true;
        // Check if id matches our gRPC address
        String selfAddr = "localhost:" + grpcPort;
        if (id.equals(selfAddr) || id.equals("127.0.0.1:" + grpcPort)) return true;
        // Check if id is in "nodeId@host:port" format and the nodeId part matches
        if (id.contains("@")) {
            String idPart = id.split("@")[0];
            if (idPart.equals(raftNode.getNodeId())) return true;
        }
        return false;
    }

    /**
     * Resolve a node identifier (in any format) to its HTTP URL.
     * Matches against: display id (node-X), full peer string (node-X@host:port), 
     * and address part (host:port).
     * Falls back to parsing host:port directly if the id looks like an address.
     */
    private String getPeerHttpUrl(String nodeId) {
        // First, try matching against known peers
        for (String peer : raftConfig.getPeers()) {
            String peerDisplayId = peer.contains("@") ? peer.split("@")[0] : peer;
            String peerAddress = peer.contains("@") ? peer.split("@")[1] : peer;

            // Match by display ID, full string, or address
            if (peerDisplayId.equals(nodeId) || peer.equals(nodeId) || peerAddress.equals(nodeId)) {
                return addressToHttpUrl(peerAddress);
            }
        }

        // Fallback: if nodeId looks like host:port, compute HTTP URL directly
        if (nodeId.contains(":") && !nodeId.contains("@")) {
            try {
                return addressToHttpUrl(nodeId);
            } catch (Exception e) {
                log.warn("Failed to parse address from nodeId: {}", nodeId);
            }
        }

        return null;
    }

    private String addressToHttpUrl(String address) {
        String[] parts = address.split(":");
        String host = parts[0];
        int peerGrpcPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 9090;
        int httpPort = peerGrpcPort - 1010;
        return "http://" + host + ":" + httpPort;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> forwardToPeer(String nodeId, String path) {
        String url = getPeerHttpUrl(nodeId);
        if (url == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "Unknown node: " + nodeId
                    + " (known peers: " + raftConfig.getPeers() + ")");
            return err;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + path))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(response.body(), LinkedHashMap.class);
        } catch (Exception e) {
            log.warn("Failed to forward to {} at {}: {}", nodeId, url, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "Failed to forward to " + nodeId + ": " + e.getMessage());
            return err;
        }
    }

    /**
     * Resolve any form of node ID to the raw peer string used by RaftNode.
     * Returns the full peer string (e.g. "node-2@localhost:9092" or "localhost:9092").
     */
    private String resolvePeerString(String id) {
        for (String peer : raftConfig.getPeers()) {
            String displayId = peer.contains("@") ? peer.split("@")[0] : peer;
            String address = peer.contains("@") ? peer.split("@")[1] : peer;
            if (displayId.equals(id) || peer.equals(id) || address.equals(id)) {
                return peer;
            }
        }
        return id; // fallback to the original
    }

    @PostMapping("/kill-node/{id}")
    public Map<String, Object> killNode(@PathVariable String id) {
        if (!isSelfNode(id)) {
            Map<String, Object> result = forwardToPeer(id, "/api/chaos/kill-node/" + id);
            // Immediately mark the peer as dead on this node so topology updates right away
            if (Boolean.TRUE.equals(result.get("success"))) {
                String peerString = resolvePeerString(id);
                raftNode.markPeerDead(peerString);
                // Broadcast death to all OTHER peers so their topology updates too
                broadcastPeerStatus(id, false);
            }
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raftNode.kill();
        result.put("success", true);
        result.put("message", "Node " + id + " killed");
        eventBus.publish(ClusterEvent.chaosAction("KILL", id));
        return result;
    }

    @PostMapping("/recover/{id}")
    public Map<String, Object> recoverNode(@PathVariable String id) {
        if (!isSelfNode(id)) {
            Map<String, Object> result = forwardToPeer(id, "/api/chaos/recover/" + id);
            if (Boolean.TRUE.equals(result.get("success"))) {
                String peerString = resolvePeerString(id);
                raftNode.markPeerAlive(peerString);
                // Broadcast recovery to all OTHER peers
                broadcastPeerStatus(id, true);
            }
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raftNode.recover();
        result.put("success", true);
        result.put("message", "Node " + id + " recovered");
        eventBus.publish(ClusterEvent.chaosAction("RECOVER", id));
        return result;
    }

    @PostMapping("/partition/{id}")
    public Map<String, Object> partitionNode(@PathVariable String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        String peerString = resolvePeerString(id);
        raftNode.addPartition(peerString);
        result.put("success", true);
        result.put("message", "Network partition added for node " + id);
        eventBus.publish(ClusterEvent.chaosAction("PARTITION", id));
        return result;
    }

    @PostMapping("/heal-partition/{id}")
    public Map<String, Object> healPartition(@PathVariable String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        String peerString = resolvePeerString(id);
        raftNode.removePartition(peerString);
        result.put("success", true);
        result.put("message", "Network partition healed for node " + id);
        eventBus.publish(ClusterEvent.chaosAction("HEAL_PARTITION", id));
        return result;
    }

    /**
     * Notification endpoint: another node tells us a peer is dead/alive.
     * This allows follower nodes to update their topology view.
     */
    @PostMapping("/notify-peer-status/{id}")
    public Map<String, Object> notifyPeerStatus(@PathVariable String id, @RequestParam boolean alive) {
        Map<String, Object> result = new LinkedHashMap<>();
        String peerString = resolvePeerString(id);
        if (alive) {
            raftNode.markPeerAlive(peerString);
        } else {
            raftNode.markPeerDead(peerString);
        }
        result.put("success", true);
        result.put("message", "Peer " + id + " marked as " + (alive ? "alive" : "dead"));
        return result;
    }

    /**
     * Broadcast a peer's status (dead/alive) to all other peers in the cluster.
     * This ensures follower nodes update their topology even though they don't send heartbeats.
     */
    private void broadcastPeerStatus(String deadOrAliveNodeId, boolean alive) {
        for (String peer : raftConfig.getPeers()) {
            String peerDisplayId = peer.contains("@") ? peer.split("@")[0] : peer;
            // Don't notify the dead/alive node itself, and don't notify ourselves
            if (peerDisplayId.equals(deadOrAliveNodeId) || isSelfNode(peerDisplayId)) continue;
            try {
                forwardToPeer(peerDisplayId,
                        "/api/chaos/notify-peer-status/" + deadOrAliveNodeId + "?alive=" + alive);
            } catch (Exception e) {
                log.debug("Failed to notify {} about {} status: {}", peerDisplayId, deadOrAliveNodeId, e.getMessage());
            }
        }
    }

    @PostMapping("/recover-all")
    public Map<String, Object> recoverAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        // Recover local node
        raftNode.recover();
        raftNode.getPartitionedPeers().forEach(raftNode::removePartition);

        // Mark all peers alive locally
        for (String peer : raftConfig.getPeers()) {
            raftNode.markPeerAlive(peer);
        }

        // Also forward recover to all peers
        List<String> peerResults = new ArrayList<>();
        for (String peer : raftConfig.getPeers()) {
            String peerDisplayId = peer.contains("@") ? peer.split("@")[0] : peer;
            try {
                Map<String, Object> peerResult = forwardToPeer(peerDisplayId,
                        "/api/chaos/recover/" + peerDisplayId);
                peerResults.add(peerDisplayId + ": " +
                        (Boolean.TRUE.equals(peerResult.get("success")) ? "OK" : "FAILED"));
            } catch (Exception e) {
                peerResults.add(peerDisplayId + ": ERROR");
            }
        }

        // Broadcast that all nodes (including self) are alive to every peer
        for (String peer : raftConfig.getPeers()) {
            String peerDisplayId = peer.contains("@") ? peer.split("@")[0] : peer;
            try {
                // Tell each peer that the local node is alive
                forwardToPeer(peerDisplayId,
                        "/api/chaos/notify-peer-status/" + raftNode.getNodeId() + "?alive=true");
                // Tell each peer about every OTHER peer being alive
                for (String otherPeer : raftConfig.getPeers()) {
                    String otherDisplayId = otherPeer.contains("@") ? otherPeer.split("@")[0] : otherPeer;
                    if (!otherDisplayId.equals(peerDisplayId)) {
                        forwardToPeer(peerDisplayId,
                                "/api/chaos/notify-peer-status/" + otherDisplayId + "?alive=true");
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to broadcast recover-all to {}: {}", peerDisplayId, e.getMessage());
            }
        }

        result.put("success", true);
        result.put("message", "All nodes recovered, all partitions healed");
        result.put("peerResults", peerResults);
        eventBus.publish(ClusterEvent.chaosAction("RECOVER_ALL", raftNode.getNodeId()));
        return result;
    }
}
