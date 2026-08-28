package com.ecommerce.inventory.api;

import com.ecommerce.inventory.api.dto.InventoryResponse;
import com.ecommerce.inventory.api.dto.OrderRefRequest;
import com.ecommerce.inventory.api.dto.ReserveRequest;
import com.ecommerce.inventory.api.dto.StockActionResponse;
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

    @PostMapping("/internal/inventory/reserve")
    public StockActionResponse reserve(@RequestBody ReserveRequest req) {
        stockService.reserve(req.orderId(), req.lines());
        return new StockActionResponse("RESERVED");
    }

    @PostMapping("/internal/inventory/commit")
    public StockActionResponse commit(@RequestBody OrderRefRequest req) {
        stockService.commit(req.orderId());
        return new StockActionResponse("COMMITTED");
    }

    @PostMapping("/internal/inventory/release")
    public StockActionResponse release(@RequestBody OrderRefRequest req) {
        stockService.release(req.orderId());
        return new StockActionResponse("RELEASED");
    }

    @GetMapping("/api/v1/inventory/{skuId}")
    public InventoryResponse get(@PathVariable long skuId) {
        Inventory inv = stockService.getInventory(skuId);
        return new InventoryResponse(inv.skuId(), inv.available(), inv.reserved(), inv.sold());
    }
}
