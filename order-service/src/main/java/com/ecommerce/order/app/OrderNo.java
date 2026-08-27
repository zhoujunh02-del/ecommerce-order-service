package com.ecommerce.order.app;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Generates a human-facing order number like ORD20260827193045-4409e497.
 * The suffix is the last 8 hex chars of the (unique) order id, so order numbers
 * stay unique even at hundreds of orders per second — a plain random suffix would
 * collide and violate the order_no UNIQUE constraint under load.
 */
final class OrderNo {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private OrderNo() {
    }

    static String generate(UUID orderId) {
        String ts = FMT.format(OffsetDateTime.now());
        String hex = orderId.toString().replace("-", "");
        return "ORD" + ts + "-" + hex.substring(hex.length() - 8);
    }
}
