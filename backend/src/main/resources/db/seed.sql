-- ---------------------------------------------------------------------------
-- Demo catalogue used by the dashboard (PostgreSQL flavour, idempotent).
--
-- The application seeds the same three products on boot when order.seed.enabled=true
-- (DemoDataSeeder), which works on PostgreSQL, MySQL and H2 alike. This script exists for teams
-- that prefer to provision data with SQL before the first start; running both is harmless.
--
-- Stock levels are chosen to exercise every dashboard state:
--   Laptop   -> plenty of stock   (AVAILABLE)
--   Keyboard -> running low       (LOW_STOCK)
--   Monitor  -> sold out          (OUT_OF_STOCK)
-- ---------------------------------------------------------------------------

INSERT INTO product (name, price, created_at)
VALUES ('Laptop', 1299.99, CURRENT_TIMESTAMP),
       ('Keyboard', 49.50, CURRENT_TIMESTAMP),
       ('Monitor', 219.00, CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;

INSERT INTO inventory (product_id, quantity, version, updated_at)
SELECT product.id, seed.quantity, 0, CURRENT_TIMESTAMP
FROM (VALUES ('Laptop', 10), ('Keyboard', 2), ('Monitor', 0)) AS seed(name, quantity)
         JOIN product ON product.name = seed.name
ON CONFLICT (product_id) DO NOTHING;
