// REST client for the frozen contract in AGENT.md §4 (DTOs only) and §6 (error shape).
const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export class ApiError extends Error {
  constructor(status, code, message, details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

async function handleResponse(res) {
  if (!res.ok) {
    // Error shape per AGENT.md §6: { status, error, message, path, details? }
    let body = null;
    try {
      body = await res.json();
    } catch {
      // non-JSON error body, fall through to generic message
    }
    throw new ApiError(
      res.status,
      body?.error || 'UNKNOWN_ERROR',
      body?.message || `Request failed with status ${res.status}`,
      body?.details
    );
  }
  if (res.status === 204) return null;
  return res.json();
}

export async function fetchProducts() {
  const res = await fetch(`${BASE_URL}/api/products`);
  return handleResponse(res);
}

export async function createProduct(name, price, initialQuantity) {
  const res = await fetch(`${BASE_URL}/api/products`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, price, initialQuantity }),
  });
  return handleResponse(res);
}

export async function fetchInventory() {
  const res = await fetch(`${BASE_URL}/api/inventory`);
  return handleResponse(res);
}

/** Admin-only stock top-up for an existing product. */
export async function restockProduct(productId, quantity) {
  const res = await fetch(`${BASE_URL}/api/inventory/${productId}/restock`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ quantity }),
  });
  return handleResponse(res);
}

export async function fetchOrders({ status, limit = 200 } = {}) {
  // Contract (AGENT.md §4/§10 amendment): a plain array with ?status= & ?limit=200, no page envelope.
  const params = new URLSearchParams({ limit: String(limit) });
  if (status) params.set('status', status);
  const res = await fetch(`${BASE_URL}/api/orders?${params.toString()}`);
  const data = await handleResponse(res);
  return Array.isArray(data) ? data : [];
}

export async function fetchDeadLetters() {
  const res = await fetch(`${BASE_URL}/api/dlq`);
  return handleResponse(res);
}

export async function fetchDashboardSummary() {
  const res = await fetch(`${BASE_URL}/api/dashboard/summary`);
  return handleResponse(res);
}

export async function submitOrder(productId, quantity) {
  const res = await fetch(`${BASE_URL}/api/orders`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productId, quantity, idempotencyKey: crypto.randomUUID() }),
  });
  return handleResponse(res);
}

export async function retryDeadLetterOrder(orderId) {
  const res = await fetch(`${BASE_URL}/api/dlq/${orderId}/retry`, { method: 'POST' });
  return handleResponse(res);
}

// ---------------------------------------------------------------------------
// Operational demo tools. Both are optional capabilities: the dashboard probes
// for them on load and hides the controls when the backend does not expose them,
// so the UI never depends on a feature that production may have switched off.
// ---------------------------------------------------------------------------

/**
 * Fires `count` orders at the same product in parallel, deliberately more than the
 * worker pool can hold, so the bounded queue and its backpressure are visible:
 * accepted orders stream into the live feed, rejected ones come back as 503
 * POOL_SATURATED and are reported rather than hidden.
 */
export async function submitOrderBurst(productId, quantity, count) {
  const attempts = Array.from({ length: count }, () => submitOrder(productId, quantity));
  const settled = await Promise.allSettled(attempts);

  const accepted = settled.filter((r) => r.status === 'fulfilled');
  const failures = settled
    .filter((r) => r.status === 'rejected')
    .map((r) => `${r.reason?.code || 'ERROR'}: ${r.reason?.message || 'unknown failure'}`);

  return {
    requested: count,
    accepted: accepted.length,
    rejected: settled.length - accepted.length,
    failures: [...new Set(failures)],
    orders: accepted.map((r) => r.value),
  };
}

/** True when the backend exposes the gated fault-injection tool (demo/chaos mode only). */
export async function fetchOpsCapabilities() {
  try {
    const res = await fetch(`${BASE_URL}/api/ops/fault-injection`);
    if (!res.ok) return { faultInjection: false };
    return await res.json();
  } catch {
    return { faultInjection: false };
  }
}

/** Arms `occurrences` transient faults for one product; the next reservations then fail and retry. */
export async function armFaultInjection(productId, occurrences) {
  const res = await fetch(`${BASE_URL}/api/ops/fault-injection`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productId, occurrences }),
  });
  return handleResponse(res);
}

/** Disarms any armed fault so the pipeline returns to normal behaviour. */
export async function clearFaultInjection(productId) {
  const res = await fetch(`${BASE_URL}/api/ops/fault-injection/${productId}`, { method: 'DELETE' });
  return handleResponse(res);
}
