package com.example.salesagent.agent;

import com.example.salesagent.config.*;
import com.example.salesagent.history.*;
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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 模型 HTTP 响应使用确定性替身，AgentScope 的结构化输出、Memory、Hook 链实际执行。 */
class SalesAssistantTest {
    private static final class MemoryHistory implements ChatHistoryStore {
        final Map<String, List<ChatTurn>> data = new HashMap<>();
        boolean failOnAppend;
        @Override public List<ChatSession> listSessions() { return List.of(); }
        @Override public boolean exists(String id) { return data.containsKey(id); }
        @Override public List<ChatTurn> turns(String id) { return List.copyOf(data.getOrDefault(id, List.of())); }
        @Override public List<ChatTurn> recentTurns(String id, int limit) {
            List<ChatTurn> all = turns(id);
            return all.subList(Math.max(0, all.size() - limit), all.size());
        }
        @Override public void append(String id, String question, ChatResponse response) {
            if (failOnAppend) throw new IllegalStateException("数据库写入失败");
            ChatTurn turn = new ChatTurn(1, id, question, response.getAnswer(), response.getSources(), response.getSteps(),
                    response.getRetrievalMs(), response.getTotalMs(), Instant.now());
            data.computeIfAbsent(id, ignored -> new ArrayList<>()).add(turn);
        }
    }

    private static SalesAssistant newAssistant(HybridRetriever retriever, ObjectProvider<DashScopeChatModel> model,
                                               ObjectProvider<McpClientWrapper> mcp, ObjectMapper mapper,
                                               ChatHistoryStore history) {
        SalesAssistant assistant = new SalesAssistant();
        ReflectionTestUtils.setField(assistant, "retriever", retriever);
        ReflectionTestUtils.setField(assistant, "model", model);
        ReflectionTestUtils.setField(assistant, "mcp", mcp);
        ReflectionTestUtils.setField(assistant, "mapper", mapper);
        ReflectionTestUtils.setField(assistant, "history", history);
        return assistant;
    }

    @SuppressWarnings("unchecked")
    @Test void answersFromRagWhenMcpIsDownAndIsolatesHistory() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> routerInputs = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            com.fasterxml.jackson.databind.JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            com.fasterxml.jackson.databind.JsonNode messages = request.path("input").path("messages");
            boolean routing = messages.get(0).path("content").toString().contains("把问题分为");
            if (routing) routerInputs.add(messages.toString());
            Object output = routing ? Map.of("intent", "KNOWLEDGE", "query", "产品A的蛋白质含量")
                    : Map.of("answer", "每袋含15克蛋白质。", "sources", List.of("products.md", "https://invented.invalid"));
            Map<String, Object> toolCall = Map.of("id", UUID.randomUUID().toString(), "type", "function", "function",
                    Map.of("name", "generate_response", "arguments", mapper.writeValueAsString(Map.of("response", output))));
            Map<String, Object> message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall));
            Map<String, Object> response = Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message))),
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1));
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        DemoProperties p = new DemoProperties(new BailianProperties("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "qwen3.7-flash", "qwen3.7-text-embedding", "qwen3.7-text-rerank", 1024, 5), null, null, null, null);
        ObjectProvider<DashScopeChatModel> model = mock(ObjectProvider.class);
        when(model.getObject()).thenReturn(new AgentConfiguration().chatModel(p));
        ObjectProvider<McpClientWrapper> mcp = mock(ObjectProvider.class);
        when(mcp.getObject()).thenThrow(new IllegalStateException("offline"));
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(new RagResult(List.of(new SearchHit(
                new KnowledgeChunk("A", "每袋蛋白质15克", "products.md", 0), .9, "rerank")), false, 10));
        MemoryHistory history = new MemoryHistory();
        SalesAssistant assistant = newAssistant(retriever, model, mcp, mapper, history);
        try {
            ChatResponse first = assistant.chat(new ChatRequest("one", "独特问题标记：产品A的蛋白质含量？"));
            assertEquals(List.of("products.md"), first.getSources());
            assertTrue(first.getSteps().stream().anyMatch(s -> s.contains("MCP 不可用")));
            assertEquals(1, history.turns("one").size());
            assistant.close();
            assistant = newAssistant(retriever, model, mcp, mapper, history);
            assistant.chat(new ChatRequest("one", "它呢？"));
            assistant.chat(new ChatRequest("two", "产品A如何？"));
            assertTrue(routerInputs.get(1).contains("独特问题标记"));
            assertFalse(routerInputs.get(2).contains("独特问题标记"));
            assertEquals(2, history.turns("one").size());
            history.failOnAppend = true;
            SalesAssistant restored = assistant;
            assertThrows(IllegalStateException.class, () -> restored.chat(new ChatRequest("one", "再次追问")));
            assertEquals(2, history.turns("one").size());
        } finally { assistant.close(); server.stop(0); }
    }
    @SuppressWarnings("unchecked")
    @Test void repositoryToolsAreRegisteredOnTheExecutingAgent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String source = "https://github.com/example/repo/blob/" + "a".repeat(40) + "/README.md";
        java.util.concurrent.atomic.AtomicBoolean sawRepositorySchema = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            com.fasterxml.jackson.databind.JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            com.fasterxml.jackson.databind.JsonNode messages = request.path("input").path("messages");
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
            Map<String, Object> toolCall = Map.of("id", UUID.randomUUID().toString(), "type", "function", "function",
                    Map.of("name", name, "arguments", mapper.writeValueAsString(arguments)));
            Map<String, Object> message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall));
            Map<String, Object> response = Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message))),
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1));
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        DemoProperties p = new DemoProperties(new BailianProperties("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "qwen3.7-flash", "unused", "unused", 1024, 5), null, null, null, null);
        ObjectProvider<DashScopeChatModel> model = mock(ObjectProvider.class);
        when(model.getObject()).thenReturn(new AgentConfiguration().chatModel(p));
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("test");
        when(client.initialize()).thenReturn(reactor.core.publisher.Mono.empty());
        io.modelcontextprotocol.spec.McpSchema.JsonSchema schema = new io.modelcontextprotocol.spec.McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        when(client.listTools()).thenReturn(reactor.core.publisher.Mono.just(List.of(
                io.modelcontextprotocol.spec.McpSchema.Tool.builder().name("listRepositoryFiles")
                        .description("List repository").inputSchema(schema).build())));
        when(client.callTool(eq("listRepositoryFiles"), anyMap())).thenReturn(reactor.core.publisher.Mono.just(
                io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder()
                        .addTextContent(mapper.writeValueAsString(Map.of("source", source, "text", "测试仓库说明")))
                        .isError(false).build()));
        ObjectProvider<McpClientWrapper> mcp = mock(ObjectProvider.class);
        when(mcp.getObject()).thenReturn(client);
        HybridRetriever retriever = mock(HybridRetriever.class);
        SalesAssistant assistant = newAssistant(retriever, model, mcp, mapper, new MemoryHistory());
        try {
            ChatResponse result = assistant.chat(new ChatRequest("repository", "读取仓库 README"));
            assertEquals("这是测试仓库。", result.getAnswer());
            assertEquals(List.of(source), result.getSources());
            assertTrue(sawRepositorySchema.get(), "模型应收到实际已注册的工具定义");
            assertTrue(result.getSteps().stream().anyMatch(step -> step.contains("工具成功")));
            verify(client).callTool("listRepositoryFiles", Map.of());
            verifyNoInteractions(retriever);
        } finally { assistant.close(); server.stop(0); }
    }

}
