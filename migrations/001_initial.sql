CREATE TABLE warehouse_products (
  sku VARCHAR(80) PRIMARY KEY,
  name VARCHAR(180) NOT NULL,
  reorder_point INT NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE stock_receipts (
  id VARCHAR(80) PRIMARY KEY,
  sku VARCHAR(80) NOT NULL,
  quantity INT NOT NULL,
  received_by VARCHAR(120) NOT NULL,
  received_at TIMESTAMP NOT NULL
);

CREATE TABLE pick_reservations (
  id VARCHAR(80) PRIMARY KEY,
  sku VARCHAR(80) NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_by VARCHAR(120) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE warehouse_audit_events (
  id VARCHAR(80) PRIMARY KEY,
  actor VARCHAR(120) NOT NULL,
  action VARCHAR(80) NOT NULL,
  entity_id VARCHAR(80) NOT NULL,
  detail VARCHAR(500) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

