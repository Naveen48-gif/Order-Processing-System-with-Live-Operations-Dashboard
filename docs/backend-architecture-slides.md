---
marp: true
theme: default
paginate: true
size: 16:9
---

<!-- 
Export to PowerPoint:
  npm install -g @marp-team/marp-cli
  marp docs/backend-architecture-slides.md --pptx -o docs/backend-architecture.pptx
Or open this file directly with the Marp for VS Code extension and click "Export slide deck".
-->

# Order Processing System
## Unique Backend Logic Deep-Dive

E-Commerce Order Management · Concurrency-Safe Inventory · Real-Time Dashboard

---

# Agenda

1. The core problem: concurrent orders, finite stock
2. Pessimistic row locking (why not just `synchronized`)
3. Defense-in-depth against negative inventory
4. Guarded order state machine
5. Business failure vs. technical failure
6. Idempotency for safe retries
7. Bounded thread pool & backpressure
8. RabbitMQ work queue + DLX/DLQ
9. Short lock windows via transaction separation
10. Real-time dashboard (WebSocket/STOMP)
11. Database portability by design

---

# The Core Problem

- Many customers can order the **same product at the same instant**
- Stock must **never go negative** — a classic race condition
- The system must stay **responsive** under load, not queue everything serially
- Failures (out of stock, transient errors) must be **handled, not lost**
- Operators need **live visibility** into what's happening right now

> Goal: correctness under concurrency + observability + graceful failure handling

---

# Pessimistic Row Locking

- Stock reservation uses `@Lock(PESSIMISTIC_WRITE)` → Hibernate issues **`SELECT ... FOR UPDATE`**
- Any concurrent transaction touching the same inventory row **blocks until commit**
- This is what physically prevents two workers from both reading `quantity = 1` and both selling it

### Why not a JVM lock (`synchronized` / `ReentrantLock`)?
- JVM locks only guard threads **inside one process**
- Scale to 2 app instances (or a rolling deploy) → each JVM has its own monitor, both hit the same DB row
- **The database is the only shared source of truth** — the lock must live there

---

# Defense-in-Depth Against Negative Stock

Two independent guards, so one bug can't cause an oversell:

| Layer | Guard |
|---|---|
| Application | Checks `quantity >= requested` inside the locked transaction |
| Database | `CHECK (quantity >= 0)` constraint on the `inventory` table |

- Stock lives in its **own narrow row**, separate from the product catalog row
- Keeps the locked/contended column isolated from rarely-updated catalog data
- Smaller lock footprint → less contention under load

---

# Guarded Order State Machine

```
PENDING → PROCESSING → COMPLETED                (terminal)
                    ├─► OUT_OF_STOCK             (business failure, never retried)
                    └─► FAILED → retry < N → PROCESSING
                               → retry = N → DLQ (terminal until manual retry)
```

- Transitions only happen through **guarded mutators** on the entity
- An illegal transition throws `InvalidOrderStateTransitionException` → HTTP 409
- Status can never be corrupted by a stray setter or a race between two workers

---

# Business Failure vs. Technical Failure

The key architectural distinction that shapes the whole retry design:

| Type | Example | Retried? |
|---|---|---|
| **Business failure** | Out of stock | ❌ Never — retrying can't create inventory |
| **Technical failure** | Lock timeout, broker hiccup | ✅ Bounded retry, then DLQ |

- `OUT_OF_STOCK` fails fast and is immediately terminal
- Transient failures get a fixed retry budget (`max-retry-attempts`, backoff)
- Exhausting the budget routes the order to the **dead-letter queue**, not a silent drop

---

# Idempotency for Safe Retries

- Every order can carry an optional `idempotencyKey`, **unique at the DB level**
- A client retry (double-click, network timeout) or a **redelivered broker message**
  re-submits the same logical order → replayed, not double-reserved
- `beginProcessing()` is itself idempotent: re-entry on an already-terminal order is a **no-op**,
  which turns duplicate message delivery into safe work instead of double-processing

---

# Bounded Thread Pool & Backpressure

- Fixed-size worker pool: **bounded core/max threads** + **bounded queue**
- No unbounded thread creation, no unbounded buffering of pending work
- When the pool is saturated, submission fails fast with `PoolSaturatedException` (503)
  instead of silently degrading or running the host out of memory
- Every tuning knob (`core-pool-size`, `max-pool-size`, `queue-capacity`, `max-retry-attempts`)
  is a **typed, validated** `@ConfigurationProperties` class — invalid combinations
  (e.g. `max-pool-size < core-pool-size`) fail at startup, not in production

---

# RabbitMQ Work Queue + DLX/DLQ

- Orders flow through a **durable queue**, processed by the bounded pool
- A **Dead Letter Exchange** automatically routes exhausted/failed messages to a DLQ
  — a real broker-level dead-letter mechanism, not just a status flag in a table
- Transport is swappable (`rabbitmq` canonical vs. `in-process` fallback for dev machines
  without a broker) — **both paths share the identical transactional processor**,
  so business rules can never diverge between environments

---

# Short Lock Windows via Transaction Separation

- Moving an order to `PROCESSING` is a **separate, short transaction** from the
  inventory reservation itself
- Two deliberate reasons:
  1. **Observability** — `PROCESSING` is visible on the dashboard before the lock is even taken
  2. **Throughput** — the inventory row lock is held only for the statements that must be
     atomic with the stock change, not for bookkeeping calls
- Result: many orders can queue against the same hot product without starving each other

---

# Real-Time Dashboard (WebSocket / STOMP)

- Live topics: `/topic/orders`, `/topic/inventory`, `/topic/dashboard`, `/topic/dlq`
- Every push is a **full snapshot, never a delta**
  → a client that reconnects can never end up in a divergent state
- Pushes fire **after transaction commit** — the UI never shows a state that
  was later rolled back
- No polling: the browser reacts the instant an order or stock level changes

---

# Database Portability by Design

- Datasource is **100% environment-variable driven**: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- Same artifact runs against local PostgreSQL, local MySQL, or **AWS RDS** — zero code changes
- Locking is expressed in **JPQL** (`@Lock(PESSIMISTIC_WRITE)`), which compiles to
  `SELECT ... FOR UPDATE` identically on PostgreSQL, MySQL/InnoDB, and H2
- Spring profiles: `default` (PostgreSQL), `local-mysql`, `test` (H2, infra-free CI)

---

# Summary

| Requirement | How it's solved |
|---|---|
| Never oversell | Pessimistic row lock + DB `CHECK` constraint |
| Concurrent processing | Bounded thread pool + RabbitMQ work queue |
| Handle failures | Business vs. technical failure split |
| Bounded retry | Typed retry-budget config + DLX/DLQ |
| Live visibility | STOMP snapshot broadcasts after commit |
| Runs anywhere | Env-driven datasource, portable locking semantics |

---

# Questions?

