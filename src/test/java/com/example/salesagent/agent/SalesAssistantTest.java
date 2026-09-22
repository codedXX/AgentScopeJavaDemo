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
}
