package com.ecommerce.order.app;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.common.event.OrderEvent;
import com.ecommerce.common.id.UuidV7;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.infra.mapper.IdempotencyMapper;
import com.ecommerce.order.infra.mapper.OrderMapper;
import com.ecommerce.order.infra.mapper.OutboxMapper;
import com.ecommerce.order.infra.mapper.PaymentMapper;
import com.ecommerce.order.infra.payment.HmacSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles payment gateway callbacks. A callback must be: idempotent (the gateway
 * retries until it gets SUCCESS), fast (stock is committed asynchronously via the
 * outbox), and amount-checked (never trust the caller's money value). Anything
 * suspicious is recorded and still answered SUCCESS, because returning an error
 * only makes the gateway retry forever.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TransactionTemplate tx;
    private final OrderMapper orderMapper;
    private final OutboxMapper outboxMapper;
    private final IdempotencyMapper idempotencyMapper;
    private final PaymentMapper paymentMapper;
    private final HmacSigner signer;
    private final ObjectMapper objectMapper;

    public PaymentService(TransactionTemplate tx,
                          OrderMapper orderMapper,
                          OutboxMapper outboxMapper,
                          IdempotencyMapper idempotencyMapper,
                          PaymentMapper paymentMapper,
                          HmacSigner signer,
                          ObjectMapper objectMapper) {
        this.tx = tx;
        this.orderMapper = orderMapper;
        this.outboxMapper = outboxMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.paymentMapper = paymentMapper;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    public void handleCallback(UUID orderId, String payNo, BigDecimal amount, String signature) {
        // 1. Verify the signature. This is the ONLY failure we reject outright.
        if (!signer.verify(canonical(orderId, payNo, amount), signature)) {
            throw new BusinessException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "invalid payment signature");
        }

        // 2. Idempotency on payNo: the gateway redelivers, so a repeat is normal → SUCCESS.
        try {
            idempotencyMapper.insertClaim("pay:" + payNo, "PAYMENT_CALLBACK");
        } catch (DuplicateKeyException redelivery) {
            return;
        }

        // 3. Unknown order: record and still answer SUCCESS (stop the gateway retrying).
        Order order = orderMapper.findById(orderId);
        if (order == null) {
            paymentMapper.insertAnomaly(orderId, payNo, amount, "ORDER_NOT_FOUND");
            log.warn("Payment callback for unknown order {}", orderId);
            return;
        }

        // 4. Amount check: reject a forged small-amount payment. Record and SUCCESS.
        if (order.totalAmount().compareTo(amount) != 0) {
            paymentMapper.insertAnomaly(orderId, payNo, amount, "AMOUNT_MISMATCH");
            log.warn("Payment amount mismatch for order {}: expected {}, got {}",
                    orderId, order.totalAmount(), amount);
            return;
        }

        // 5. Transition to PAID and emit OrderPaid, atomically.
        tx.executeWithoutResult(s -> {
            int affected = orderMapper.transition(orderId, OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, null);
            if (affected == 1) {
                outboxMapper.insert(orderId, OrderEvent.ORDER_PAID,
                        toJson(OrderEvent.of(OrderEvent.ORDER_PAID, orderId, order.userId())));
                return;
            }
            // affected == 0: someone moved the order first. If a timeout cancel won the
            // race, we have taken money for a cancelled order → queue a refund + alert.
            Order current = orderMapper.findById(orderId);
            if (current.status() == OrderStatus.CANCELLED) {
                paymentMapper.insertPendingRefund(orderId, payNo, amount);
                log.error("PAID-but-CANCELLED race for order {}: money received, refund required", orderId);
            }
            // else already PAID: a duplicate that slipped past the payNo guard — no-op.
        });
    }

    /** Simulate a gateway: build a correctly-signed callback for the order's own total. */
    public void mockPay(UUID orderId) {
        Order order = orderMapper.findById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "no such order");
        }
        String payNo = "PAY" + UuidV7.generate().toString().replace("-", "").substring(0, 16);
        BigDecimal amount = order.totalAmount();
        handleCallback(orderId, payNo, amount, signer.sign(canonical(orderId, payNo, amount)));
    }

    private String canonical(UUID orderId, String payNo, BigDecimal amount) {
        return orderId + "|" + payNo + "|" + amount.toPlainString();
    }

    private String toJson(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "cannot serialize event");
        }
    }
}
