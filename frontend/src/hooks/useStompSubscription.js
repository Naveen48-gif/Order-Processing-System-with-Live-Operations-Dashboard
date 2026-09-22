import { useEffect, useRef, useState } from 'react';
import { createDashboardStompClient } from '../websocket/stompClient.js';

/**
 * Owns the STOMP client lifecycle (activate on mount, deactivate on unmount) and exposes the
 * live connection state so components can render a "Live"/"Connecting..." indicator.
 * @param {{onOrder, onInventory, onDashboard, onDlq}} handlers per-topic callbacks
 * @returns {{connected: boolean}}
 */
export function useStompSubscription({ onOrder, onInventory, onDashboard, onDlq }) {
  const [connected, setConnected] = useState(false);
  const handlersRef = useRef({ onOrder, onInventory, onDashboard, onDlq });
  handlersRef.current = { onOrder, onInventory, onDashboard, onDlq };

  useEffect(() => {
    const client = createDashboardStompClient({
      onOrder: (payload) => handlersRef.current.onOrder?.(payload),
      onInventory: (payload) => handlersRef.current.onInventory?.(payload),
      onDashboard: (payload) => handlersRef.current.onDashboard?.(payload),
      onDlq: (payload) => handlersRef.current.onDlq?.(payload),
      onConnected: () => setConnected(true),
      onDisconnected: () => setConnected(false),
    });
    client.activate();
    return () => client.deactivate();
  }, []);

  return { connected };
}
