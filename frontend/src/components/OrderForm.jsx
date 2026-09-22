import { useState } from 'react';

export default function OrderForm({ products, onSubmit }) {
  const [productId, setProductId] = useState('');
  const [quantity, setQuantity] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!productId) {
      setError('Select a product');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit(Number(productId), Number(quantity));
      setQuantity(1);
    } catch (err) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="panel">
      <h2>Place Order</h2>
      <form className="order-form" onSubmit={handleSubmit}>
        <select value={productId} onChange={(e) => setProductId(e.target.value)}>
          <option value="">Select product...</option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name} ({p.quantity ?? '?'} in stock)
            </option>
          ))}
        </select>
        <input
          type="number"
          min="1"
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
        />
        <button type="submit" disabled={submitting}>
          {submitting ? 'Submitting...' : 'Submit Order'}
        </button>
      </form>
      {error && <p className="error-text">{error}</p>}
    </section>
  );
}
