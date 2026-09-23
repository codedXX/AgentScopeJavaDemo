package com.example.salesagent.rag;

import com.example.salesagent.bailian.EmbeddingClient;
import com.example.salesagent.bailian.RerankClient;
import com.example.salesagent.model.KnowledgeChunk;
import com.example.salesagent.model.RagResult;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 混合检索入口。先用 Lucene BM25 搜索关键词，再对查询生成向量并搜索 Milvus；两路结果按分块 ID 去重。
 * 原始分数来自不同体系，不能直接混排，因此将候选片段交给 Rerank 模型统一排序。
 */
public final class HybridRetriever {
    private final KeywordIndex keywordIndex;
    private final VectorChunkStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;
    private final KnowledgeReadiness readiness;
    private final int recallTopK;
    private final int finalTopK;
    private final Double minRerankScore;

    public HybridRetriever(KeywordIndex keywordIndex, VectorChunkStore vectorStore,
                           EmbeddingClient embeddingClient, RerankClient rerankClient,
                           KnowledgeReadiness readiness, int recallTopK, int finalTopK,
                           Double minRerankScore) {
        this.keywordIndex = keywordIndex;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.readiness = readiness;
        this.recallTopK = recallTopK;
        this.finalTopK = finalTopK;
        this.minRerankScore = minRerankScore;
    }

    public RagResult retrieve(String query) {
        long started = System.nanoTime();
        return readiness.withReadLock(() -> retrieveLocked(query, started));
    }

    /**
     * 整个召回与重排过程位于就绪读锁内，确保这一轮使用同一版索引。
     * 无候选时返回空证据；有候选时根据首条重排分数判断证据是否不足，并保留总检索耗时。
     */
    private RagResult retrieveLocked(String query, long started) {
        if (!readiness.isReady()) throw new KnowledgeNotReadyException();
        LinkedHashMap<String, KnowledgeChunk> candidates = new LinkedHashMap<>();
        keywordIndex.search(query, recallTopK).forEach(hit -> candidates.putIfAbsent(
                hit.getChunk().getChunkId(), hit.getChunk()));

        List<float[]> embedded = embeddingClient.embed(List.of(query));
        if (embedded.size() != 1) throw new IllegalStateException("查询向量返回数量不正确");
        vectorStore.search(embedded.getFirst(), recallTopK)
                .forEach(hit -> candidates.putIfAbsent(hit.getChunk().getChunkId(), hit.getChunk()));

        long elapsed = elapsedMs(started);
        if (candidates.isEmpty()) return new RagResult(List.of(), true, elapsed);
        List<com.example.salesagent.model.SearchHit> ranked = rerankClient.rank(query, List.copyOf(candidates.values()), finalTopK);
        boolean insufficient = ranked.isEmpty()
                || minRerankScore != null && ranked.getFirst().getScore() < minRerankScore;
        return new RagResult(List.copyOf(ranked), insufficient, elapsedMs(started));
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
