package com.example.salesagent.mcp;

import com.example.salesagent.SalesAgentApplication;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 启动真实 HTTP MCP 服务，测试发现与调用，不需要百炼 Key 或 Docker。 */
class McpIntegrationTest {
    @Test void discoversToolsOverStreamableHttp() throws Exception {
        // 业务工具通过 HTTP 回调同一个随机端口上的业务 Controller。
        int selectedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { selectedPort = socket.getLocalPort(); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        try (org.springframework.context.ConfigurableApplicationContext context = new SpringApplicationBuilder(SalesAgentApplication.class).profiles("mcp-server")
                .properties("spring.main.banner-mode=off").run("--server.port=" + selectedPort,
                        "--demo.mcp.business-url=http://127.0.0.1:" + selectedPort)) {
            assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty(),
                    "MCP 进程不应创建聊天数据库连接");
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            io.agentscope.core.tool.mcp.McpClientWrapper client = McpClientBuilder.create("test").streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                    .timeout(Duration.ofSeconds(5)).buildSync();
            try {
                client.initialize().block(Duration.ofSeconds(10));
                java.util.List<io.modelcontextprotocol.spec.McpSchema.Tool> tools = client.listTools().block();
                assertNotNull(tools);
                assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("readRepositoryFile")));
                // 参数非法必须通过 MCP 返回工具错误，而非访问外部网络。
                io.modelcontextprotocol.spec.McpSchema.CallToolResult result = client.callTool("readRepositoryFile", Map.of("path", "../.env", "commitSha", "a".repeat(40))).block();
                assertNotNull(result); assertTrue(result.isError());
                io.modelcontextprotocol.spec.McpSchema.CallToolResult business = client.callTool("getProductStatus", Map.of("sku", "DEMO-A")).block();
                assertNotNull(business); assertFalse(Boolean.TRUE.equals(business.isError()));
                String content = ((io.modelcontextprotocol.spec.McpSchema.TextContent) business.content().getFirst()).text();
                com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
                assertTrue(json.path("data").path("demo").asBoolean());
                assertEquals(120, json.path("data").path("stock").asInt());
                assertTrue(json.path("source").asText().endsWith("/demo/business/products/DEMO-A"));
                java.net.http.HttpResponse<String> missing = java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/demo/business/products/UNKNOWN")).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(404, missing.statusCode());
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex);
            } finally { client.close(); }
        }
    }
}
