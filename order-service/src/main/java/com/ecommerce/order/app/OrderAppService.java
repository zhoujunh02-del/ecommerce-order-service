package com.ecommerce.order.app;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.common.event.OrderEvent;
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
import com.ecommerce.order.infra.mapper.IdempotencyMapper;
import com.ecommerce.order.infra.mapper.IdempotencyRow;
import com.ecommerce.order.infra.mapper.OrderItemMapper;
import com.ecommerce.order.infra.mapper.OrderItemRow;
import com.ecommerce.order.infra.mapper.OrderMapper;
import com.ecommerce.order.infra.mapper.OutboxMapper;
import com.ecommerce.order.infra.mapper.Sku;
import com.ecommerce.order.infra.mapper.SkuMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates the order lifecycle. Not annotated @Transactional: transactions are
 * opened explicitly and briefly via {@link TransactionTemplate}, so the HTTP reserve
 * call runs OUTSIDE any transaction.
 */
@Service
public class OrderAppService {

    private final TransactionTemplate tx;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SkuMapper skuMapper;
    private final IdempotencyMapper idempotencyMapper;
    private final OutboxMapper outboxMapper;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Duration paymentTimeout;

    public OrderAppService(TransactionTemplate tx,
                           OrderMapper orderMapper,
                           OrderItemMapper orderItemMapper,
                           SkuMapper skuMapper,
                           IdempotencyMapper idempotencyMapper,
                           OutboxMapper outboxMapper,
                           InventoryClient inventoryClient,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry,
                           @Value("${order.payment-timeout}") Duration paymentTimeout) {
        this.tx = tx;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.skuMapper = skuMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.outboxMapper = outboxMapper;
        this.inventoryClient = inventoryClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.paymentTimeout = paymentTimeout;
    }

    public OrderResponse placeOrder(long userId, String idemKey, CreateOrderRequest req) {
        validate(idemKey, req);
        String requestHash = requestHash(userId, req);

        // Fast path: a completed retry replays without rebuilding anything.
        IdempotencyRow existing = idempotencyMapper.find(idemKey);
        if (existing != null) {
            return replay(existing, requestHash);
        }

        // Snapshot SKU price/name (plain reads).
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
                total, "CNY", expireAt, null, 0, null, null, idemKey);

        // ── T1: claim the idempotency key AND persist the order, atomically. ──
        // If a concurrent request already claimed the key, the INSERT conflicts and
        // the whole T1 rolls back (no orphan order); we then replay the winner's result.
        try {
            tx.executeWithoutResult(s -> {
                idempotencyMapper.insertClaim(idemKey, requestHash);
                orderMapper.insert(order);
                orderItemMapper.batchInsert(orderId, items);
            });
        } catch (DuplicateKeyException duplicate) {
            return replay(idempotencyMapper.find(idemKey), requestHash);
        }

        // ── HTTP reserve: OUTSIDE any transaction. ──
        List<ReserveLine> lines = items.stream()
                .map(i -> new ReserveLine(i.skuId(), i.quantity())).toList();
        try {
            inventoryClient.reserve(orderId, lines);
        } catch (BusinessException e) {
            if (e.code() == ErrorCode.INSUFFICIENT_STOCK) {
                // Definite business failure → cache it so retries get the identical 409 (T2').
                tx.executeWithoutResult(s -> {
                    orderMapper.transition(orderId, OrderStatus.CREATING, OrderStatus.FAILED, "INSUFFICIENT_STOCK");
                    idempotencyMapper.complete(idemKey,
                            toJson(IdemOutcome.error(e.code().name(), e.getMessage())));
                });
                meterRegistry.counter("order.result", "outcome", "insufficient_stock").increment();
            }
            throw e;
        } catch (RuntimeException e) {
            // Retries exhausted / circuit open / timeout: we do NOT know whether the
            // reserve happened. Leave the order CREATING and the key IN_PROGRESS; the
            // CreatingOrderReconciler will query inventory's status and decide. The
            // client gets a 503 and may retry (idempotently).
            meterRegistry.counter("order.result", "outcome", "inventory_unavailable").increment();
            throw new BusinessException(ErrorCode.INVENTORY_UNAVAILABLE,
                    "inventory temporarily unavailable; order is being processed");
        }

        // ── T2: CREATING → PENDING_PAYMENT, and cache the success outcome. ──
        OrderResponse resp = toResponse(order.id(), orderNo, OrderStatus.PENDING_PAYMENT, total, expireAt, items);
        tx.executeWithoutResult(s -> {
            orderMapper.transition(orderId, OrderStatus.CREATING, OrderStatus.PENDING_PAYMENT, null);
            outboxMapper.insert(orderId, OrderEvent.ORDER_CREATED,
                    toJson(OrderEvent.of(OrderEvent.ORDER_CREATED, orderId, userId)));
            idempotencyMapper.complete(idemKey, toJson(IdemOutcome.ok(resp)));
        });
        meterRegistry.counter("order.result", "outcome", "created").increment();
        return resp;
    }

    public void cancel(long userId, UUID orderId) {
        Order order = orderMapper.findByIdAndUser(orderId, userId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "no such order");
        }
        Boolean moved = tx.execute(s -> {
            int affected = orderMapper.transition(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, null);
            if (affected == 0) {
                return false;
            }
            outboxMapper.insert(orderId, OrderEvent.ORDER_CANCELLED,
                    toJson(OrderEvent.of(OrderEvent.ORDER_CANCELLED, orderId, userId)));
            return true;
        });
        if (!Boolean.TRUE.equals(moved)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT, "order cannot be cancelled now");
        }
    }

    public OrderResponse getOrder(long userId, UUID orderId) {
        Order order = orderMapper.findByIdAndUser(orderId, userId);
        if (order == null) {
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

    // ── Idempotency helpers ────────────────────────────────────────────────

    private OrderResponse replay(IdempotencyRow row, String requestHash) {
        if (!row.requestHash().equals(requestHash)) {
            // Same key, different body: a client bug or an attack — refuse it.
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "idempotency key reused with a different request");
        }
        if ("IN_PROGRESS".equals(row.status())) {
            throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS,
                    "a request with this key is still being processed");
        }
        IdemOutcome outcome = fromJson(row.response());
        if (outcome.success()) {
            return outcome.order();
        }
        throw new BusinessException(ErrorCode.valueOf(outcome.errorCode()), outcome.errorMessage());
    }

    private String requestHash(long userId, CreateOrderRequest req) {
        String canonical = userId + "|" + req.items().stream()
                .sorted(Comparator.comparingLong(CreateOrderRequest.OrderItemRequest::skuId))
                .map(i -> i.skuId() + ":" + i.quantity())
                .collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "hash failure");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "cannot serialize value");
        }
    }

    private IdemOutcome fromJson(String json) {
        try {
            return objectMapper.readValue(json, IdemOutcome.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "cannot read cached outcome");
        }
    }

    private void validate(String idemKey, CreateOrderRequest req) {
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "invalid Idempotency-Key");
        }
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
