# legacy-agent-b — quarantined parallel implementation (not compiled)

This folder holds a **complete second implementation** of the same system that a second agent wrote
into `backend/src/main/java/com/ordermanagement/**` while this project was already under way.

It was moved here on **2026-09-22** to unblock the build. Nothing was deleted: every file is intact,
just outside the Maven source root (`src/main/java`), so it is not compiled and does not clash with
the canonical implementation.

## Why it could not be merged

Both implementations map the *same* database tables (`product`/`products`, `orders`). Keeping both in
`src/main/java` makes Spring Data JPA fail at startup with duplicate entity/table mappings, and it
duplicated repository interfaces with identical simple names. A single source of truth was required.

Beyond the collisions, this variant contradicted several non-negotiable requirements of the brief:

| Quarantined design | Requirement it breaks |
|---|---|
| `OrderStatus` has no `DLQ` constant; dead letters live in a separate `dead_letter_orders` table | DLQ must be a status of the order and exposed via `GET /api/dlq` |
| Business rejections (`OutOfStockException`) are written to the dead-letter table | `OUT_OF_STOCK` is a business failure: never retried, never dead-lettered |
| Retry implemented with in-thread `@Retryable`; `retryCount` overwritten with `maxRetryAttempts` | Bounded retry must be a *persisted per-attempt* count on the order |
| `Order.productId` is an unconstrained `Long` column; stock lives on `Product` | Foreign keys are required, and stock belongs to a separate `Inventory` row with `version`/`updatedAt` |
| `order.setStatus(...)` through a Lombok setter | Invalid status transitions must be impossible |
| `submitOrder` enqueues a worker inside the same `@Transactional` block | The worker can read the order before the insert commits (their own log line "Order {} disappeared" is that race firing) |
| No idempotency key, no CHECK constraints, pool `5/10/200` with `CallerRunsPolicy` | Idempotency, DB-level safety nets, and a bounded 10/10/100 pool are explicit requirements |

## What was worth keeping (and has been adopted)

* The idea of broadcasting inventory/order updates to the dashboard — implemented canonically in the
  `websocket` package against the frozen STOMP contract.
* `CallerRunsPolicy`-style backpressure thinking — replaced by an explicit rejection signal so the
  REST layer can answer `503 POOL_SATURATED` instead of silently blocking a request thread.
* A seeding component and CORS/broker web configuration — re-implemented against the canonical
  entities.

## If you want to cherry-pick

Read the files here as reference only. Any port must target the canonical packages
(`entity`, `repository`, `service`, `dto`, `controller`, `messaging`, `websocket`) and must pass
`backend/scripts/build.ps1 -Goal "clean test"`, including the concurrency test.
