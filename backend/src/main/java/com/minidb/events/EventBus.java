package com.minidb.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Internal event pub/sub system. Captures all significant cluster events
 * for the dashboard UI and observability.
 */
@Service
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    private static final int MAX_HISTORY = 10000;

    private final List<Consumer<ClusterEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Deque<ClusterEvent> eventHistory = new ConcurrentLinkedDeque<>();

    /**
     * Publish an event to all listeners.
     */
    public void publish(ClusterEvent event) {
        eventHistory.addFirst(event);
        while (eventHistory.size() > MAX_HISTORY) {
            eventHistory.removeLast();
        }

        for (Consumer<ClusterEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("Event listener failed", e);
            }
        }
    }

    /**
     * Subscribe to events.
     */
    public void subscribe(Consumer<ClusterEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Get recent event history.
     */
    public List<ClusterEvent> getRecentEvents(int limit) {
        List<ClusterEvent> result = new ArrayList<>();
        int count = 0;
        for (ClusterEvent event : eventHistory) {
            result.add(event);
            if (++count >= limit) break;
        }
        return result;
    }

    /**
     * Get events filtered by category.
     */
    public List<ClusterEvent> getEventsByCategory(ClusterEvent.Category category, int limit) {
        List<ClusterEvent> result = new ArrayList<>();
        for (ClusterEvent event : eventHistory) {
            if (event.getCategory() == category) {
                result.add(event);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    /**
     * Search events by message text.
     */
    public List<ClusterEvent> searchEvents(String query, int limit) {
        String lowerQuery = query.toLowerCase();
        List<ClusterEvent> result = new ArrayList<>();
        for (ClusterEvent event : eventHistory) {
            if (event.getMessage().toLowerCase().contains(lowerQuery)) {
                result.add(event);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }
}
