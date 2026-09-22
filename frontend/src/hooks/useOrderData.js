import { useEffect, useMemo, useState } from 'react';
import {
  fetchProducts,
  fetchInventory,
  fetchOrders,
  fetchDeadLetters,
  fetchDashboardSummary,
} from '../services/api.js';
import { useStompSubscription } from './useStompSubscription.js';

function upsertBy(key) {
  return (list, item) => {
    const idx = list.findIndex((x) => x[key] === item[key]);
    if (idx === -1) return [item, ...list];
    const next = [...list];
    next[idx] = item;
    return next;
  };
}

const upsertOrder = upsertBy('id');
const upsertInventory = upsertBy('productId');

/**
 * Single source of truth for order/inventory/DLQ/summary state, shared by the storefront and admin
 * views so neither page can drift from what the STOMP feed is actually broadcasting.
 */
export function useOrderData() {
  const [products, setProducts] = useState([]);
  const [inventory, setInventory] = useState([]);
  const [orders, setOrders] = useState([]);
  const [deadLetters, setDeadLetters] = useState([]);
  const [summary, setSummary] = useState(null);
  const [loadError, setLoadError] = useState(null);

  useEffect(() => {
    Promise.all([fetchProducts(), fetchInventory(), fetchOrders(), fetchDeadLetters(), fetchDashboardSummary()])
      .then(([p, inv, o, d, s]) => {
        setProducts(p);
        setInventory(inv);
        setOrders(o);
        setDeadLetters(d);
        setSummary(s);
      })
      .catch((err) => setLoadError(err.message));
  }, []);

  const { connected } = useStompSubscription({
    onOrder: (order) => setOrders((prev) => upsertOrder(prev, order)),
    onInventory: (inv) => setInventory((prev) => upsertInventory(prev, inv)),
    onDashboard: (s) => setSummary(s),
    onDlq: (entry) =>
      setDeadLetters((prev) => {
        const idx = prev.findIndex((e) => e.orderId === entry.orderId);
        if (idx === -1) return [entry, ...prev];
        const next = [...prev];
        next[idx] = entry;
        return next;
      }),
  });

  // OrderForm/ChaosPanel need both catalog data (name/price) and live stock (quantity) per product.
  const productsWithStock = useMemo(() => {
    const stockByProductId = new Map(inventory.map((i) => [i.productId, i.quantity]));
    return products.map((p) => ({ ...p, quantity: stockByProductId.get(p.id) }));
  }, [products, inventory]);

  return {
    products,
    setProducts,
    inventory,
    setInventory,
    orders,
    setOrders,
    deadLetters,
    setDeadLetters,
    summary,
    connected,
    loadError,
    productsWithStock,
    upsertOrder,
  };
}
