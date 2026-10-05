// 将模拟实时业务接口归入 API 包。
package com.example.salesagent.controller;

// 导入 UTC 时间戳类型，为业务结果标记返回时刻。
import java.time.Instant;
// 导入不可变键值映射，用于构造 JSON 响应。
import java.util.Map;
// 导入环境注解，将演示业务接口与 MCP 工具进程一起启动。
import org.springframework.context.annotation.Profile;
// 导入 HTTP 状态枚举，用于明确返回商品不存在。
import org.springframework.http.HttpStatus;
// 导入 REST 路由和路径参数注解。
import org.springframework.web.bind.annotation.*;
// 导入携带 HTTP 状态与可读原因的异常。
import org.springframework.web.server.ResponseStatusException;

// 将商品状态映射输出为 JSON。
@RestController
// 仅在 MCP 服务环境中提供模拟业务数据。
@Profile("mcp-server")
// 供 BusinessTools 通过真实 HTTP 请求演示价格和库存查询。
public class DemoBusinessController {
    // 将商品编码放在 URL 路径中。
    @GetMapping("/demo/business/products/{sku}")
    // 接收商品 SKU 并返回对应的模拟业务状态。
    public Map<String, Object> product(@PathVariable String sku) {
        // 演示接口只提供 DEMO-A，其他 SKU 返回 404。
        if (!sku.equals("DEMO-A"))
            // 携带明确原因，让工具能够提示商品不存在。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "演示商品不存在");
        // 返回 SKU、名称、价格及币种，字段名供工具和前端读取。
        return Map.of("sku", sku, "name", "演示蛋白营养粉", "price", 199.00, "currency", "CNY",
                // 标记库存和模拟数据属性，同时附上当前时间以说明响应时效。
                "stock", 120, "demo", true, "updatedAt", Instant.now().toString());
    }
}
