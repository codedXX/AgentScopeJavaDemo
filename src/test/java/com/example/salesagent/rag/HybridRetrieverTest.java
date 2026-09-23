package com.example.salesagent.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.example.salesagent.bailian.EmbeddingClient;
import com.example.salesagent.bailian.RerankClient;
import com.example.salesagent.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
/** 验证两路召回合并、重排和证据不足的判断。 */
class HybridRetrieverTest {
    private static KnowledgeChunk chunk(String id) {
        return new KnowledgeChunk(id, "内容-" + id, "source-" + id + ".md", 0);
    }

    /** 按片段编号去重后重排，不直接比较两路原始分数。 */
    @Test
    void mergesByChunkIdBeforeRerankingWithoutComparingChannelScores() {
        KnowledgeChunk a = chunk("A"); KnowledgeChunk b = chunk("B"); KnowledgeChunk c = chunk("C"); KnowledgeChunk d = chunk("D");
        KeywordIndex keyword = (query, topK) -> List.of(
                new SearchHit(a, 100, "bm25"), new SearchHit(b, 90, "bm25"), new SearchHit(c, 80, "bm25"));
        VectorChunkStore vector = (queryVector, topK) -> List.of(
                new SearchHit(b, .99, "vector"), new SearchHit(d, .98, "vector"), new SearchHit(a, .97, "vector"));
        List<KnowledgeChunk> submitted = new ArrayList<>();
        RerankClient reranker = (query, chunks, topK) -> {
            submitted.addAll(chunks);
            return List.of(new SearchHit(chunks.get(3), .95, "rerank"));
        };
        HybridRetriever retriever = new HybridRetriever(keyword, vector, texts -> List.of(new float[]{1, 2}),
                reranker, () -> true, 10, 5, null);

        RagResult result = retriever.retrieve("问题");

        assertEquals(List.of("A", "B", "C", "D"), submitted.stream().map(KnowledgeChunk::getChunkId).toList());
        assertEquals("D", result.getEvidence().getFirst().getChunk().getChunkId());
        assertFalse(result.isInsufficient());
    }

    /** 没有候选片段时跳过重排并标记证据不足。 */
    @Test
    void emptyCandidatesSkipRerankerAndReturnInsufficient() {
        RerankClient reranker = (query, chunks, topK) -> { throw new AssertionError("空候选不应调用重排序"); };
        HybridRetriever retriever = new HybridRetriever((q, k) -> List.of(), (v, k) -> List.of(),
                texts -> List.of(new float[]{1}), reranker, () -> true, 10, 5, null);

        RagResult result = retriever.retrieve("没有答案的问题");

        assertTrue(result.isInsufficient());
        assertTrue(result.getEvidence().isEmpty());
    }

    /** 知识库未就绪时拒绝检索。 */
    @Test
    void disabledKnowledgeBaseRejectsRetrieval() {
        HybridRetriever retriever = new HybridRetriever((q, k) -> List.of(), (v, k) -> List.of(),
                texts -> List.of(new float[]{1}), (q, c, k) -> List.of(), () -> false, 10, 5, null);

        assertThrows(KnowledgeNotReadyException.class, () -> retriever.retrieve("问题"));
    }

    /** 低于配置分数线的证据会被标记为不足。 */
    @Test
    void configuredThresholdMarksLowScoredEvidenceInsufficient() {
        KnowledgeChunk a = chunk("A");
        HybridRetriever retriever = new HybridRetriever((q, k) -> List.of(new SearchHit(a, 1, "bm25")),
                (v, k) -> List.of(), texts -> List.of(new float[]{1}),
                (q, c, k) -> List.of(new SearchHit(a, .39, "rerank")), () -> true, 10, 5, .4);

        assertTrue(retriever.retrieve("问题").isInsufficient());
    }
}
