package com.ecommerce.order.app;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.common.id.UuidV7;
import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.api.dto.OrderResponse.OrderItemResponse;
import com.ecommerce.order.api.dto.OrderSummary;
import com.ecommerce.order.api.dto.PageResponse;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.infra.client.InventoryClient;
import com.ecommerce.order.infra.client.dto.InventoryDtos.ReserveLine;
import com.ecommerce.order.infra.mapper.OrderItemMapper;
import com.ecommerce.order.infra.mapper.OrderItemRow;
import com.ecommerce.order.infra.mapper.OrderMapper;
import com.ecommerce.order.infra.mapper.Sku;
import com.ecommerce.order.infra.mapper.SkuMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates the order lifecycle. Note what is NOT here: this class is not
 * annotated @Transactional. Transactions are opened explicitly and briefly via
 * {@link TransactionTemplate}, so the HTTP reserve call runs OUTSIDE any transaction.
 */
@Service
public class OrderAppService {

    private final TransactionTemplate tx;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SkuMapper skuMapper;
    private final InventoryClient inventoryClient;
    private final Duration paymentTimeout;

    public OrderAppService(TransactionTemplate tx,
                           OrderMapper orderMapper,
                           OrderItemMapper orderItemMapper,
                           SkuMapper skuMapper,
                           InventoryClient inventoryClient,
                           @Value("${order.payment-timeout}") Duration paymentTimeout) {
        this.tx = tx;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.skuMapper = skuMapper;
        this.inventoryClient = inventoryClient;
        this.paymentTimeout = paymentTimeout;
    }

    public OrderResponse placeOrder(long userId, CreateOrderRequest req) {
        validate(req);

        // Snapshot SKU price/name (plain reads; no transaction needed yet).
        List<Long> skuIds = req.items().stream()
                .map(CreateOrderRequest.OrderItemRequest::skuId).distinct().toList();
        Map<Long, Sku> catalog = skuMapper.findByIds(skuIds).stream()
                .collect(Collectors.toMap(Sku::id, Function.identity()));
        if (catalog.size() != skuIds.size()) {
            throw new BusinessException(ErrorCode.SKU_NOT_FOUND, "order references an unknown sku");
        }

        List<OrderItemRow> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CreateOrderRequest.OrderItemRequest line : req.items()) {
            Sku sku = catalog.get(line.skuId());
            items.add(new OrderItemRow(sku.id(), sku.name(), sku.price(), line.quantity()));
            total = total.add(sku.price().multiply(BigDecimal.valueOf(line.quantity())));
        }

        UUID orderId = UuidV7.generate();
        String orderNo = OrderNo.generate(orderId);
        OffsetDateTime expireAt = OffsetDateTime.now().plus(paymentTimeout);
        Order order = new Order(orderId, orderNo, userId, OrderStatus.CREATING,
                total, "CNY", expireAt, null, 0, null, null);

        // ── T1: persist the order as CREATING. Transaction is just these two writes. ──
        tx.executeWithoutResult(s -> {
            orderMapper.insert(order);
            orderItemMapper.batchInsert(orderId, items);
        });

        // ── HTTP reserve: OUTSIDE any transaction. No DB connection is held here. ──
        List<ReserveLine> lines = items.stream()
                .map(i -> new ReserveLine(i.skuId(), i.quantity())).toList();
        try {
            inventoryClient.reserve(orderId, lines);
        } catch (BusinessException e) {
            if (e.code() == ErrorCode.INSUFFICIENT_STOCK) {
                // Definite business failure → mark the order FAILED (T2').
                tx.executeWithoutResult(s ->
                        orderMapper.transition(orderId, OrderStatus.CREATING, OrderStatus.FAILED, "INSUFFICIENT_STOCK"));
            }
            // Technical failure (timeout/5xx): we do NOT know if stock was reserved,
            // so we leave the order in CREATING and let the caller retry.
            // Phase 3 adds retry + a reconciler that queries inventory to decide.
            throw e;
        }

        // ── T2: CREATING → PENDING_PAYMENT. ──
        tx.executeWithoutResult(s ->
                orderMapper.transition(orderId, OrderStatus.CREATING, OrderStatus.PENDING_PAYMENT, null));

        return toResponse(order.id(), orderNo, OrderStatus.PENDING_PAYMENT, total, expireAt, items);
    }

    /** Mock payment success. Phase 1 commits stock synchronously (see the gap note). */
    public void pay(UUID orderId) {
        Order order = orderMapper.findById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "no such order");
        }
        int affected = tx.execute(s ->
                orderMapper.transition(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, null));
        if (affected == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT, "order is not awaiting payment");
        }
        // KNOWN GAP (Phase 1): this commit is a second write to a different service,
        // not covered by the transaction above. If it fails, the order is PAID but
        // stock stays reserved. Phase 2 replaces it with an outbox event over Kafka.
        inventoryClient.commit(orderId);
    }

    public void cancel(long userId, UUID orderId) {
        Order order = orderMapper.findByIdAndUser(orderId, userId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "no such order");
        }
        int affected = tx.execute(s ->
                orderMapper.transition(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, null));
        if (affected == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT, "order cannot be cancelled now");
        }
        // Same known dual-write gap as pay(); fixed in Phase 2.
        inventoryClient.release(orderId);
    }

    public OrderResponse getOrder(long userId, UUID orderId) {
        Order order = orderMapper.findByIdAndUser(orderId, userId);
        if (order == null) {
            // Do not distinguish "not yours" from "does not exist" — 404 for both.
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "no such order");
        }
        List<OrderItemRow> items = orderItemMapper.findByOrderId(orderId);
        return toResponse(order.id(), order.orderNo(), order.status(),
                order.totalAmount(), order.expireAt(), items);
    }

    public PageResponse<OrderSummary> listOrders(long userId, String cursor, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        Cursor c = Cursor.decode(cursor);
        List<Order> rows = orderMapper.findPage(userId,
                c == null ? null : c.createdAt(), c == null ? null : c.id(), limit);

        List<OrderSummary> items = rows.stream()
                .map(o -> new OrderSummary(o.id(), o.orderNo(), o.status().name(),
                        o.totalAmount(), o.createdAt()))
                .toList();

        String next = null;
        if (rows.size() == limit) {
            Order last = rows.get(rows.size() - 1);
            next = Cursor.encode(last.createdAt(), last.id());
        }
        return new PageResponse<>(items, next);
    }

    private void validate(CreateOrderRequest req) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "items must not be empty");
        }
        if (req.items().size() > 20) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "too many item lines");
        }
        for (CreateOrderRequest.OrderItemRequest it : req.items()) {
            if (it.quantity() <= 0 || it.quantity() > 999) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "quantity out of range for sku " + it.skuId());
            }
        }
    }

    private OrderResponse toResponse(UUID orderId, String orderNo, OrderStatus status,
                                     BigDecimal total, OffsetDateTime expireAt, List<OrderItemRow> items) {
        List<OrderItemResponse> lines = items.stream()
                .map(i -> new OrderItemResponse(i.skuId(), i.skuName(), i.unitPrice(), i.quantity()))
                .toList();
        return new OrderResponse(orderId, orderNo, status.name(), total, "CNY", expireAt, lines);
    }
}
