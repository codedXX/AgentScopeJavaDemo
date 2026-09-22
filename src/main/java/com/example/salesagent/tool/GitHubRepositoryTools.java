package com.example.salesagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 只读指定仓库。按 commit SHA 固定版本，回答中的链接可回到同一份源码。 */
public class GitHubRepositoryTools {
    private static final String REPO = "/repos/codedXX/redis-cache-demo";
    private static final String WEB = "https://github.com/codedXX/redis-cache-demo";
    private final String apiUrl;
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    public GitHubRepositoryTools(String apiUrl, String token, ObjectMapper mapper) {
        this.apiUrl = apiUrl; this.token = token; this.mapper = mapper;
    }
    public Map<String, Object> listRepositoryFiles() {
        var repo = get(REPO);
        String branch = repo.path("default_branch").asText();
        String sha = get(REPO + "/commits/" + encode(branch)).path("sha").asText();
        requireSha(sha);
        var tree = get(REPO + "/git/trees/" + sha + "?recursive=1");
        List<String> paths = new ArrayList<>();
        for (var item : tree.path("tree")) {
            if (item.path("type").asText().equals("blob") && allowed(item.path("path").asText()))
                paths.add(item.path("path").asText());
        }
        boolean truncated = tree.path("truncated").asBoolean() || paths.size() > 500;
        return Map.of("commitSha", sha, "files", paths.stream().limit(500).toList(),
                "truncated", truncated, "source", WEB + "/tree/" + sha);
    }
    public Map<String, Object> readRepositoryFile(String path, String commitSha) {
        if (!allowed(path)) throw new IllegalArgumentException("仅允许仓库中的普通源码/文档文件，禁止敏感路径");
        requireSha(commitSha);
        String encodedPath = Arrays.stream(path.split("/")).map(GitHubRepositoryTools::encode).reduce((a,b) -> a + "/" + b).orElseThrow();
        var file = get(REPO + "/contents/" + encodedPath + "?ref=" + commitSha);
        if (!file.path("type").asText().equals("file") || !file.path("encoding").asText().equals("base64"))
            throw new IllegalArgumentException("不是可读取的文本文件");
        if (file.path("size").asLong() > 1_000_000) throw new IllegalArgumentException("文件超过 1MB，请选择更小的文件");
        byte[] bytes = Base64.getMimeDecoder().decode(file.path("content").asText());
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.indexOf('\0') >= 0) throw new IllegalArgumentException("不支持二进制文件");
        // 以字符边界截断，UTF-8 输出不超过约 20KB，避免半个汉字。
        int end = Math.min(content.length(), 6500);
        if (end > 0 && Character.isHighSurrogate(content.charAt(end - 1))) end--;
        return Map.of("path", path, "commitSha", commitSha, "text", content.substring(0, end),
                "truncated", end < content.length(), "source", WEB + "/blob/" + commitSha + "/" + encodedPath);
    }
    private JsonNode get(String path) {
        try {
            var request = HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "sales-agent-demo");
            if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
            var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("GitHub HTTP " + response.statusCode() + "：请检查仓库权限或限流");
            return mapper.readTree(response.body());
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("GitHub 请求已取消"); }
        catch (java.io.IOException ex) { throw new IllegalStateException("GitHub 网络或响应异常"); }
    }
    private static void requireSha(String sha) {
        if (sha == null || !sha.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("请先获取文件树中的 commitSha");
    }
    private static boolean allowed(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains(".env") || lower.contains("secret") || lower.contains("credential") || lower.contains("private") || lower.startsWith(".git/")) return false;
        return lower.matches(".*\\.(java|md|txt|xml|yml|yaml|properties|json|sql|gradle|kt|gitignore)$") || lower.equals("dockerfile");
    }
    private static String encode(String text) {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
