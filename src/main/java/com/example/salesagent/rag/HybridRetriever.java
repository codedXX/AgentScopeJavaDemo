package com.example.salesagent.rag;

import com.example.salesagent.bailian.EmbeddingClient;
import com.example.salesagent.bailian.RerankClient;
import com.example.salesagent.model.KnowledgeChunk;
import com.example.salesagent.model.RagResult;
import java.util.LinkedHashMap;
import java.util.List;

/** 串行执行双路召回，按分块 ID 去重后才交给模型重排序。 */
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

    private RagResult retrieveLocked(String query, long started) {
        if (!readiness.isReady()) throw new KnowledgeNotReadyException();
        LinkedHashMap<String, KnowledgeChunk> candidates = new LinkedHashMap<>();
        keywordIndex.search(query, recallTopK).forEach(hit -> candidates.putIfAbsent(hit.chunk().chunkId(), hit.chunk()));

        List<float[]> embedded = embeddingClient.embed(List.of(query));
        if (embedded.size() != 1) throw new IllegalStateException("查询向量返回数量不正确");
        vectorStore.search(embedded.getFirst(), recallTopK)
                .forEach(hit -> candidates.putIfAbsent(hit.chunk().chunkId(), hit.chunk()));

        long elapsed = elapsedMs(started);
        if (candidates.isEmpty()) return new RagResult(List.of(), true, elapsed);
        List<com.example.salesagent.model.SearchHit> ranked = rerankClient.rank(query, List.copyOf(candidates.values()), finalTopK);
        boolean insufficient = ranked.isEmpty()
                || minRerankScore != null && ranked.getFirst().score() < minRerankScore;
        return new RagResult(List.copyOf(ranked), insufficient, elapsedMs(started));
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
