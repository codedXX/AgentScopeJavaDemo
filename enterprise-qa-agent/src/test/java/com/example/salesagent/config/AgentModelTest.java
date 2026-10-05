// 测试类位于生产组件同包，可直接检查包内可见的状态与工具轨迹。
package com.example.salesagent.config;
// 本地 HTTP 服务提供模型接口替身，让真实 AgentScope SDK 执行请求。
import com.sun.net.httpserver.HttpServer;
// 真实 ReActAgent 驱动模型 HTTP 适配器而非直接伪造其调用结果。
import io.agentscope.core.ReActAgent;
// 引入 AgentScope 消息角色、文本输出与工具调用块。
import io.agentscope.core.message.*;
// 指定服务器只监听本机并由系统分配空闲端口。
import java.net.InetSocketAddress;
// 显式指定 UTF-8，确保模型响应字节编码一致。
import java.nio.charset.StandardCharsets;
// 指定测试中会话 TTL 或模型调用等待时限。
import java.time.Duration;
// 以线程安全方式从 HTTP 处理线程捕获实际请求路径。
import java.util.concurrent.atomic.AtomicReference;
// Test 标记需要由 JUnit 执行的测试方法。
import org.junit.jupiter.api.Test;
// 引入断言方法，验证结果、异常与隔离边界。
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 AgentScope HTTP 适配器，防止 SDK 之间不同的 base URL 约定导致路径错误。 */
// 验证生产模型适配器的请求路径只含一次 /api/v1 前缀。
class AgentModelTest {
    // 使用真实 SDK 发起聊天请求，检查端点路径与返回正文。
    @Test void sendsChatToExactlyOneApiV1Prefix() throws Exception {
        // 原子引用保存 HTTP 处理线程看到的真实请求路径。
        var path = new AtomicReference<String>();
        // 建立仅监听本机随机端口的模型 HTTP 替身。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 注册根路径处理器，捕获模型请求并返回固定文本。
        server.createContext("/", e -> {
            // 记录 URI 路径并读完请求体，保证当前请求完整消费。
            path.set(e.getRequestURI().getPath()); e.getRequestBody().readAllBytes();
            // 返回符合模型 API 格式的 hello 响应，并使用 UTF-8 编码为字节。
            byte[] body = "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            // 设置 application/json 让 SDK 按 JSON 处理响应。
            e.getResponseHeaders().set("Content-Type", "application/json");
            // 发送成功状态与内容长度，写入响应后关闭交换。
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        // 完成处理器注册并启动测试服务器。
        }); server.start();
        // 测试模型调用和端点断言完成后，finally 统一停止本地 HTTP 服务器。
        try {
            // 基础 URL 特意携带 /api/v1，检验生产工厂是否去除 SDK 会自动补的后缀。
            var p = new DemoProperties(new DemoProperties.Bailian("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    // 设置实际聊天模型参数，其他功能配置为 null 因为本测试只调用聊天。
                    "qwen3.7-flash", "qwen3.7-text-embedding", "qwen3.7-text-rerank", 1024, 5), null, null, null, null);
            // 通过生产 AgentConfiguration 创建真实 DashScope 模型适配器。
            var model = new AgentConfiguration().chatModel(p);
            // 创建使用该适配器的实际 ReActAgent。
            var answer = ReActAgent.builder().name("test").model(model).build()
                    // 发送 USER 消息并最多等待十秒，让真实 SDK 生成 HTTP 请求和解析响应。
                    .call(Msg.builder().role(MsgRole.USER).textContent("hi").build()).block(Duration.ofSeconds(10));
            // 断言结果非空且正文为 hello，确认不仅路径正确，响应也能被解析。
            assertNotNull(answer); assertEquals("hello", answer.getTextContent());
            // 断言请求恰为多模态生成路径且只有一个 /api/v1 前缀。
            assertEquals("/api/v1/services/aigc/multimodal-generation/generation", path.get());
        // 无论断言是否成功，都停止本地 HTTP 替身。
        } finally { server.stop(0); }
    }
}
