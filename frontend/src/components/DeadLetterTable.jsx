// Rows are DlqOrderResponse: { orderId, productId, quantity, retryCount, failureReason, deadLetteredAt }.
export default function DeadLetterTable({ entries, onRetry }) {
  return (
    <section className="panel">
      <h2>Dead-Letter Queue</h2>
      <table>
        <thead>
          <tr>
            <th>Order #</th>
            <th>Product ID</th>
            <th>Qty</th>
            <th>Reason</th>
            <th>Retries</th>
            <th>Failed At</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => (
            <tr key={e.orderId}>
              <td>{e.orderId}</td>
              <td>{e.productId}</td>
              <td>{e.quantity}</td>
              <td className="reason">{e.failureReason}</td>
              <td>{e.retryCount}</td>
              <td>{new Date(e.deadLetteredAt).toLocaleTimeString()}</td>
              <td>
                <button type="button" className="retry-btn" onClick={() => onRetry?.(e.orderId)}>
                  Retry
                </button>
              </td>
            </tr>
          ))}
          {entries.length === 0 && (
            <tr>
              <td colSpan={7} className="empty">No failed orders</td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
