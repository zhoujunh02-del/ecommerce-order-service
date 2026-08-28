package com.ecommerce.inventory.api;

import com.ecommerce.inventory.api.dto.InventoryResponse;
import com.ecommerce.inventory.api.dto.ReservationStatusResponse;
import com.ecommerce.inventory.api.dto.ReserveRequest;
import com.ecommerce.inventory.api.dto.StockActionResponse;
import java.util.UUID;
import com.ecommerce.inventory.app.StockService;
import com.ecommerce.inventory.domain.Inventory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {

    private final StockService stockService;

    public InventoryController(StockService stockService) {
        this.stockService = stockService;
    }

    // reserve stays a synchronous HTTP call (the Saga forward action). commit/release
    // are now driven by Kafka events (see InventoryEventConsumer), not HTTP.
    @PostMapping("/internal/inventory/reserve")
    public StockActionResponse reserve(@RequestBody ReserveRequest req) {
        stockService.reserve(req.orderId(), req.lines());
        return new StockActionResponse("RESERVED");
    }

    /** Status query used by the order-service reconciler to converge a Saga. */
    @GetMapping("/internal/inventory/reservations/{orderId}")
    public ReservationStatusResponse reservation(@PathVariable UUID orderId) {
        return new ReservationStatusResponse(orderId.toString(), stockService.queryReservation(orderId));
    }

    @GetMapping("/api/v1/inventory/{skuId}")
    public InventoryResponse get(@PathVariable long skuId) {
        Inventory inv = stockService.getInventory(skuId);
        return new InventoryResponse(inv.skuId(), inv.available(), inv.reserved(), inv.sold());
    }
}
