// 将实时业务 HTTP 调用封装归入工具包。
package com.example.salesagent.tool;

// 导入 JSON 映射器，用于解析业务接口响应。
import com.fasterxml.jackson.databind.ObjectMapper;
// 导入 URI 类型，把业务地址交给 HTTP 请求构造器。
import java.net.URI;
// 导入 JDK HTTP 客户端、请求和响应处理器。
import java.net.http.*;
// 导入时长类型，明确设置连接和请求超时。
import java.time.Duration;
// 导入映射类型，统一返回数据和可追溯来源。
import java.util.Map;

/** 工具真正经过 HTTP 调用业务接口；接口自身的数据明确标注为模拟。 */
// 为 Agent 和 MCP 服务提供商品价格、库存查询能力。
public class BusinessTools {
    // 保存业务服务根地址，调用路径由 SKU 拼接。
    private final String baseUrl;
    // 保存 JSON 映射器，复用统一序列化配置。
    private final ObjectMapper mapper;
    // 复用 HTTP 客户端，并为建立连接设置 20 秒上限。
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    // 注入业务 HTTP 地址与 JSON 映射器。
    public BusinessTools(String baseUrl, ObjectMapper mapper) {
        // 保存业务接口地址。
        this.baseUrl = baseUrl;
        // 保存响应解析器。
        this.mapper = mapper;
    }

    // 查询一个 SKU 的当前业务状态，同时返回真实请求来源地址。
    public Map<String, Object> getProductStatus(String sku) {
        // SKU 只能由 1～40 位字母、数字或短横线组成，避免路径参数注入。
        if (sku == null || !sku.matches("[A-Za-z0-9-]{1,40}"))
            // 输入格式错误时不发出网络请求。
            throw new IllegalArgumentException("SKU 格式不正确");
        // 固定商品查询路径，并将其保留为结果的来源链接。
        String source = baseUrl + "/demo/business/products/" + sku;
        // 捕获网络读取和线程中断，转换成工具可读错误。
        try {
            // 以 GET 查询商品；整个响应等待同样限制为 20 秒，并按文本读取正文。
            var response = client.send(HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            // 只有 HTTP 200 才能作为有效商品状态返回。
            if (response.statusCode() != 200)
                // 保留状态码，但不将上游响应或认证信息直接回传。
                throw new IllegalStateException("业务接口 HTTP " + response.statusCode() + "，商品可能不存在");
            // 把正文解析为 JSON 节点，并附上 HTTP 来源地址供回答引用。
            return Map.of("data", mapper.readTree(response.body()), "source", source);
        // HTTP 等待被中断时，恢复线程的中断标记。
        } catch (InterruptedException ex) {
            // 允许上层取消机制继续感知线程中断。
            Thread.currentThread().interrupt();
            // 给工具调用层提供可读的取消说明。
            throw new IllegalStateException("业务请求已取消");
        // 连接、响应读取或 JSON 解析错误统一提示业务接口不可用。
        } catch (java.io.IOException ex) {
            // 不在响应中包含内部网络或解析堆栈。
            throw new IllegalStateException("业务接口不可用");
        }
    }
}
