-- Static product catalog. sku_id values are shared with inventory-service
-- (they are a business identifier, not a cross-database foreign key).
INSERT INTO sku (id, name, price) VALUES
    (2001, 'Mechanical Keyboard', 299.00),
    (2002, 'Wireless Mouse',      129.00),
    (2003, 'USB-C Hub',           199.00),
    (2004, '27-inch Monitor',    1599.00),
    (2005, 'Laptop Stand',         89.00);
