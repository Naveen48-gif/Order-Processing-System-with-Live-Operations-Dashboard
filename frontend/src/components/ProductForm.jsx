import { useState } from 'react';

export default function ProductForm({ onCreate }) {
  const [name, setName] = useState('');
  const [price, setPrice] = useState('9.99');
  const [initialQuantity, setInitialQuantity] = useState(20);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!name.trim()) {
      setError('Enter a product name');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await onCreate(name.trim(), Number(price), Number(initialQuantity));
      setName('');
    } catch (err) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="panel">
      <h2>Add Product</h2>
      <form className="order-form" onSubmit={handleSubmit}>
        <input
          type="text"
          placeholder="Product name"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <input
          type="number"
          min="0"
          step="0.01"
          placeholder="Price"
          value={price}
          onChange={(e) => setPrice(e.target.value)}
        />
        <input
          type="number"
          min="0"
          placeholder="Initial stock"
          value={initialQuantity}
          onChange={(e) => setInitialQuantity(e.target.value)}
        />
        <button type="submit" disabled={submitting}>
          {submitting ? 'Adding...' : 'Add Product'}
        </button>
      </form>
      {error && <p className="error-text">{error}</p>}
    </section>
  );
}
