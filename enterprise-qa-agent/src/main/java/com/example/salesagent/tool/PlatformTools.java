// 将模型、向量化、RAG 和业务数据的 MCP 适配器归入工具包。
package com.example.salesagent.tool;

// 导入统一模型网关及敏感问题守卫。
import com.example.salesagent.agent.*;
// 导入 Embedding 客户端接口，工具无需依赖具体向量模型实现。
import com.example.salesagent.bailian.EmbeddingClient;
// 导入 JSON 映射器，编码请求并解析主应用响应。
import com.fasterxml.jackson.databind.ObjectMapper;
// 导入 URI 类型，创建主应用 HTTP 请求地址。
import java.net.URI;
// 导入 JDK HTTP 请求、客户端和响应读取器。
import java.net.http.*;
// 导入超时时长类型。
import java.time.Duration;
// 导入列表和键值映射，构造模型输入及 JSON 请求正文。
import java.util.*;

/** MCP 抽象模型、向量化、检索和业务数据；RAG 通过 app API 访问，避免双进程同时写索引。 */
// 将不同能力统一包装为可供 MCP 服务注册的工具方法。
public class PlatformTools {
    // 保存文本生成网关。
    private final LlmGateway llm;
    // 保存文本向量化客户端。
    private final EmbeddingClient embedding;
    // 保存 app 进程根地址，检索与 SQL 通过 HTTP 调用该进程。
    private final String appUrl;
    // 保存请求序列化和响应解析器。
    private final ObjectMapper mapper;

    // 注入模型客户端、主应用地址及 JSON 映射器。
    public PlatformTools(LlmGateway llm, EmbeddingClient embedding, String appUrl, ObjectMapper mapper) {
        // 记录统一文本生成入口。
        this.llm = llm;
        // 记录统一向量生成入口。
        this.embedding = embedding;
        // 记录索引和业务查询所在应用地址。
        this.appUrl = appUrl;
        // 记录 JSON 编解码依赖。
        this.mapper = mapper;
    }

    // 将文本生成能力封装为具有来源标记的工具结果。
    public Map<String, Object> generate(String prompt) {
        // 在请求模型前统一检查长度、空值和敏感意图。
        check(prompt);
        // 返回模型文本及来源标记，供调用方明确结果性质。
        return Map.of("text", llm.generate(prompt), "source", "model://bailian");
    }

    // 将单条输入封装成 Embedding 客户端支持的批量文本列表。
    public Map<String, Object> embed(String text) {
        // 向量化前应用相同的输入和敏感意图检查。
        check(text);
        // 返回向量列表，并标明向量来自百炼 Embedding 服务。
        return Map.of("vectors", embedding.embed(List.of(text)), "source", "embedding://bailian");
    }

    // 查询 app 进程维护的知识索引，MCP 进程不自行打开写索引。
    public Object search(String query) {
        // 检索前检查问题是否合法。
        check(query);
        // 通过 JSON POST 使用检索缓存，返回真实知识证据。
        return post("/api/retrieval", Map.of("query", query, "bypassCache", false));
    }

    // 通过主应用执行自然语言只读数据查询。
    public Object data(String question, boolean export) {
        // 模型生成 SQL 前先应用统一输入限制。
        check(question);
        // 将问题及 Excel 导出选项发送到主应用的数据查询 API。
        return post("/api/sql/query", Map.of("question", question, "export", export));
    }

    // 为所有平台工具集中执行输入边界和敏感问题检查。
    private void check(String text) {
        // 拒绝缺失、空白和超过 2000 字符的输入。
        if (text == null || text.isBlank() || text.length() > 2000)
            // 避免无效调用产生模型成本或过大的请求。
            throw new IllegalArgumentException("输入不合法");
        // 敏感问题守卫存在拒绝原因时，不访问任何后续平台能力。
        if (new SensitiveQuestionGuard().rejection(text) != null)
            // 固定拒绝文本由 MCP 错误包装器安全回传。
            throw new IllegalArgumentException("敏感问题被拦截");
    }

    // 把通用对象编码为 JSON 并 POST 到主问答应用。
    private Object post(String path, Object body) {
        // 为此次调用创建客户端并设置 5 秒连接超时，结束后自动关闭。
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            // 拼接固定 API 路径，设置 90 秒请求超时及 JSON 内容类型。
            var req = HttpRequest.newBuilder(URI.create(appUrl + path)).timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                // 将参数对象序列化为正文，使用 POST 调用主应用接口。
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            // 同步发送请求，并把响应正文读取为字符串。
            var response = client.send(req, HttpResponse.BodyHandlers.ofString());
            // HTTP 非 200 表示主应用没有成功完成此次工具请求。
            if (response.statusCode() != 200)
                // 将状态码交给 Agent 判断是否重试或解释能力不可用。
                throw new IllegalStateException("问答服务返回 HTTP " + response.statusCode());
            // 解析 JSON 树返回，不假设检索和 SQL 两种 API 的响应结构相同。
            return mapper.readTree(response.body());
        // 外部取消或线程中断需要恢复中断状态。
        } catch (InterruptedException e) {
            // 让上层继续感知取消信号。
            Thread.currentThread().interrupt();
            // 保留原始异常作为 cause，便于服务端诊断。
            throw new IllegalStateException("工具请求取消", e);
        // 连接失败、响应读取和 JSON 解析错误统一标为主应用不可用。
        } catch (java.io.IOException e) {
            // 保留异常链，但提供稳定可读的工具错误文案。
            throw new IllegalStateException("问答服务不可用", e);
        }
    }
}
