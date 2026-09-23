package com.example.salesagent.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.example.salesagent.model.KnowledgeChunk;
import com.example.salesagent.model.SearchHit;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
/** 验证中文关键词查找和索引清空。 */
class LuceneKeywordIndexTest {
    @TempDir Path tempDir;

    /** 中文关键词找到正确片段并保留来源。 */
    @Test
    void chineseTermsFindTheMatchingChunkAndPreserveMetadata() {
        try (LuceneKeywordIndex index = new LuceneKeywordIndex(tempDir)) {
            KnowledgeChunk product = new KnowledgeChunk("A", "产品支持离线缓存和快速启动", "product.md", 2);
            KnowledgeChunk health = new KnowledgeChunk("B", "健康科普强调规律作息", "health.md", 0);
            index.reset();
            index.upsert(List.of(product, health));

            List<SearchHit> hits = index.search("离线缓存", 10);

            assertEquals(1, hits.size());
            assertEquals(product, hits.getFirst().getChunk());
            assertEquals("bm25", hits.getFirst().getChannel());
        }
    }

    /** 清空索引后旧资料不能再搜到。 */
    @Test
    void resetRemovesOldDocuments() {
        try (LuceneKeywordIndex index = new LuceneKeywordIndex(tempDir)) {
            index.reset();
            index.upsert(List.of(new KnowledgeChunk("old", "香蕉缓存策略", "old.md", 0)));
            index.reset();
            index.upsert(List.of(new KnowledgeChunk("new", "火箭推进引擎", "new.md", 0)));

            assertTrue(index.search("香蕉", 10).isEmpty());
            assertEquals("new", index.search("火箭", 10).getFirst().getChunk().getChunkId());
        }
    }
}
