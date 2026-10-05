// 声明所属包，组织 com.example.salesagent.bailian 的类型并避免类名冲突。
package com.example.salesagent.bailian;

// 引入 ConnectionOptions，用于设置官方 SDK 的连接、读取和写入超时。
import com.alibaba.dashscope.protocol.ConnectionOptions;
// 引入官方 Rerank 客户端、请求参数与排名响应类型。
import com.alibaba.dashscope.rerank.*;
// 引入 DemoProperties，用于读取模型地址、凭证、维度和超时配置。
import com.example.salesagent.config.DemoProperties;
// 引入知识分块、命中证据与检索结果等业务数据类型。
import com.example.salesagent.model.*;
// 引入 Duration，用于配置连接、读写和整体请求超时。
import java.time.Duration;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入 Profile，用于限制组件仅在 app 配置环境启用。
import org.springframework.context.annotation.Profile;
// 引入 Component，用于将模型客户端注册为 Spring Bean。
import org.springframework.stereotype.Component;

// 注册 Spring 组件，并限制其在 app 配置环境中启用。
@Component @Profile("app")
// 使用百炼 Rerank 模型为真实候选分块重新计算相关性并按分数排名。
public class BailianRerankClient implements RerankClient {
    // 保存 Rerank 模型、凭证、地址与超时配置。
    private final DemoProperties.Bailian config;
    // 复用官方 TextReRank SDK 客户端。
    private final TextReRank api;
    // 根据应用百炼配置初始化候选重排序客户端。
    public BailianRerankClient(DemoProperties properties) {
        // 读取百炼模型与连接配置。
        config = properties.bailian();
        // 将超时秒数转换为 Duration。
        var timeout = Duration.ofSeconds(config.timeoutSeconds());
        // 以配置地址构造官方 HTTP Rerank 客户端。
        api = new TextReRank("http", config.baseUrl(), ConnectionOptions.builder()
                // 为连接、读取和写入分别设置同一超时时限。
                .connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).build());
    }
    // 围绕原问题重排序知识候选，最多返回 topK 条命中。
    @Override public List<SearchHit> rank(String query, List<KnowledgeChunk> chunks, int topK) {
        // 空候选直接返回，不消耗远程模型请求。
        if (chunks.isEmpty()) return List.of();
        // 有候选时先校验 API Key。
        BailianCalls.requireKey(config.apiKey());
        // 绑定认证密钥及配置中的 Rerank 模型名。
        var param = TextReRankParam.builder().apiKey(config.apiKey()).model(config.rerankModel())
                // 发送原问题及候选正文列表；正文顺序决定响应 index 的含义。
                .query(query).documents(chunks.stream().map(KnowledgeChunk::text).toList())
                // 请求数量不超过候选总数，并关闭重复返回正文以节省响应体。
                .topN(Math.min(topK, chunks.size())).returnDocuments(false).build();
        // 通过有限重试机制执行官方 Rerank 请求。
        var result = BailianCalls.call(() -> api.call(param));
        // 拒绝空结果或缺少 output 的模型响应。
        if (result == null || result.getOutput() == null) throw new IllegalStateException("Rerank 响应为空");
        // 把响应下标和分数映射回真实知识分块。
        return map(result.getOutput().getResults(), chunks, topK);
    }
    // 将模型相关性响应转换成应用命中，验证每条结果并截取 TopK。
    static List<SearchHit> map(List<TextReRankOutput.Result> results, List<KnowledgeChunk> chunks, int topK) {
        // 候选非空但模型未返回结果时视为服务异常。
        if (results == null || results.isEmpty()) throw new IllegalStateException("Rerank 未返回结果");
        // 记录已见候选下标，避免重复结果。
        Set<Integer> seen = new HashSet<>();
        // 收集通过校验的重排序命中。
        List<SearchHit> hits = new ArrayList<>();
        // 逐条检查模型给出的排名结果。
        for (var item : results) {
            // 读取候选下标及相关性分数，均使用包装类型识别 null。
            Integer index = item.getIndex(); Double score = item.getRelevanceScore();
            // 拒绝缺失、越界或重复的候选下标。
            if (index == null || index < 0 || index >= chunks.size() || !seen.add(index)
                    // 同时拒绝空分数、NaN 与无穷值。
                    || score == null || !Double.isFinite(score)) throw new IllegalStateException("Rerank 返回非法 index 或分数");
            // 关键：index 指向发送给 Reranker 的候选列表，不是 Milvus 主键。
            // 通过请求列表下标关联真实分块，保留来源与稳定 ID 并标记 rerank 通道。
            hits.add(new SearchHit(chunks.get(index), score, "rerank"));
        }
        // 按相关性分数降序重新排列，限制最终 topK 数量后返回不可变列表。
        return hits.stream().sorted(Comparator.comparingDouble(SearchHit::score).reversed()).limit(topK).toList();
    }
}
