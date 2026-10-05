// 将只读 GitHub 仓库访问封装归入工具包。
package com.example.salesagent.tool;

// 导入 JSON 节点类型，读取仓库元信息、文件树和文件响应。
import com.fasterxml.jackson.databind.JsonNode;
// 导入统一 JSON 解析器。
import com.fasterxml.jackson.databind.ObjectMapper;
// 导入 URI 类型，用于构造 GitHub API 请求。
import java.net.URI;
// 导入 JDK HTTP 客户端、请求和响应读取器。
import java.net.http.*;
// 导入明确的 UTF-8 编码，正确解码源码和 URL 路径。
import java.nio.charset.StandardCharsets;
// 导入请求超时时长类型。
import java.time.Duration;
// 导入列表、映射、Base64 及区域无关大小写转换工具。
import java.util.*;

/** 只读指定仓库。按 commit SHA 固定版本，回答中的链接可回到同一份源码。 */
// 在路径白名单和固定提交约束下暴露仓库文件树与源码读取能力。
public class GitHubRepositoryTools {
    // 保存 GitHub API 中的仓库资源路径，例如 /repos/owner/repository。
    private final String REPO;
    // 保存仓库网页地址，用于生成可追溯的来源链接。
    private final String WEB;
    // 保存 API 根地址，可在测试时替换为本地模拟服务器。
    private final String apiUrl;
    // 保存可选的认证令牌，调用失败时不将它回传给用户。
    private final String token;
    // 保存 JSON 解析器，解析 GitHub 标准响应。
    private final ObjectMapper mapper;
    // 复用客户端，并限制建立 GitHub 连接最多等待 20 秒。
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    // 提供使用默认演示仓库的便捷构造器。
    public GitHubRepositoryTools(String apiUrl, String token, ObjectMapper mapper) {
        // 委托完整构造器，保证两种入口使用同一套仓库名校验。
        this(apiUrl, token, mapper, "codedXX/redis-cache-demo");
    }

    // 接收用户配置的 owner/repository，建立该仓库的只读访问边界。
    public GitHubRepositoryTools(String apiUrl, String token, ObjectMapper mapper, String repository) {
        // 仓库必须由两段有限长度的安全名称组成，拒绝任意 URL 或多段路径。
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
            // 配置错误直接提示正确的仓库格式。
            throw new IllegalArgumentException("仓库格式应为owner/repository");
        // 拼出固定仓库 API 前缀，后续工具不能切换到其他仓库。
        this.REPO = "/repos/" + repository;
        // 拼出网页仓库地址，结果引用回到同一仓库。
        this.WEB = "https://github.com/" + repository;
        // 保存 GitHub API 根地址。
        this.apiUrl = apiUrl;
        // 保存可选 token，用于私有仓库或增加请求额度。
        this.token = token;
        // 保存解析器实例。
        this.mapper = mapper;
    }

    // 列出默认分支在当前提交版本下允许读取的源码和文档路径。
    public Map<String, Object> listRepositoryFiles() {
        // 读取仓库元信息，获取默认分支名称。
        var repo = get(REPO);
        // GitHub 的 default_branch 字段给出本次需要固定的分支。
        String branch = repo.path("default_branch").asText();
        // 将默认分支解析为 40 位 commit SHA，避免后续读取时分支内容漂移。
        String sha = get(REPO + "/commits/" + encode(branch)).path("sha").asText();
        // 拒绝缺失或非标准 SHA，保证来源链接和读取参数有效。
        requireSha(sha);
        // 按固定 SHA 递归获取该版本仓库文件树。
        var tree = get(REPO + "/git/trees/" + sha + "?recursive=1");
        // 保存通过文件类型和敏感路径检查的候选文件。
        List<String> paths = new ArrayList<>();
        // 遍历 GitHub 文件树中的所有条目。
        for (var item : tree.path("tree")) {
            // blob 表示普通文件，同时要求路径属于源码和文档白名单。
            if (item.path("type").asText().equals("blob") && allowed(item.path("path").asText()))
                // 将通过校验的相对路径提供给 Agent 选择读取。
                paths.add(item.path("path").asText());
        }
        // GitHub 递归树本身截断或文件数量超过本工具上限，都需向调用方标记。
        boolean truncated = tree.path("truncated").asBoolean() || paths.size() > 500;
        // 返回固定提交和最多 500 个文件，避免工具结果过长。
        return Map.of("commitSha", sha, "files", paths.stream().limit(500).toList(),
                // 让调用方知道文件树是否完整，并附上同一 SHA 的网页来源。
                "truncated", truncated, "source", WEB + "/tree/" + sha);
    }

