package com.example.salesagent.config;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 AgentScope HTTP 适配器，防止 SDK 之间不同的 base URL 约定导致路径错误。 */
class AgentModelTest {
    @Test void sendsChatToExactlyOneApiV1Prefix() throws Exception {
        var path = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            path.set(e.getRequestURI().getPath()); e.getRequestBody().readAllBytes();
            byte[] body = "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        }); server.start();
        try {
            var p = new DemoProperties(new DemoProperties.Bailian("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    "qwen3.7-flash", "qwen3.7-text-embedding", "qwen3.7-text-rerank", 1024, 5), null, null, null, null);
            var model = new AgentConfiguration().chatModel(p);
            var answer = ReActAgent.builder().name("test").model(model).build()
                    .call(Msg.builder().role(MsgRole.USER).textContent("hi").build()).block(Duration.ofSeconds(10));
            assertNotNull(answer); assertEquals("hello", answer.getTextContent());
            assertEquals("/api/v1/services/aigc/multimodal-generation/generation", path.get());
        } finally { server.stop(0); }
    }
}
