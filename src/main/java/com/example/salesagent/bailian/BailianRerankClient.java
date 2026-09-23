package com.example.salesagent.bailian;

import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.alibaba.dashscope.rerank.*;
import com.example.salesagent.config.BailianProperties;
import com.example.salesagent.config.DemoProperties;
import com.example.salesagent.model.*;

import java.time.Duration;
import java.util.*;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 调用百炼重排模型，按问题相关程度重新排列候选片段。 */
@Component @Profile("app")
public class BailianRerankClient implements RerankClient {
    private final BailianProperties config;

    private final TextReRank api;


    public BailianRerankClient(DemoProperties properties) {
        config = properties.getBailian();
        Duration timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        api = new TextReRank("http", config.getBaseUrl(), ConnectionOptions.builder()
                .connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).build());
    }
    /** 把候选片段交给重排模型，取前几条。 */
    @Override public List<SearchHit> rank(String query, List<KnowledgeChunk> chunks, int topK) {
        if (chunks.isEmpty()) return List.of();
        BailianCalls.requireKey(config.getApiKey());
        TextReRankParam param = TextReRankParam.builder().apiKey(config.getApiKey()).model(config.getRerankModel())
                .query(query).documents(chunks.stream().map(KnowledgeChunk::getText).toList())
                .topN(Math.min(topK, chunks.size())).returnDocuments(false).build();
        TextReRankResult result = BailianCalls.call(() -> api.call(param));
        if (result == null || result.getOutput() == null) throw new IllegalStateException("Rerank 响应为空");
        return map(result.getOutput().getResults(), chunks, topK);
    }
    /**
     * Rerank 结果只包含候选列表下标和相关性分数；用下标取回原始 KnowledgeChunk，保留其来源信息。
     * 拒绝重复或越界下标和非有限分数，再按分数降序返回最多 topK 条。
     */
    static List<SearchHit> map(List<TextReRankOutput.Result> results, List<KnowledgeChunk> chunks, int topK) {
        if (results == null || results.isEmpty()) throw new IllegalStateException("Rerank 未返回结果");
        Set<Integer> seen = new HashSet<>();
        List<SearchHit> hits = new ArrayList<>();
        for (TextReRankOutput.Result item : results) {
            Integer index = item.getIndex(); Double score = item.getRelevanceScore();
            if (index == null || index < 0 || index >= chunks.size() || !seen.add(index)
                    || score == null || !Double.isFinite(score)) throw new IllegalStateException("Rerank 返回非法 index 或分数");
            // 关键：index 指向发送给 Reranker 的候选列表，不是 Milvus 主键。
            hits.add(new SearchHit(chunks.get(index), score, "rerank"));
        }
        return hits.stream().sorted(Comparator.comparingDouble(SearchHit::getScore).reversed()).limit(topK).toList();
    }
}
