-- Runs once, on the very first boot of the postgres container.
-- Creates the two logical databases and a dedicated user per service, so each
-- service can ONLY reach its own database (enforcing data ownership at the DB level).

-- order-service
CREATE USER order_user WITH PASSWORD 'order_pass';
CREATE DATABASE order_db OWNER order_user;

-- inventory-service
CREATE USER inventory_user WITH PASSWORD 'inventory_pass';
CREATE DATABASE inventory_db OWNER inventory_user;
