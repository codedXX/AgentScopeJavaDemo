package com.example.salesagent.agent;

import com.example.salesagent.config.*;
import com.example.salesagent.model.*;
import com.example.salesagent.rag.HybridRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 模型 HTTP 响应使用确定性替身，AgentScope 的结构化输出、Memory、Hook 链实际执行。 */
class SalesAssistantTest {
    @SuppressWarnings("unchecked")
    @Test void answersFromRagWhenMcpIsDownAndIsolatesHistory() throws Exception {
        var mapper = new ObjectMapper();
        List<String> routerInputs = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            var messages = request.path("input").path("messages");
            boolean routing = messages.get(0).path("content").toString().contains("把问题分为");
            if (routing) routerInputs.add(messages.toString());
            Object output = routing ? Map.of("intent", "KNOWLEDGE", "query", "产品A的蛋白质含量")
                    : Map.of("answer", "每袋含15克蛋白质。", "sources", List.of("products.md", "https://invented.invalid"));
            var toolCall = Map.of("id", UUID.randomUUID().toString(), "type", "function", "function",
                    Map.of("name", "generate_response", "arguments", mapper.writeValueAsString(Map.of("response", output))));
            var message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall));
            var response = Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message))),
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1));
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var p = new DemoProperties(new DemoProperties.Bailian("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "qwen3.7-flash", "qwen3.7-text-embedding", "qwen3.7-text-rerank", 1024, 5), null, null, null, null);
        ObjectProvider<DashScopeChatModel> model = mock(ObjectProvider.class);
        when(model.getObject()).thenReturn(new AgentConfiguration().chatModel(p));
        ObjectProvider<McpClientWrapper> mcp = mock(ObjectProvider.class);
        when(mcp.getObject()).thenThrow(new IllegalStateException("offline"));
        var retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(new RagResult(List.of(new SearchHit(
                new KnowledgeChunk("A", "每袋蛋白质15克", "products.md", 0), .9, "rerank")), false, 10));
        var assistant = new SalesAssistant(retriever, model, mcp, mapper);
        try {
            var first = assistant.chat(new ChatRequest("one", "独特问题标记：产品A的蛋白质含量？"));
            assertEquals(List.of("products.md"), first.sources());
            assertTrue(first.steps().stream().anyMatch(s -> s.contains("MCP 不可用")));
            assistant.chat(new ChatRequest("one", "它呢？"));
            assistant.chat(new ChatRequest("two", "产品A如何？"));
            assertTrue(routerInputs.get(1).contains("独特问题标记"));
            assertFalse(routerInputs.get(2).contains("独特问题标记"));
        } finally { assistant.close(); server.stop(0); }
    }
    @SuppressWarnings("unchecked")
    @Test void repositoryToolsAreRegisteredOnTheExecutingAgent() throws Exception {
        var mapper = new ObjectMapper();
        var source = "https://github.com/example/repo/blob/" + "a".repeat(40) + "/README.md";
        var sawRepositorySchema = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            var messages = request.path("input").path("messages");
            boolean routing = messages.get(0).path("content").toString().contains("把问题分为");
            String name;
            Map<String, Object> arguments;
            if (routing) {
                name = "generate_response";
                arguments = Map.of("response", Map.of("intent", "REPOSITORY", "query", "读取仓库 README"));
            } else if (!messages.toString().contains("测试仓库说明")) {
                sawRepositorySchema.set(request.path("parameters").path("tools").toString().contains("listRepositoryFiles"));
                name = "listRepositoryFiles";
                arguments = Map.of();
            } else {
                name = "generate_response";
                arguments = Map.of("response", Map.of("answer", "这是测试仓库。", "sources", List.of(source)));
            }
            var toolCall = Map.of("id", UUID.randomUUID().toString(), "type", "function", "function",
                    Map.of("name", name, "arguments", mapper.writeValueAsString(arguments)));
            var message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall));
            var response = Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message))),
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1));
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var p = new DemoProperties(new DemoProperties.Bailian("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "qwen3.7-flash", "unused", "unused", 1024, 5), null, null, null, null);
        ObjectProvider<DashScopeChatModel> model = mock(ObjectProvider.class);
        when(model.getObject()).thenReturn(new AgentConfiguration().chatModel(p));
        var client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("test");
        when(client.initialize()).thenReturn(reactor.core.publisher.Mono.empty());
        var schema = new io.modelcontextprotocol.spec.McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        when(client.listTools()).thenReturn(reactor.core.publisher.Mono.just(List.of(
                io.modelcontextprotocol.spec.McpSchema.Tool.builder().name("listRepositoryFiles")
                        .description("List repository").inputSchema(schema).build())));
        when(client.callTool(eq("listRepositoryFiles"), anyMap())).thenReturn(reactor.core.publisher.Mono.just(
                io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder()
                        .addTextContent(mapper.writeValueAsString(Map.of("source", source, "text", "测试仓库说明")))
                        .isError(false).build()));
        ObjectProvider<McpClientWrapper> mcp = mock(ObjectProvider.class);
        when(mcp.getObject()).thenReturn(client);
        var retriever = mock(HybridRetriever.class);
        var assistant = new SalesAssistant(retriever, model, mcp, mapper);
        try {
            var result = assistant.chat(new ChatRequest("repository", "读取仓库 README"));
            assertEquals("这是测试仓库。", result.answer());
            assertEquals(List.of(source), result.sources());
            assertTrue(sawRepositorySchema.get(), "模型应收到实际已注册的工具定义");
            assertTrue(result.steps().stream().anyMatch(step -> step.contains("工具成功")));
            verify(client).callTool("listRepositoryFiles", Map.of());
            verifyNoInteractions(retriever);
        } finally { assistant.close(); server.stop(0); }
    }

}
