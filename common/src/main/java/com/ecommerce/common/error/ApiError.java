package com.ecommerce.common.error;

/** Uniform JSON error body returned by both services. */
public record ApiError(String code, String message) {
}
