package com.example.salesagent.agent;

import com.example.salesagent.attachment.ImageAttachmentStore;
import com.example.salesagent.config.*;
import com.example.salesagent.model.ChatRequest;
import com.example.salesagent.rag.HybridRetriever;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本地 HTTP 替身检查真正的 AgentScope 多模态请求，不消耗模型额度。 */
class ImageChatTest {
    @TempDir Path temp;

    @Test void imageBytesReachRouterAndAnswerAndSurviveRestartAndFollowup() throws Exception {
        try (var fixture = new Fixture()) {
            var uploaded = fixture.upload();
            fixture.source = "image://one/" + uploaded.id();
            var first = fixture.assistant().chat(new ChatRequest("one", "", List.of(uploaded.id())));
            assertEquals("图片中有一个方块。", first.answer());
            assertEquals(List.of(fixture.source), first.sources());
            assertTrue(first.steps().stream().anyMatch(s -> s.contains("图片分析")));
            assertImageInEveryRequest(fixture.requests);
            var saved = fixture.conversations.load("one");
            assertEquals(List.of(uploaded.id()), saved.turns().getFirst().imageIds());
            assertEquals("", saved.turns().getFirst().text());
            fixture.requests.clear();
            // 使用新的助手和落盘历史恢复，不共享之前的 Agent 内存。
            assertEquals("图片中有一个方块。", fixture.assistant().chat(new ChatRequest("one", "刚才图片里是什么？")).answer());
            assertImageInEveryRequest(fixture.requests);
            verifyNoInteractions(fixture.retriever, fixture.mcp);
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> fixture.assistant().chat(new ChatRequest("two", "看图", List.of(uploaded.id()))));
        }
    }

    @Test void imageDoesNotSubstituteForDatabaseOrLiveBusinessEvidence() throws Exception {
        try (var fixture = new Fixture()) {
            var uploaded = fixture.upload();
            fixture.source = "image://one/" + uploaded.id();
            fixture.intent.set("DATA");
            assertTrue(fixture.assistant().chat(new ChatRequest("one", "数据库销量是多少", List.of(uploaded.id())))
                    .answer().contains("未取得有效数据库查询结果"));
            fixture.intent.set("BUSINESS");
            var client = mock(McpClientWrapper.class);
            when(client.getName()).thenReturn("empty-tools");
            when(client.initialize()).thenReturn(reactor.core.publisher.Mono.empty());
            when(client.listTools()).thenReturn(reactor.core.publisher.Mono.just(List.of()));
            when(fixture.mcp.getObject()).thenReturn(client);
            assertTrue(fixture.assistant().chat(new ChatRequest("one", "当前库存是多少", List.of(uploaded.id())))
                    .answer().contains("当前价格和库存无法确认"));
        }
    }

    @Test void imagePlanStepReceivesAttachmentAndRetainsHistory() throws Exception {
        try (var fixture = new Fixture()) {
            var uploaded = fixture.upload();
            fixture.source = "image://one/" + uploaded.id();
            fixture.intent.set("MULTI_TASK");
            var result = fixture.assistant().chat(new ChatRequest("one", "识别图片并总结", List.of(uploaded.id())));
            assertEquals("图片中有一个方块。", result.answer());
            assertEquals(List.of(fixture.source), result.sources());
            assertTrue(fixture.requests.stream().filter(r -> r.toString().contains("执行计划中的一步"))
                    .anyMatch(r -> hasImage(r.path("input").path("messages"))));
            assertEquals(List.of(uploaded.id()), fixture.conversations.load("one").turns().getFirst().imageIds());
            verifyNoInteractions(fixture.retriever, fixture.mcp);
        }
    }

    @Test void historyCompressionReadsImagesBeforeReplacingOldTurnsWithSummary() throws Exception {
        try (var fixture = new Fixture()) {
            var uploaded = fixture.upload();
            fixture.source = "image://one/" + uploaded.id();
            var turns = new ArrayList<PersistentConversationStore.Turn>();
            for (int i = 0; i < 10; i++) {
                turns.add(new PersistentConversationStore.Turn("user", "问题" + i,
                        i == 0 ? List.of(uploaded.id()) : List.of()));
                turns.add(new PersistentConversationStore.Turn("assistant", "回答" + i));
            }
            fixture.conversations.save("one", new PersistentConversationStore.Conversation("", turns));
            fixture.assistant().chat(new ChatRequest("one", "第一张图是什么？"));
            var saved = fixture.conversations.load("one");
            assertEquals(20, saved.turns().size());
            assertEquals("早期图片中有一个方块。", saved.summary());
            assertTrue(fixture.requests.stream().filter(r -> r.toString().contains("概括历史中的实体"))
                    .anyMatch(r -> hasImage(r.path("input").path("messages"))));
        }
    }

    @Test void oldTextOnlyHistoryRemainsReadable() throws Exception {
        var mapper = new ObjectMapper();
        var old = mapper.readValue("{\"summary\":\"\",\"turns\":[{\"role\":\"user\",\"text\":\"你好\"}]}",
                PersistentConversationStore.Conversation.class);
        assertTrue(old.turns().getFirst().imageIds().isEmpty());
        var store = new PersistentConversationStore(temp);
        store.save("legacy", old);
        assertEquals(old, new PersistentConversationStore(temp).load("legacy"));
    }

    private static void assertImageInEveryRequest(List<JsonNode> requests) {
        assertTrue(requests.size() >= 2);
        for (var request : requests) assertTrue(hasImage(request.path("input").path("messages")), request.toString());
    }

    private static boolean hasImage(JsonNode messages) {
        for (var message : messages) for (var block : message.path("content")) {
            if (block.path("image").asText().startsWith("data:image/png;base64,")) return true;
        }
        return false;
    }

    private class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<String> intent = new AtomicReference<>("IMAGE");
        final HttpServer server;
        final ImageAttachmentStore images = new ImageAttachmentStore(temp.resolve("images"));
        final PersistentConversationStore conversations = new PersistentConversationStore(temp.resolve("sessions"));
        final HybridRetriever retriever = mock(HybridRetriever.class);
        final ObjectProvider<DashScopeChatModel> models;
        final ObjectProvider<McpClientWrapper> mcp;
        String source;

        @SuppressWarnings("unchecked") Fixture() throws Exception {
            models = mock(ObjectProvider.class);
            mcp = mock(ObjectProvider.class);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
                requests.add(request);
                String system = request.path("input").path("messages").get(0).path("content").toString();
                Object result;
                if (system.contains("把问题分为")) result = Map.of("intent", intent.get(), "query", "分析图片中的方块");
                else if (system.contains("概括历史中的实体")) result = Map.of("text", "早期图片中有一个方块。");
                else if (system.contains("将用户问题拆为")) result = Map.of("steps", List.of(
                        Map.of("id", "a", "action", "IMAGE", "question", "识别图片", "dependsOn", List.of())));
                else result = Map.of("answer", "图片中有一个方块。", "sources", List.of(source, "image://invented"));
                var call = Map.of("id", UUID.randomUUID().toString(), "type", "function", "function",
                        Map.of("name", "generate_response", "arguments", mapper.writeValueAsString(Map.of("response", result))));
                var message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(call));
                byte[] bytes = mapper.writeValueAsBytes(Map.of("output", Map.of("choices", List.of(
                        Map.of("finish_reason", "tool_calls", "message", message))),
                        "usage", Map.of("input_tokens", 1, "output_tokens", 1)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            var properties = new DemoProperties(new DemoProperties.Bailian("test", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen3.7-flash", "unused", "unused", 1024, 5), null, null, null, null);
            when(models.getObject()).thenReturn(new AgentConfiguration().chatModel(properties));
        }

        ImageAttachmentStore.Uploaded upload() throws Exception {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return images.upload("one", bytes.toByteArray());
        }

        SalesAssistant assistant() {
            var properties = mock(EnterpriseProperties.class);
            when(properties.planningEnabled()).thenReturn(true);
            var llm = new LlmGateway(models);
            return new SalesAssistant(retriever, models, mcp, mapper, conversations, new TaskPlanner(llm), llm, null, properties, images);
        }

        public void close() { server.stop(0); }
    }
}
