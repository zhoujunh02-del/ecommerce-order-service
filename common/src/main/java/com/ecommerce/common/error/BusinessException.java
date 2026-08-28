package com.ecommerce.common.error;

/**
 * A NON-retryable error caused by business rules (insufficient stock, illegal
 * state transition, ...). Retrying it changes nothing, so callers must not retry.
 * Contrast with technical/transient failures, which are retryable.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
