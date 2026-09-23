package com.example.salesagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** 工具真正经过 HTTP 调用业务接口；接口自身的数据明确标注为模拟。 */
public class BusinessTools {
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    public BusinessTools(String baseUrl, ObjectMapper mapper) { this.baseUrl = baseUrl; this.mapper = mapper; }
    public Map<String, Object> getProductStatus(String sku) {
        if (sku == null || !sku.matches("[A-Za-z0-9-]{1,40}")) throw new IllegalArgumentException("SKU 格式不正确");
        String source = baseUrl + "/demo/business/products/" + sku;
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("业务接口 HTTP " + response.statusCode() + "，商品可能不存在");
            return Map.of("data", mapper.readTree(response.body()), "source", source);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("业务请求已取消"); }
        catch (java.io.IOException ex) { throw new IllegalStateException("业务接口不可用"); }
    }
}
