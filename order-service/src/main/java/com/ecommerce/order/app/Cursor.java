package com.ecommerce.order.app;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor: base64("&lt;createdAt ISO&gt;|&lt;uuid&gt;"). Encodes the
 * last row of a page so the next page can continue from exactly there.
 */
record Cursor(OffsetDateTime createdAt, UUID id) {

    static String encode(OffsetDateTime createdAt, UUID id) {
        String raw = createdAt.toInstant().toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf('|');
            OffsetDateTime ts = OffsetDateTime.parse(raw.substring(0, sep));
            UUID id = UUID.fromString(raw.substring(sep + 1));
            return new Cursor(ts, id);
        } catch (IllegalArgumentException | DateTimeParseException | IndexOutOfBoundsException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "invalid cursor");
        }
    }
}
