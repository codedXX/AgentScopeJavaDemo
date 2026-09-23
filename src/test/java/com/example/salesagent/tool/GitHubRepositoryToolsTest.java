package com.example.salesagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 验证仓库工具按固定版本读取文件，并正确处理仓库不可用。 */
class GitHubRepositoryToolsTest {
    /** 读取指定提交里的文件，并保留可追溯的来源。 */
    @Test void readsFileAtExactCommitAndKeepsSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/codedXX/redis-cache-demo/contents/README.md", exchange -> {
            assertEquals("ref=" + "a".repeat(40), exchange.getRequestURI().getQuery());
            String encoded = Base64.getEncoder().encodeToString("演示仓库".getBytes(StandardCharsets.UTF_8));
            byte[] body = ("{\"type\":\"file\",\"encoding\":\"base64\",\"size\":12,\"content\":\"" + encoded + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            GitHubRepositoryTools tools = new GitHubRepositoryTools("http://127.0.0.1:" + server.getAddress().getPort(), "", new ObjectMapper());
            java.util.Map<String, Object> result = tools.readRepositoryFile("README.md", "a".repeat(40));
            assertEquals("演示仓库", result.get("text"));
            assertTrue(result.get("source").toString().contains("/blob/" + "a".repeat(40)));
            assertThrows(IllegalArgumentException.class, () -> tools.readRepositoryFile("../.env", "a".repeat(40)));
            assertThrows(IllegalArgumentException.class, () -> tools.readRepositoryFile("secret.pem", "a".repeat(40)));
        } finally { server.stop(0); }
    }
    /** 仓库不可用时明确报错。 */
    @Test void propagatesUnavailableRepository() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> { e.sendResponseHeaders(404, -1); e.close(); }); server.start();
        try {
            GitHubRepositoryTools tools = new GitHubRepositoryTools("http://127.0.0.1:" + server.getAddress().getPort(), "", new ObjectMapper());
            assertThrows(IllegalStateException.class, tools::listRepositoryFiles);
        } finally { server.stop(0); }
    }
}
