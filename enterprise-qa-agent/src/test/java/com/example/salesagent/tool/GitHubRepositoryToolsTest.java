// 将 GitHub 文件读取和错误传播测试归入工具测试包。
package com.example.salesagent.tool;

// 导入 JSON 解析器，供被测工具解析模拟 GitHub 响应。
import com.fasterxml.jackson.databind.ObjectMapper;
// 导入 JDK 轻量 HTTP 服务，无需访问真实 GitHub 网络。
import com.sun.net.httpserver.HttpServer;
// 导入本地套接字地址，使用随机空闲端口。
import java.net.InetSocketAddress;
// 导入 UTF-8 编码，构造中文文件内容和 JSON 响应字节。
import java.nio.charset.StandardCharsets;
// 导入 Base64，与 GitHub contents API 的文件响应格式一致。
import java.util.Base64;
// 导入 JUnit 测试标记。
import org.junit.jupiter.api.Test;
// 导入相等、布尔和异常断言。
import static org.junit.jupiter.api.Assertions.*;

// 验证源码固定在指定提交读取，并拒绝敏感路径和上游错误。
class GitHubRepositoryToolsTest {
    // 标记固定 SHA 读取和路径限制测试。
    @Test
    // 模拟 HTTP 服务及请求执行可能抛出异常，交给 JUnit 报告。
    void readsFileAtExactCommitAndKeepsSource() throws Exception {
        // 创建仅监听回环地址的随机端口服务，测试不会访问外部仓库。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 模拟默认演示仓库的 README contents 接口。
        server.createContext("/repos/codedXX/redis-cache-demo/contents/README.md", exchange -> {
            // 检查请求使用 ref=完整 SHA，避免分支变化导致源码与引用不一致。
            assertEquals("ref=" + "a".repeat(40), exchange.getRequestURI().getQuery());
            // 用 Base64 封装 UTF-8 中文文件，匹配 GitHub 响应协议。
            String encoded = Base64.getEncoder().encodeToString("演示仓库".getBytes(StandardCharsets.UTF_8));
            // 构造普通文件、Base64 编码和大小字段，以及实际内容。
            byte[] body = ("{\"type\":\"file\",\"encoding\":\"base64\",\"size\":12,\"content\":\"" + encoded + "\"}").getBytes(StandardCharsets.UTF_8);
            // HTTP 200 表示文件查询成功，并声明正文字节数。
            exchange.sendResponseHeaders(200, body.length);
            // 把模拟 GitHub JSON 写入网络响应。
            exchange.getResponseBody().write(body);
            // 关闭交换对象，确保响应完整结束。
            exchange.close();
        });
        // 启动模拟服务后才能供 HTTP 客户端调用。
        server.start();
        // 即便断言或请求失败，也需要在 finally 关闭服务。
        try {
            // 将工具的 API 根地址替换为本地服务，且不使用认证 token。
            var tools = new GitHubRepositoryTools("http://127.0.0.1:" + server.getAddress().getPort(), "", new ObjectMapper());
            // 请求 README 在固定 40 位 SHA 下的内容。
            var result = tools.readRepositoryFile("README.md", "a".repeat(40));
            // 检查 Base64 和 UTF-8 解码保留了中文文本。
            assertEquals("演示仓库", result.get("text"));
            // 检查来源链接包含同一个 SHA，能够回到这份具体源码。
            assertTrue(result.get("source").toString().contains("/blob/" + "a".repeat(40)));
            // 目录穿越与环境文件路径必须在发出 HTTP 请求前被拒绝。
            assertThrows(IllegalArgumentException.class, () -> tools.readRepositoryFile("../.env", "a".repeat(40)));
            // 敏感凭证文件也不能作为源码工具输入。
            assertThrows(IllegalArgumentException.class, () -> tools.readRepositoryFile("secret.pem", "a".repeat(40)));
        // 无论测试结果如何，都释放本地 HTTP 端口。
        } finally {
            // 立即停止服务，避免影响其他测试。
            server.stop(0);
        }
    }

    // 标记 GitHub 不可用响应传播测试。
    @Test
    // 本地 HTTP 服务创建可能抛出 I/O 异常。
    void propagatesUnavailableRepository() throws Exception {
        // 使用新的随机端口，隔离上一个测试的请求状态。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 根处理器覆盖所有请求路径，模拟仓库接口返回 404。
        server.createContext("/", e -> {
            // 发送无正文的 404 响应。
            e.sendResponseHeaders(404, -1);
            // 结束本次模拟交换。
            e.close();
        });
        // 启动模拟服务接受工具请求。
        server.start();
        // 使用 finally 保证错误断言也不会留下服务线程。
        try {
            // 注入本地 API 地址与空 token，避免调用真实网络。
            var tools = new GitHubRepositoryTools("http://127.0.0.1:" + server.getAddress().getPort(), "", new ObjectMapper());
            // 上游 404 必须变成状态异常，不能伪造一个空文件树当作成功。
            assertThrows(IllegalStateException.class, tools::listRepositoryFiles);
        // 测试完成时清理本地模拟服务。
        } finally {
            // 立即停止 HTTP 服务。
            server.stop(0);
        }
    }
}
