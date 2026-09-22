package com.example.salesagent.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.example.salesagent.model.KnowledgeChunk;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneKeywordIndexTest {
    @TempDir Path tempDir;

    @Test
    void chineseTermsFindTheMatchingChunkAndPreserveMetadata() {
        try (LuceneKeywordIndex index = new LuceneKeywordIndex(tempDir)) {
            KnowledgeChunk product = new KnowledgeChunk("A", "产品支持离线缓存和快速启动", "product.md", 2);
            KnowledgeChunk health = new KnowledgeChunk("B", "健康科普强调规律作息", "health.md", 0);
            index.reset();
            index.upsert(List.of(product, health));

            var hits = index.search("离线缓存", 10);

            assertEquals(1, hits.size());
            assertEquals(product, hits.getFirst().chunk());
            assertEquals("bm25", hits.getFirst().channel());
        }
    }

    @Test
    void resetRemovesOldDocuments() {
        try (LuceneKeywordIndex index = new LuceneKeywordIndex(tempDir)) {
            index.reset();
            index.upsert(List.of(new KnowledgeChunk("old", "香蕉缓存策略", "old.md", 0)));
            index.reset();
            index.upsert(List.of(new KnowledgeChunk("new", "火箭推进引擎", "new.md", 0)));

            assertTrue(index.search("香蕉", 10).isEmpty());
            assertEquals("new", index.search("火箭", 10).getFirst().chunk().chunkId());
        }
    }
}
