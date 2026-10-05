-- Track fulfilment separately from shipment.
--
-- orders holds roughly 180M rows and takes writes continuously from the
-- checkout path. Applied by the migration job during a normal deploy.

BEGIN;

ALTER TABLE orders ADD COLUMN fulfilled_at timestamptz;

UPDATE orders
SET fulfilled_at = shipped_at
WHERE shipped_at IS NOT NULL;

CREATE INDEX idx_orders_fulfilled_at ON orders (fulfilled_at);

ALTER TABLE orders
  ADD CONSTRAINT orders_fulfilled_after_created
  CHECK (fulfilled_at IS NULL OR fulfilled_at >= created_at);

COMMIT;
