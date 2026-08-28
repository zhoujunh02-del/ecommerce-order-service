-- Atomic stock pre-deduction. Redis runs this whole script without interruption,
-- so the read + check + decrement cannot interleave with another request.
--   KEYS[1] = stock:{skuId}
--   ARGV[1] = quantity
-- Returns:
--   -2  key missing (SKU not warmed) -> caller degrades to the database
--   -1  insufficient stock
--   >=0 remaining stock after deducting
local current = redis.call('GET', KEYS[1])
if current == false then
    return -2
end
local stock = tonumber(current)
local qty = tonumber(ARGV[1])
if stock < qty then
    return -1
end
return redis.call('DECRBY', KEYS[1], qty)
