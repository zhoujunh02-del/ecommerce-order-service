package com.ecommerce.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates UUID version 7 (RFC 9562): a 48-bit millisecond timestamp followed
 * by random bits. Because the high bits are time-ordered, values inserted over
 * time land at the "right end" of a B-tree index instead of at random positions,
 * avoiding the page splits that random UUID v4 causes.
 *
 * <p>We generate it in the application because PostgreSQL 16 has no native
 * {@code uuidv7()} function (that arrived in PostgreSQL 18).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                unix_ts_ms (48 bits)           |ver|  rand_a   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                     rand_b (62 bits)                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        byte[] value = new byte[16];

        // Bytes 0..5: 48-bit big-endian millisecond timestamp.
        long tsMs = System.currentTimeMillis();
        value[0] = (byte) (tsMs >>> 40);
        value[1] = (byte) (tsMs >>> 32);
        value[2] = (byte) (tsMs >>> 24);
        value[3] = (byte) (tsMs >>> 16);
        value[4] = (byte) (tsMs >>> 8);
        value[5] = (byte) (tsMs);

        // Bytes 6..15: random.
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);
        System.arraycopy(rnd, 0, value, 6, 10);

        // Set version (0111 = 7) in the high nibble of byte 6.
        value[6] = (byte) ((value[6] & 0x0F) | 0x70);
        // Set variant (10xx) in the high bits of byte 8.
        value[8] = (byte) ((value[8] & 0x3F) | 0x80);

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (value[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (value[i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }
}
