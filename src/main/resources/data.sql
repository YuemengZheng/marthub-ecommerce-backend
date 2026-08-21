INSERT INTO users(id, name) VALUES (1, 'Demo User') ON DUPLICATE KEY UPDATE name=VALUES(name);
INSERT INTO users(id, name) VALUES (2, 'Load Test User') ON DUPLICATE KEY UPDATE name=VALUES(name);
INSERT INTO shops(id, name, category, price_cents) VALUES
  (1, 'North Star Coffee', 'Cafe', 1299),
  (2, 'Lakeview Noodles', 'Restaurant', 1899),
  (3, 'Green Market', 'Grocery', 999)
ON DUPLICATE KEY UPDATE name=VALUES(name), category=VALUES(category), price_cents=VALUES(price_cents);
INSERT INTO flash_sale_items(id, shop_id, stock, active) VALUES (101, 1, 100, TRUE)
ON DUPLICATE KEY UPDATE stock=VALUES(stock), active=VALUES(active);

-- A three-row table makes any cache look perfect, so the benchmark needs a keyspace
-- big enough for a hot-key distribution to mean something. Generated with a digit
-- cross join rather than a recursive CTE, which would hit cte_max_recursion_depth.
INSERT INTO shops(id, name, category, price_cents)
SELECT n, CONCAT('Shop ', n), 'Generated', 500 + (n * 7) % 5000
FROM (
  SELECT a.i + b.i * 10 + c.i * 100 + d.i * 1000 AS n
  FROM (SELECT 0 i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
  CROSS JOIN (SELECT 0 i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
  CROSS JOIN (SELECT 0 i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
  CROSS JOIN (SELECT 0 i UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
        UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
) t
WHERE n BETWEEN 4 AND 9999
ON DUPLICATE KEY UPDATE name=VALUES(name);
