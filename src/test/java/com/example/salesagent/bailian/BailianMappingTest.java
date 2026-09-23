package com.example.salesagent.bailian;

import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.example.salesagent.model.KnowledgeChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BailianMappingTest {
    @Test void embeddingRestoresInputOrder() {
        TextEmbeddingResultItem second = embedding(1, List.of(0.0, 1.0));
        TextEmbeddingResultItem first = embedding(0, List.of(1.0, 0.0));
        List<float[]> vectors = BailianEmbeddingClient.map(List.of(second, first), 2, 2);
        assertArrayEquals(new float[]{1, 0}, vectors.getFirst());
    }
    @Test void rejectsDuplicateOrIncorrectDimensions() {
        TextEmbeddingResultItem item = embedding(0, List.of(1.0, 0.0));
        assertThrows(IllegalStateException.class, () -> BailianEmbeddingClient.map(List.of(item, item), 2, 2));
        assertThrows(IllegalStateException.class, () -> BailianEmbeddingClient.map(List.of(item), 1, 3));
    }
    @Test void rerankIndexMapsToOriginalChunk() {
        List<KnowledgeChunk> chunks = List.of(new KnowledgeChunk("A", "a", "a.md", 0), new KnowledgeChunk("B", "b", "b.md", 0));
        List<com.example.salesagent.model.SearchHit> results = BailianRerankClient.map(List.of(rank(1, .9), rank(0, .3)), chunks, 1);
        assertEquals("B", results.getFirst().getChunk().getChunkId());
        assertEquals("b.md", results.getFirst().getChunk().getSource());
        assertThrows(IllegalStateException.class, () -> BailianRerankClient.map(List.of(rank(2, .9)), chunks, 1));
        assertThrows(IllegalStateException.class, () -> BailianRerankClient.map(List.of(rank(0, .9), rank(0, .5)), chunks, 2));
    }
    private static TextEmbeddingResultItem embedding(int index, List<Double> values) {
        TextEmbeddingResultItem item = new TextEmbeddingResultItem(); item.setTextIndex(index); item.setEmbedding(values); return item;
    }
    private static TextReRankOutput.Result rank(int index, double score) {
        TextReRankOutput.Result result = new TextReRankOutput.Result(); result.setIndex(index); result.setRelevanceScore(score); return result;
    }
}
