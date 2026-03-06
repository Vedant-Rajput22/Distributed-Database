package com.minidb.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Publishes cluster events to WebSocket topics for the dashboard UI.
 */
@Component
public class WebSocketPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final EventBus eventBus;

    public WebSocketPublisher(SimpMessagingTemplate messagingTemplate, EventBus eventBus) {
        this.messagingTemplate = messagingTemplate;
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(this::publishEvent);
        log.info("WebSocket publisher initialized");
    }

    private void publishEvent(ClusterEvent event) {
        try {
            // Publish to category-specific topic
            String topic = switch (event.getCategory()) {
                case RAFT -> "/topic/raft";
                case REPLICATION -> "/topic/replication";
                case KV -> "/topic/kv";
                case HEARTBEAT -> "/topic/heartbeat";
                case SNAPSHOT -> "/topic/snapshot";
                case TXN -> "/topic/txn";
                case MVCC -> "/topic/mvcc";
                case CHAOS -> "/topic/chaos";
            };
            messagingTemplate.convertAndSend(topic, event);

            // Also publish to catch-all topic
            messagingTemplate.convertAndSend("/topic/events", event);
        } catch (Exception e) {
            log.debug("Failed to publish event via WebSocket: {}", e.getMessage());
        }
    }
}
