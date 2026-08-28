package com.ecommerce.order.infra.client;

import com.ecommerce.common.error.ApiError;
import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.order.infra.client.dto.InventoryDtos.ReserveLine;
import com.ecommerce.order.infra.client.dto.InventoryDtos.ReserveRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Synchronous client for inventory-service. Distinguishes business rejections from
 * technical failures:
 *   - 409 INSUFFICIENT_STOCK -> BusinessException (never retried)
 *   - timeout / connection / 5xx -> InventoryUnavailableException (retried, trips breaker)
 * reserve() is wrapped with Resilience4j @Retry + @CircuitBreaker. Retrying is safe
 * because inventory's reserve is idempotent (keyed by order id).
 */
@Component
public class InventoryClient {

    private final RestClient http;

    public InventoryClient(RestClient inventoryRestClient) {
        this.http = inventoryRestClient;
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public void reserve(UUID orderId, List<ReserveLine> lines) {
        try {
            http.post().uri("/internal/inventory/reserve")
                    .body(new ReserveRequest(orderId, lines))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            ApiError err = safeParse(e);
            if (err != null && ErrorCode.INSUFFICIENT_STOCK.name().equals(err.code())) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, err.message());
            }
            // Any other 4xx (e.g. a hanging-reservation conflict) is technical here.
            throw new InventoryUnavailableException(
                    "inventory rejected reserve: " + (err != null ? err.code() : e.getStatusText()));
        } catch (HttpServerErrorException e) {
            throw new InventoryUnavailableException("inventory 5xx");
        } catch (ResourceAccessException e) {
            throw new InventoryUnavailableException("inventory timeout/connection: " + e.getMessage());
        }
    }

    /** Query the reservation outcome for an order. Used by the reconciler. */
    public String queryReservation(UUID orderId) {
        try {
            Map<?, ?> body = http.get().uri("/internal/inventory/reservations/{id}", orderId)
                    .retrieve().body(Map.class);
            return body == null ? "NOT_FOUND" : String.valueOf(body.get("status"));
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new InventoryUnavailableException("status query failed: " + e.getMessage());
        }
    }

    private ApiError safeParse(HttpClientErrorException e) {
        try {
            return e.getResponseBodyAs(ApiError.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