    // 读取文件树返回的路径与提交版本，保持内容和来源链接一致。
    public Map<String, Object> readRepositoryFile(String path, String commitSha) {
        // 在发出网络请求前拒绝敏感路径、目录穿越和不允许的文件类型。
        if (!allowed(path))
            // 提示 Agent 只能选择允许的源码或文档文件。
            throw new IllegalArgumentException("仅允许仓库中的普通源码/文档文件，禁止敏感路径");
        // 提交参数必须是从文件树获得的 40 位 SHA。
        requireSha(commitSha);
        // 按路径段进行 URL 编码，保留目录分隔符，避免特殊字符改变请求结构。
        String encodedPath = Arrays.stream(path.split("/")).map(GitHubRepositoryTools::encode).reduce((a, b) -> a + "/" + b).orElseThrow();
        // contents API 的 ref 指定固定提交，不使用可变化的分支名。
        var file = get(REPO + "/contents/" + encodedPath + "?ref=" + commitSha);
        // 只读取 GitHub 以 Base64 返回的普通文件。
        if (!file.path("type").asText().equals("file") || !file.path("encoding").asText().equals("base64"))
            // 拒绝目录、符号链接或其他非标准文件响应。
            throw new IllegalArgumentException("不是可读取的文本文件");
        // 限制原始文件最大 1 MB，避免一次解码过大内容。
        if (file.path("size").asLong() > 1_000_000)
            // 让调用方换一个更小的源码文件。
            throw new IllegalArgumentException("文件超过 1MB，请选择更小的文件");
        // MIME Base64 解码器兼容 GitHub content 字段中的换行。
        byte[] bytes = Base64.getMimeDecoder().decode(file.path("content").asText());
        // 使用 UTF-8 将文件字节解码成可供模型阅读的文本。
        String content = new String(bytes, StandardCharsets.UTF_8);
        // 空字符通常意味着二进制内容，不把它交给问答模型。
        if (content.indexOf('\0') >= 0)
            // 明确拒绝二进制文件。
            throw new IllegalArgumentException("不支持二进制文件");
        // 以字符边界截断，UTF-8 输出不超过约 20KB，避免半个汉字。
        // 最多保留 6500 个 UTF-16 代码单元，控制工具返回长度。
        int end = Math.min(content.length(), 6500);
        // 若最后一个代码单元是代理对的高位部分，就不能只返回半个 Unicode 字符。
        if (end > 0 && Character.isHighSurrogate(content.charAt(end - 1)))
            // 将截断点前移一位，保持代理对完整。
            end--;
        // 返回路径、固定 SHA 和截断后的文本，方便回答引用精确版本。
        return Map.of("path", path, "commitSha", commitSha, "text", content.substring(0, end),
                // 明确说明是否截断，并构造同一提交下此文件的网页地址。
                "truncated", end < content.length(), "source", WEB + "/blob/" + commitSha + "/" + encodedPath);
    }

    // 统一执行 GitHub GET 请求，并把响应正文解析为 JSON。
    private JsonNode get(String path) {
        // 对网络异常和取消执行提供统一工具错误说明。
        try {
            // 以固定 API 根地址发起请求，设置 20 秒超时。
            var request = HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(Duration.ofSeconds(20))
                    // 使用 GitHub JSON 媒体类型与明确的 User-Agent。
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "sales-agent-demo");
            // 只有配置了非空 token 时才发送认证头。
            if (token != null && !token.isBlank())
                // 认证信息只写入请求头，不写入来源或错误消息。
                request.header("Authorization", "Bearer " + token);
            // 构建 GET 请求并同步读取响应文本。
            var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            // 非 200 状态不能作为有效仓库数据继续解析。
            if (response.statusCode() != 200)
                // 向用户提供状态码及权限、限流检查提示。
                throw new IllegalStateException("GitHub HTTP " + response.statusCode() + "：请检查仓库权限或限流");
            // 将 GitHub 标准 JSON 响应转换成树节点供调用方法读取。
            return mapper.readTree(response.body());
        // HTTP 等待被中断时恢复线程中断标记。
        } catch (InterruptedException ex) {
            // 使上层取消机制继续感知中断信号。
            Thread.currentThread().interrupt();
            // 工具错误文本只说明取消，不附带敏感请求上下文。
            throw new IllegalStateException("GitHub 请求已取消");
        // 网络连接、正文读取或 JSON 解析失败走同一错误分支。
        } catch (java.io.IOException ex) {
            // 对外隐藏网络堆栈和响应原文。
            throw new IllegalStateException("GitHub 网络或响应异常");
        }
    }

    // 校验固定提交标识，拒绝分支名和非 SHA 参数。
    private static void requireSha(String sha) {
        // GitHub 完整 SHA 使用 40 位十六进制字符。
        if (sha == null || !sha.matches("[0-9a-fA-F]{40}"))
            // 指导 Agent 先发现文件树，再使用其中的 commitSha。
            throw new IllegalArgumentException("请先获取文件树中的 commitSha");
    }

    // 通过路径与扩展名白名单控制可读取的文件范围。
    private static boolean allowed(String path) {
        // 拒绝空路径、绝对路径、目录穿越和反斜杠路径变体。
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\"))
            // 非普通仓库相对路径不能读取。
            return false;
        // 使用 Locale.ROOT 转小写，避免系统区域设置改变敏感匹配结果。
        String lower = path.toLowerCase(Locale.ROOT);
        // 拒绝环境文件、凭证、私有材料及 Git 元数据目录。
        if (lower.contains(".env") || lower.contains("secret") || lower.contains("credential") || lower.contains("private") || lower.startsWith(".git/"))
            // 即使扩展名符合源码类型，也不能绕过敏感路径限制。
            return false;
        // 只允许常见源码、配置、文档格式和 Dockerfile，排除证书等文件。
        return lower.matches(".*\\.(java|md|txt|xml|yml|yaml|properties|json|sql|gradle|kt|gitignore)$") || lower.equals("dockerfile");
    }

    // 把单个路径段或分支名安全编码为 URL 部分。
    private static String encode(String text) {
        // URLEncoder 默认将空格编码为 +，替换成路径中明确的 %20。
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
