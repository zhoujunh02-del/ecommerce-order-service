package com.ecommerce.inventory.api;

import com.ecommerce.common.error.ApiError;
import com.ecommerce.common.error.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns BusinessException into the uniform ApiError JSON body + mapped HTTP status. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        return ResponseEntity
                .status(ex.code().httpStatus())
                .body(new ApiError(ex.code().name(), ex.getMessage()));
    }
}
