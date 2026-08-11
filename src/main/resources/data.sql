INSERT INTO users(id, name) VALUES (1, 'Demo User') ON DUPLICATE KEY UPDATE name=VALUES(name);
INSERT INTO users(id, name) VALUES (2, 'Load Test User') ON DUPLICATE KEY UPDATE name=VALUES(name);
INSERT INTO shops(id, name, category, price_cents) VALUES
  (1, 'North Star Coffee', 'Cafe', 1299),
  (2, 'Lakeview Noodles', 'Restaurant', 1899),
  (3, 'Green Market', 'Grocery', 999)
ON DUPLICATE KEY UPDATE name=VALUES(name), category=VALUES(category), price_cents=VALUES(price_cents);
INSERT INTO flash_sale_items(id, shop_id, stock, active) VALUES (101, 1, 100, TRUE)
ON DUPLICATE KEY UPDATE stock=VALUES(stock), active=VALUES(active);
