package com.ecommerce.order.infra.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies payment callbacks with HMAC-SHA256 over a shared secret,
 * standing in for a real gateway's signature scheme. Verification uses a
 * constant-time comparison to avoid leaking information through timing.
 */
@Component
public class HmacSigner {

    private final byte[] secret;

    public HmacSigner(@Value("${payment.callback-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    public boolean verify(String data, String signature) {
        byte[] expected = sign(data).getBytes(StandardCharsets.UTF_8);
        byte[] provided = signature == null ? new byte[0] : signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);   // constant-time
    }
}
