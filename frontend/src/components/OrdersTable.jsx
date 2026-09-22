// Status enum per AGENT.md §3: PENDING, PROCESSING, COMPLETED, OUT_OF_STOCK, FAILED, DLQ.
const STATUS_CLASS = {
  PENDING: 'badge badge-pending',
  PROCESSING: 'badge badge-processing',
  COMPLETED: 'badge badge-completed',
  OUT_OF_STOCK: 'badge badge-failed',
  FAILED: 'badge badge-failed',
  DLQ: 'badge badge-failed',
};

export default function OrdersTable({ orders }) {
  return (
    <section className="panel">
      <h2>Live Order Status</h2>
      <table>
        <thead>
          <tr>
            <th>Order #</th>
            <th>Product</th>
            <th>Qty</th>
            <th>Status</th>
            <th>Reason</th>
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((o) => (
            <tr key={o.id}>
              <td>{o.id}</td>
              <td>{o.productName}</td>
              <td>{o.quantity}</td>
              <td><span className={STATUS_CLASS[o.status] || 'badge'}>{o.status}</span></td>
              <td className="reason">{o.failureReason || '-'}</td>
              <td>{new Date(o.updatedAt).toLocaleTimeString()}</td>
            </tr>
          ))}
          {orders.length === 0 && (
            <tr>
              <td colSpan={6} className="empty">No orders yet</td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
