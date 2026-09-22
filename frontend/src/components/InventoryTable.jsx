// Rows are InventoryResponse: { productId, productName, quantity, stockStatus }.
import { useState } from 'react';

const STOCK_ROW_CLASS = {
  OUT_OF_STOCK: 'row-danger',
  LOW_STOCK: 'row-warning',
  AVAILABLE: '',
};

function RestockControl({ productId, onRestock }) {
  const [amount, setAmount] = useState(10);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function handleClick() {
    setBusy(true);
    setError(null);
    try {
      await onRestock(productId, Number(amount));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="restock-control">
      <input type="number" min="1" value={amount} onChange={(e) => setAmount(e.target.value)} />
      <button type="button" className="retry-btn" disabled={busy} onClick={handleClick}>
        {busy ? '...' : '+ Restock'}
      </button>
      {error && <span className="error-text">{error}</span>}
    </div>
  );
}

// onRestock is optional: the storefront view renders this table read-only for customers, the admin
// view passes a handler to expose the inline top-up control.
export default function InventoryTable({ inventory, onRestock }) {
  return (
    <section className="panel">
      <h2>Current Inventory</h2>
      <table>
        <thead>
          <tr>
            <th>Product</th>
            <th>Available Qty</th>
            <th>Stock Status</th>
            {onRestock && <th>Restock</th>}
          </tr>
        </thead>
        <tbody>
          {inventory.map((i) => (
            <tr key={i.productId} className={STOCK_ROW_CLASS[i.stockStatus] || ''}>
              <td>{i.productName}</td>
              <td>{i.quantity}</td>
              <td>{i.stockStatus}</td>
              {onRestock && (
                <td>
                  <RestockControl productId={i.productId} onRestock={onRestock} />
                </td>
              )}
            </tr>
          ))}
          {inventory.length === 0 && (
            <tr>
              <td colSpan={onRestock ? 4 : 3} className="empty">No inventory yet</td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
