import { useEffect, useRef, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useClusterStore } from '../stores/clusterStore';
import type { ClusterEvent } from '../types/events';

/** Build the WebSocket URL based on the selected node */
function getWsUrl(): string {
  const apiBase = useClusterStore.getState().apiBaseUrl;
  if (!apiBase) return '/ws/events'; // default proxy
  // apiBase looks like "http://localhost:8082/api" — strip "/api" and add "/ws/events"
  return apiBase.replace(/\/api$/, '/ws/events');
}

export function useWebSocket() {
  const clientRef = useRef<Client | null>(null);
  const addEvent = useClusterStore((s) => s.addEvent);
  const setConnected = useClusterStore((s) => s.setConnected);
  const apiBaseUrl = useClusterStore((s) => s.apiBaseUrl);

  const connect = useCallback(() => {
    if (clientRef.current?.active) {
      clientRef.current.deactivate();
    }

    const wsUrl = getWsUrl();
    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl) as any,
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('WebSocket connected');
        setConnected(true);

        // Subscribe to all event topics
        const topics = [
          '/topic/events',
          '/topic/raft',
          '/topic/replication',
          '/topic/kv',
          '/topic/heartbeat',
          '/topic/snapshot',
          '/topic/txn',
          '/topic/chaos',
        ];

        // Subscribe only to the catch-all topic to avoid duplicates
        client.subscribe('/topic/events', (message) => {
          try {
            const event: ClusterEvent = JSON.parse(message.body);
            addEvent(event);
          } catch (e) {
            console.warn('Failed to parse event:', e);
          }
        });
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected');
        setConnected(false);
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame.headers['message']);
        setConnected(false);
      },
    });

    client.activate();
    clientRef.current = client;
  }, [addEvent, setConnected, apiBaseUrl]);

  const disconnect = useCallback(() => {
    if (clientRef.current) {
      clientRef.current.deactivate();
      clientRef.current = null;
      setConnected(false);
    }
  }, [setConnected]);

  // Reconnect whenever the target node changes
  useEffect(() => {
    connect();
    return () => {
      disconnect();
    };
  }, [connect, disconnect, apiBaseUrl]);

  return { connect, disconnect };
}
