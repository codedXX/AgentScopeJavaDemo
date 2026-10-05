// 将独立检索 HTTP 接口放入 API 包。
package com.example.salesagent.controller;

// 导入携带检索证据、来源和耗时的结果对象。
import com.example.salesagent.model.RagResult;
// 导入负责查询扩展、混合召回和重排的检索服务。
import com.example.salesagent.rag.HybridRetriever;
// 导入按运行环境启用 Bean 的注解。
import org.springframework.context.annotation.Profile;
// 导入 JSON 请求体和 HTTP 路由绑定注解。
import org.springframework.web.bind.annotation.*;

// 返回值由 Spring 自动序列化为 JSON。
@RestController
// 检索索引属于 app 进程，MCP 通过 HTTP 使用该接口。
@Profile("app")
// 对外提供仅检索证据、不生成最终回答的接口。
public class RetrievalController {
    // query 是检索问题；bypassCache 为 true 时要求重新计算结果。
    public record Request(String query, boolean bypassCache) {}
    // 保存混合检索器，复用已初始化的索引和模型客户端。
    private final HybridRetriever retriever;

    // 通过构造器注入检索服务。
    public RetrievalController(HybridRetriever retriever) {
        // 保留检索器供各请求复用。
        this.retriever = retriever;
    }

    // 以 POST 接收检索文本和缓存控制参数。
    @PostMapping("/api/retrieval")
    // 将 JSON 正文绑定到检索请求记录。
    public RagResult retrieve(@RequestBody Request request) {
        // 拒绝缺失、空白或超过 2000 字符的问题，避免无效检索和异常负载。
        if (request.query() == null || request.query().isBlank() || request.query().length() > 2000)
            // 由全局异常处理器统一返回参数错误。
            throw new IllegalArgumentException("查询不合法");
        // 在访问知识库前检查是否包含读取密钥等敏感意图。
        if (new com.example.salesagent.agent.SensitiveQuestionGuard().rejection(request.query()) != null)
            // 将敏感输入作为参数错误拒绝，不进入检索器。
            throw new IllegalArgumentException("敏感问题被拦截");
        // 将问题和缓存策略一起传入检索器，并返回其原始证据结果。
        return retriever.retrieve(request.query(), request.bypassCache());
    }
}
