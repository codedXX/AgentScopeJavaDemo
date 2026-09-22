package com.example.salesagent.bailian;

import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.alibaba.dashscope.rerank.*;
import com.example.salesagent.config.DemoProperties;
import com.example.salesagent.model.*;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("app")
public class BailianRerankClient implements RerankClient {
    private final DemoProperties.Bailian config;
    private final TextReRank api;
    public BailianRerankClient(DemoProperties properties) {
        config = properties.bailian();
        var timeout = Duration.ofSeconds(config.timeoutSeconds());
        api = new TextReRank("http", config.baseUrl(), ConnectionOptions.builder()
                .connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).build());
    }
    @Override public List<SearchHit> rank(String query, List<KnowledgeChunk> chunks, int topK) {
        if (chunks.isEmpty()) return List.of();
        BailianCalls.requireKey(config.apiKey());
        var param = TextReRankParam.builder().apiKey(config.apiKey()).model(config.rerankModel())
                .query(query).documents(chunks.stream().map(KnowledgeChunk::text).toList())
                .topN(Math.min(topK, chunks.size())).returnDocuments(false).build();
        var result = BailianCalls.call(() -> api.call(param));
        if (result == null || result.getOutput() == null) throw new IllegalStateException("Rerank 响应为空");
        return map(result.getOutput().getResults(), chunks, topK);
    }
    static List<SearchHit> map(List<TextReRankOutput.Result> results, List<KnowledgeChunk> chunks, int topK) {
        if (results == null || results.isEmpty()) throw new IllegalStateException("Rerank 未返回结果");
        Set<Integer> seen = new HashSet<>();
        List<SearchHit> hits = new ArrayList<>();
        for (var item : results) {
            Integer index = item.getIndex(); Double score = item.getRelevanceScore();
            if (index == null || index < 0 || index >= chunks.size() || !seen.add(index)
                    || score == null || !Double.isFinite(score)) throw new IllegalStateException("Rerank 返回非法 index 或分数");
            // 关键：index 指向发送给 Reranker 的候选列表，不是 Milvus 主键。
            hits.add(new SearchHit(chunks.get(index), score, "rerank"));
        }
        return hits.stream().sorted(Comparator.comparingDouble(SearchHit::score).reversed()).limit(topK).toList();
    }
}
