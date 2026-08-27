-- Seed stock. sku_id 2004 (monitor) intentionally has low stock (50) so
-- oversell / concurrency tests have a scarce SKU to fight over.
INSERT INTO inventory (sku_id, available, reserved, sold) VALUES
    (2001, 100,  0, 0),
    (2002, 500,  0, 0),
    (2003, 200,  0, 0),
    (2004,  50,  0, 0),
    (2005, 1000, 0, 0);
