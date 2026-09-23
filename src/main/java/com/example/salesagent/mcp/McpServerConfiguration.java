package com.example.salesagent.mcp;

import com.example.salesagent.config.DemoProperties;
import com.example.salesagent.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;

/**
 * MCP 服务端装配类。通过 Streamable HTTP 的 /mcp 端点暴露仓库文件树、文件读取和模拟商品状态三个工具；
 * 传输、工具注册和响应格式由官方 MCP SDK 负责。
 */
@Configuration @Profile("mcp-server")
public class McpServerConfiguration {
    /** 创建 MCP 的 HTTP 传输层。 */
    @Bean public HttpServletStreamableServerTransportProvider mcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder().jsonMapper(McpJsonMapper.getDefault()).mcpEndpoint("/mcp").build();
    }
    @Bean public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration = new ServletRegistrationBean<>(transport, "/mcp");
        registration.setAsyncSupported(true);
        return registration;
    }
    /**
     * 注册工具名称、说明与必填字符串参数。仓库文件树不接收参数；读取文件需要 path 和 commitSha；
     * 商品状态需要 sku。工具实现返回 JSON 文本，错误通过 MCP 的 isError 标志传递。
     */
    @Bean(destroyMethod = "close") public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, DemoProperties p, ObjectMapper mapper) {
        GitHubRepositoryTools github = new GitHubRepositoryTools(p.getGithub().getApiUrl(), p.getGithub().getToken(), mapper);
        BusinessTools business = new BusinessTools(p.getMcp().getBusinessUrl(), mapper);
        return McpServer.sync(transport).serverInfo("sales-demo-tools", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(tool("listRepositoryFiles", "列出 codedXX/redis-cache-demo 的源码文件，返回固定 commitSha 和来源。", Map.of()),
                        (exchange, request) -> result(github::listRepositoryFiles, mapper))
                .toolCall(tool("readRepositoryFile", "读取仓库源码；必须先从文件树取得 path 和 commitSha。", Map.of("path", "文件路径", "commitSha", "文件树返回的40位提交SHA")),
                        (exchange, request) -> result(() -> github.readRepositoryFile(arg(request, "path"), arg(request, "commitSha")), mapper))
                .toolCall(tool("getProductStatus", "查询当前价格库存。演示商品 SKU 为 DEMO-A；结果是模拟业务数据。", Map.of("sku", "商品SKU")),
                        (exchange, request) -> result(() -> business.getProductStatus(arg(request, "sku")), mapper))
                .build();
    }
    /** 生成工具描述和参数格式。 */
    private static McpSchema.Tool tool(String name, String description, Map<String, String> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        fields.forEach((key, label) -> properties.put(key, Map.of("type", "string", "description", label)));
        return McpSchema.Tool.builder().name(name).description(description)
                .inputSchema(new McpSchema.JsonSchema("object", properties, new ArrayList<>(fields.keySet()), false, null, null)).build();
    }
    /** 读取并检查工具调用参数。 */
    private static String arg(McpSchema.CallToolRequest request, String name) {
        Object value = request.arguments() == null ? null : request.arguments().get(name);
        if (!(value instanceof String)) throw new IllegalArgumentException("缺少工具参数 " + name);
        String text = (String) value;
        if (text.isBlank()) throw new IllegalArgumentException("缺少工具参数 " + name);
        return text;
    }
    /** 将工具结果或安全的错误信息转为 MCP 响应。 */
    private static McpSchema.CallToolResult result(Supplier<?> action, ObjectMapper mapper) {
        try {
            return McpSchema.CallToolResult.builder().addTextContent(mapper.writeValueAsString(action.get())).isError(false).build();
        } catch (Exception ex) {
            // 不回传堆栈/认证头。工具错误仍是正常 MCP 响应，便于 Agent 决定如何回答。
            String message = ex instanceof IllegalArgumentException || ex instanceof IllegalStateException ? ex.getMessage() : "工具执行失败";
            return McpSchema.CallToolResult.builder().addTextContent("工具失败：" + message).isError(true).build();
        }
    }
}
