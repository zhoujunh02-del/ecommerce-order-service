package com.ecommerce.order.api;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.api.dto.OrderSummary;
import com.ecommerce.order.api.dto.PageResponse;
import com.ecommerce.order.app.OrderAppService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** X-User-Id header stands in for an authenticated user (auth is out of scope). */
@RestController
public class OrderController {

    private final OrderAppService orderAppService;

    public OrderController(OrderAppService orderAppService) {
        this.orderAppService = orderAppService;
    }

    @PostMapping("/api/v1/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse place(@RequestHeader("X-User-Id") long userId,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @RequestBody CreateOrderRequest req) {
        return orderAppService.placeOrder(userId, idempotencyKey, req);
    }

    @GetMapping("/api/v1/orders/{orderId}")
    public OrderResponse get(@RequestHeader("X-User-Id") long userId,
                             @PathVariable UUID orderId) {
        return orderAppService.getOrder(userId, orderId);
    }

    @GetMapping("/api/v1/orders")
    public PageResponse<OrderSummary> list(@RequestHeader("X-User-Id") long userId,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "20") int size) {
        return orderAppService.listOrders(userId, cursor, size);
    }

    @PostMapping("/api/v1/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@RequestHeader("X-User-Id") long userId,
                       @PathVariable UUID orderId) {
        orderAppService.cancel(userId, orderId);
    }
}
