package com.ecommerce.order.infra.client;

import com.ecommerce.common.error.ApiError;
import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.order.infra.client.dto.InventoryDtos.OrderRefRequest;
import com.ecommerce.order.infra.client.dto.InventoryDtos.ReserveLine;
import com.ecommerce.order.infra.client.dto.InventoryDtos.ReserveRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Synchronous client for inventory-service. Translates transport outcomes into
 * our error vocabulary:
 *   - a business 409 (INSUFFICIENT_STOCK) -> non-retryable BusinessException
 *   - a timeout / connection error / 5xx  -> INVENTORY_UNAVAILABLE
 * The distinction matters: business failures must not be retried, technical ones may.
 */
@Component
public class InventoryClient {

    private final RestClient http;

    public InventoryClient(RestClient inventoryRestClient) {
        this.http = inventoryRestClient;
    }

    public void reserve(UUID orderId, List<ReserveLine> lines) {
        post("/internal/inventory/reserve", new ReserveRequest(orderId, lines));
    }

    public void commit(UUID orderId) {
        post("/internal/inventory/commit", new OrderRefRequest(orderId));
    }

    public void release(UUID orderId) {
        post("/internal/inventory/release", new OrderRefRequest(orderId));
    }

    private void post(String path, Object body) {
        try {
            http.post().uri(path).body(body).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // 4xx: a business decision from inventory-service.
            ApiError err = safeParse(e);
            if (err != null && ErrorCode.INSUFFICIENT_STOCK.name().equals(err.code())) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, err.message());
            }
            throw new BusinessException(ErrorCode.INVENTORY_UNAVAILABLE,
                    "inventory rejected request: " + (err != null ? err.code() : e.getStatusText()));
        } catch (HttpServerErrorException e) {
            // 5xx: inventory-service is unhealthy — treat as a technical failure.
            throw new BusinessException(ErrorCode.INVENTORY_UNAVAILABLE, "inventory 5xx");
        } catch (ResourceAccessException e) {
            // Connection refused / read timeout — we do NOT know if it happened.
            throw new BusinessException(ErrorCode.INVENTORY_UNAVAILABLE,
                    "inventory unavailable: " + e.getMessage());
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
