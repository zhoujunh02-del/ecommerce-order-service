package com.ecommerce.order.api;

import com.ecommerce.common.error.ApiError;
import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
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

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(new ApiError(ErrorCode.INVALID_REQUEST.name(), "missing header: " + ex.getHeaderName()));
    }
}
