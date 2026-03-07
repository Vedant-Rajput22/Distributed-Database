package com.minidb.config;

import com.minidb.raft.RaftNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Intercepts HTTP requests and returns 503 Service Unavailable when the node is "killed".
 *
 * <p>This makes the kill simulation realistic — a dead server cannot serve any data
 * requests. The only endpoints that remain accessible on a killed node are:
 * <ul>
 *     <li>/api/chaos/** — so the dashboard/demo can recover the node</li>
 *     <li>/api/cluster/status — so clients can detect the killed state</li>
 * </ul>
 *
 * <p>This mirrors real-world behavior: when a server crashes, all client requests
 * get connection-refused / timeout, forcing clients to fail over to another node.
 */
@Component
public class KilledNodeInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(KilledNodeInterceptor.class);
    private final RaftNode raftNode;

    public KilledNodeInterceptor(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // Always allow CORS preflight requests (browser sends OPTIONS before cross-origin fetches)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (!raftNode.isKilled()) {
            return true; // Node is alive — allow all requests
        }

        String path = request.getRequestURI();

        // Always allow chaos endpoints (kill/recover) and cluster status
        if (path.startsWith("/api/chaos") || path.equals("/api/cluster/status")) {
            return true;
        }

        // Node is killed — reject everything else with 503
        log.debug("[{}] Rejecting request to {} — node is killed", raftNode.getNodeId(), path);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Node is killed (simulated crash)\",\"killed\":true,\"nodeId\":\""
                        + raftNode.getNodeId() + "\"}"
        );
        return false;
    }
}
