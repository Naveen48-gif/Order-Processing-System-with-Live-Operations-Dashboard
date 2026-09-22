// STOMP-over-native-WebSocket connection factory, per AGENT.md §5.
import { Client } from '@stomp/stompjs';

const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws';

/**
 * Opens a STOMP client subscribed to the four live-dashboard topics. Every push is a full
 * snapshot object (never a delta), so callers only need to upsert-by-id into local state.
 * @returns {import('@stomp/stompjs').Client} the (already activating) client
 */
export function createDashboardStompClient({ onOrder, onInventory, onDashboard, onDlq, onConnected, onDisconnected }) {
  const client = new Client({
    brokerURL: WS_URL,
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      onConnected?.();
      client.subscribe('/topic/orders', (msg) => onOrder?.(JSON.parse(msg.body)));
      client.subscribe('/topic/inventory', (msg) => onInventory?.(JSON.parse(msg.body)));
      client.subscribe('/topic/dashboard', (msg) => onDashboard?.(JSON.parse(msg.body)));
      client.subscribe('/topic/dlq', (msg) => onDlq?.(JSON.parse(msg.body)));
    },
    onWebSocketClose: () => onDisconnected?.(),
    onStompError: () => onDisconnected?.(),
  });
  return client;
}
