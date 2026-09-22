import { useEffect, useState } from 'react';
import InventoryTable from '../components/InventoryTable.jsx';
import OrdersTable from '../components/OrdersTable.jsx';
import DeadLetterTable from '../components/DeadLetterTable.jsx';
import DashboardSummary from '../components/DashboardSummary.jsx';
import ProductForm from '../components/ProductForm.jsx';
import ChaosPanel from '../components/ChaosPanel.jsx';
import { useOrderData } from '../hooks/useOrderData.js';
import {
  createProduct,
  fetchInventory,
  restockProduct,
  submitOrderBurst,
  retryDeadLetterOrder,
  armFaultInjection,
  clearFaultInjection,
  fetchOpsCapabilities,
} from '../services/api.js';
import '../styles/App.css';

/**
 * Operator/admin console: catalogue management, inventory restock, the full order feed, the
 * dead-letter queue with manual replay, and the load/failure drills. None of this is reachable from
 * the storefront — this is a separate route, not a toggle on the customer page.
 */
export default function AdminPage() {
  const {
    inventory,
    setInventory,
    setProducts,
    orders,
    setOrders,
    deadLetters,
    setDeadLetters,
    summary,
    connected,
    loadError,
    productsWithStock,
    upsertOrder,
  } = useOrderData();

  const [capabilities, setCapabilities] = useState({ faultInjection: false });

  useEffect(() => {
    fetchOpsCapabilities().then(setCapabilities).catch(() => setCapabilities({ faultInjection: false }));
  }, []);

  async function handleCreateProduct(name, price, initialQuantity) {
    const product = await createProduct(name, price, initialQuantity);
    setProducts((prev) => [...prev, product]);
    setInventory(await fetchInventory());
  }

  async function handleRestock(productId, quantity) {
    const updated = await restockProduct(productId, quantity);
    setInventory((prev) => {
      const idx = prev.findIndex((i) => i.productId === productId);
      if (idx === -1) return [...prev, updated];
      const next = [...prev];
      next[idx] = updated;
      return next;
    });
  }

  async function handleRetry(orderId) {
    const order = await retryDeadLetterOrder(orderId);
    setOrders((prev) => upsertOrder(prev, order));
    setDeadLetters((prev) => prev.filter((e) => e.orderId !== orderId));
  }

  // Drills live in the page, not the panel: the page owns all server state, so a burst's accepted
  // orders are merged immediately and the panel only renders the outcome it is handed back.
  async function handleBurst(productId, quantity, count) {
    const result = await submitOrderBurst(productId, quantity, count);
    setOrders((prev) => result.orders.reduce((acc, order) => upsertOrder(acc, order), prev));
    return result;
  }

  async function handleArmFault(productId, occurrences) {
    return armFaultInjection(productId, occurrences);
  }

  async function handleClearFault(productId) {
    return clearFaultInjection(productId);
  }

  return (
    <div className="app">
      <div className="admin-badge">Admin console — not visible to customers</div>
      <span className={`conn-indicator ${connected ? 'live' : 'offline'}`}>
        {connected ? 'Live' : 'Connecting...'}
      </span>

      {loadError && <p className="error-text">Failed to load data: {loadError}</p>}

      <DashboardSummary summary={summary} />

      <ProductForm onCreate={handleCreateProduct} />
      <InventoryTable inventory={inventory} onRestock={handleRestock} />
      <OrdersTable orders={orders} />
      <DeadLetterTable entries={deadLetters} onRetry={handleRetry} />
      <ChaosPanel
        products={productsWithStock}
        faultInjectionEnabled={capabilities.faultInjection === true}
        onBurst={handleBurst}
        onArmFault={handleArmFault}
        onClearFault={handleClearFault}
      />
    </div>
  );
}
