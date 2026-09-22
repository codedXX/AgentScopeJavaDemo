package com.example.salesagent.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @Profile("mcp-server")
public class DemoBusinessController {
    @GetMapping("/demo/business/products/{sku}")
    public Map<String, Object> product(@PathVariable String sku) {
        if (!sku.equals("DEMO-A")) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "演示商品不存在");
        return Map.of("sku", sku, "name", "演示蛋白营养粉", "price", 199.00, "currency", "CNY",
                "stock", 120, "demo", true, "updatedAt", Instant.now().toString());
    }
}
