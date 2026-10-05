// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 Path，用于安全组合与规范化文件路径。
import java.nio.file.Path;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;
// 引入 TempDir，用于由 JUnit 自动隔离和清理测试临时目录。
import org.junit.jupiter.api.io.TempDir;

// 用真实本地 Lucene 索引验证中文检索与重建清理。
class LuceneKeywordIndexTest {
    // 为每项测试提供独立索引目录，避免测试间残留文档。
    @TempDir Path tempDir;

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证中文关键词只找到相关分块，并保留所有原始元数据。
    void chineseTermsFindTheMatchingChunkAndPreserveMetadata() {
        // 创建真实 Lucene 索引，测试结束时自动关闭。
        try (LuceneKeywordIndex index = new LuceneKeywordIndex(tempDir)) {
            // 构造包含查询词的产品知识，并指定非零序号检验元数据。
            KnowledgeChunk product = new KnowledgeChunk("A", "产品支持离线缓存和快速启动", "product.md", 2);
            // 构造与查询无关的健康知识，检验召回过滤。
            KnowledgeChunk health = new KnowledgeChunk("B", "健康科普强调规律作息", "health.md", 0);
            // 建立空索引作为本次测试起点。
            index.reset();
            // 将相关和无关两条知识写入真实索引。
            index.upsert(List.of(product, health));

            // 用中文词组执行最多十条的 BM25 召回。
            var hits = index.search("离线缓存", 10);

            // 断言只返回一条相关结果。
            assertEquals(1, hits.size());
            // 断言命中知识对象与原产品知识完全相同，包含来源、序号等字段。
            assertEquals(product, hits.getFirst().chunk());
            // 断言命中正确标记为 bm25 通道。
            assertEquals("bm25", hits.getFirst().channel());
        }
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证全量重建清空旧资料后，仅保留本次写入的知识。
    void resetRemovesOldDocuments() {
        // 创建隔离的真实 Lucene 索引并自动管理资源。
        try (LuceneKeywordIndex index = new LuceneKeywordIndex(tempDir)) {
            // 先确保初始索引为空。
            index.reset();
            // 写入包含香蕉关键词的旧知识。
            index.upsert(List.of(new KnowledgeChunk("old", "香蕉缓存策略", "old.md", 0)));
            // 再次 reset，模拟下一次知识库全量重建。
            index.reset();
            // 写入包含火箭关键词的新知识。
            index.upsert(List.of(new KnowledgeChunk("new", "火箭推进引擎", "new.md", 0)));

            // 断言旧资料关键词已经无法命中。
            assertTrue(index.search("香蕉", 10).isEmpty());
            // 断言新资料关键词命中 ID 为 new 的新记录。
            assertEquals("new", index.search("火箭", 10).getFirst().chunk().chunkId());
        }
    }
}
