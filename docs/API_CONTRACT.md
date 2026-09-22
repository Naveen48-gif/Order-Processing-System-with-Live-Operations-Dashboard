# API Contract — Order Processing System (superseded)

> **This document describes an earlier SSE-based prototype and is kept for history only.**
> The current frozen contract (REST paths, WebSocket/STOMP topics, status enum, error shape,
> DB schema) lives in [`../AGENT.md`](../AGENT.md) sections 3–8 — that file is the single source
> of truth both agents build against.

Base URL (backend): `http://localhost:8080`

## Enums

**OrderStatus**: `PENDING` → `PROCESSING` → `COMPLETED` | `OUT_OF_STOCK` | `FAILED`
(`OUT_OF_STOCK` and `FAILED` orders also get a row in the dead-letter table.)

## REST Endpoints

### `POST /api/orders`
Submit a new order. Returns immediately with status `PENDING`; processing happens async.

Request:
```json
{ "productId": 1, "quantity": 3 }
```
Response `201`:
```json
{
  "id": 101,
  "productId": 1,
  "productName": "Wireless Mouse",
  "quantity": 3,
  "status": "PENDING",
  "failureReason": null,
  "retryCount": 0,
  "createdAt": "2026-09-22T10:00:00Z",
  "updatedAt": "2026-09-22T10:00:00Z"
}
```
Validation errors → `400` with `{ "errors": [...] }`.

### `GET /api/orders`
List most recent orders (newest first, capped at 200). Array of the same order shape as above.

### `GET /api/orders/{id}`
Single order by id. `404` if not found.

### `GET /api/products`
List all products with live stock:
```json
[{ "id": 1, "name": "Wireless Mouse", "sku": "WM-100", "quantity": 42 }]
```

### `GET /api/dead-letter`
List dead-lettered orders:
```json
[{
  "id": 5,
  "orderId": 101,
  "productId": 1,
  "quantity": 3,
  "reason": "OUT_OF_STOCK",
  "retryCount": 3,
  "failedAt": "2026-09-22T10:00:02Z"
}]
```

## Live updates — `GET /api/notifications/stream` (SSE)

`text/event-stream`. Two named event types:

- `event: order-update` — data is the full order object (same shape as `POST /api/orders` response).
- `event: inventory-update` — data is the full product object (same shape as `GET /api/products` item).

Frontend should use `EventSource` and merge events into local state (upsert by `id`) instead of
polling.

## CORS

Backend allows all origins in dev (`*`) so the Vite dev server (default `http://localhost:5173`)
can call it directly with no proxy required.
