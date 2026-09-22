import OrderForm from '../components/OrderForm.jsx';
import InventoryTable from '../components/InventoryTable.jsx';
import OrdersTable from '../components/OrdersTable.jsx';
import { useOrderData } from '../hooks/useOrderData.js';
import { submitOrder } from '../services/api.js';
import '../styles/App.css';

/**
 * Customer-facing storefront: browse stock and place an order. Deliberately excludes every admin
 * capability (catalogue management, restock, DLQ replay, load/fault drills) — a shopper can place
 * orders, not run the operation.
 *
 * <p>There is no authentication layer in this system, so "my orders" cannot be scoped per customer;
 * the live feed below is every order in the system. Adding real accounts would only change how this
 * page filters `orders`, not the admin/customer split itself.
 */
export default function StorefrontPage() {
  const { orders, setOrders, inventory, summary, connected, loadError, productsWithStock, upsertOrder } =
    useOrderData();

  async function handleOrderSubmit(productId, quantity) {
    const order = await submitOrder(productId, quantity);
    setOrders((prev) => upsertOrder(prev, order));
  }

  return (
    <div className="app">
      {loadError && <p className="error-text">Failed to load data: {loadError}</p>}

      <div className="grid">
        <OrderForm products={productsWithStock} onSubmit={handleOrderSubmit} />
        <div className="panel summary-mini">
          <h2>Store Status</h2>
          <p>
            {summary ? `${summary.totalOrders} orders placed so far.` : 'Loading...'}{' '}
            <span className={`conn-indicator ${connected ? 'live' : 'offline'}`}>
              {connected ? 'Live' : 'Connecting...'}
            </span>
          </p>
        </div>
      </div>

      <InventoryTable inventory={inventory} />
      <OrdersTable orders={orders} />
    </div>
  );
}
