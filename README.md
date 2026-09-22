<<<<<<< HEAD
# Order Processing System with Live Operations Dashboard

Concurrent order processing with inventory-safe locking, a broker-backed dead-letter queue, and a
live React dashboard. Built by two coworker agents — see [`AGENT.md`](AGENT.md) for the frozen
REST/WebSocket/DB contract and the phase-by-phase division of labor between the backend
(concurrency, persistence, messaging) and frontend/DevOps/QA sides.

## Architecture at a glance

- Orders are accepted immediately (`PENDING`) and processed concurrently by a **bounded thread
  pool** fed by a **RabbitMQ** work queue with a dead-letter exchange/queue (in-process pipeline
  fallback when no broker is available).
- Inventory decrement uses a **pessimistic row lock** (`SELECT ... FOR UPDATE`) inside a
  transaction, plus a `CHECK (quantity >= 0)` constraint as a second line of defense — stock can
  never go negative.
- Transient technical failures get a **bounded retry**; business failures (`OUT_OF_STOCK`) fail
  fast and are never retried. Exhausted retries and out-of-stock orders land in the **DLQ**,
  visible (and manually retryable) from the dashboard.
- Live order/inventory/dashboard/DLQ updates are pushed to the browser over **WebSocket/STOMP**
  (`ws://localhost:8080/ws`, topics in `AGENT.md` §5) — no polling.
- Persistence: PostgreSQL (canonical — Docker locally or AWS RDS in production), with a MySQL 8
  profile for machines without Docker, and H2 for the infrastructure-free test profile.

## Run it

### 1. Infrastructure (PostgreSQL + RabbitMQ)
```bash
docker compose up -d
```
Brings up PostgreSQL on `:5432` and RabbitMQ (AMQP `:5672`, management UI at
`http://localhost:15672`) using the credentials in [`.env.example`](.env.example). Copy that file
to `.env` and adjust before running in a shared environment.

No Docker available? Use the `local-mysql` Spring profile against a local MySQL 8 instance
instead (`SPRING_PROFILES_ACTIVE=local-mysql`, see `.env.example` for the connection vars).

To use AWS RDS instead of local Postgres, just point `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` at the
RDS endpoint — no code change required.

### 2. Backend
```bash
cd backend
mvn spring-boot:run
```
Runs on `http://localhost:8080`. Seeds a few demo products/inventory on first boot.

### 3. Frontend
```bash
cd frontend
npm install
npm run dev
```
Runs on `http://localhost:5173`, calling the backend REST API directly (CORS-open in dev) and
subscribing to `ws://localhost:8080/ws` for live updates. Configure `VITE_API_BASE_URL` /
`VITE_WS_URL` via `.env` if the backend runs elsewhere.

## What to look at

- `backend/src/main/java/com/ordermanagement/config/OrderProcessingProperties.java` — typed,
  validated bounded thread-pool + retry-budget configuration.
- `backend/src/main/java/com/ordermanagement/config/MessagingProperties.java` — RabbitMQ
  topology (exchange/queue/DLX/DLQ), env-driven.
- `AGENT.md` — the full frozen contract (status machine, REST API, WebSocket topics, error shape,
  DB schema) plus the live phase status board both agents update.
- `frontend/src/api.js` — REST calls + STOMP-over-WebSocket subscription for the live dashboard.

=======
# Order-Processing-System-with-Live-Operations-Dashboard
Concurrent e-commerce order processing system using Java, Spring Boot, and thread pools, with locking to prevent overselling and negative inventory. Includes failed-order handling, bounded retries, dead-letter queue, persistent PostgreSQL/MySQL or AWS RDS storage, and a React/Angular live dashboard for order status and inventory updates.
>>>>>>> e95950f8d718182f09d8bb12f857ac82e2a2277b
