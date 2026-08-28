package com.ecommerce.order.api;

import com.ecommerce.common.error.ApiError;
import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.order.infra.client.InventoryUnavailableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        return ResponseEntity
                .status(ex.code().httpStatus())
                .body(new ApiError(ex.code().name(), ex.getMessage()));
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    public ResponseEntity<ApiError> handleInventoryUnavailable(InventoryUnavailableException ex) {
        return ResponseEntity
                .status(ErrorCode.INVENTORY_UNAVAILABLE.httpStatus())
                .body(new ApiError(ErrorCode.INVENTORY_UNAVAILABLE.name(), ex.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(new ApiError(ErrorCode.INVALID_REQUEST.name(), "missing header: " + ex.getHeaderName()));
    }
}
