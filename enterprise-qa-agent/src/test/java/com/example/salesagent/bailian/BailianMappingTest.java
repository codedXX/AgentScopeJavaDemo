// 声明所属包，组织 com.example.salesagent.bailian 的类型并避免类名冲突。
package com.example.salesagent.bailian;

// 引入 TextEmbeddingResultItem，用于构造包含 text_index 的模型响应样本。
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
// 引入 TextReRankOutput，用于构造相关性分数与候选下标响应。
import com.alibaba.dashscope.rerank.TextReRankOutput;
// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;

// 验证百炼响应的下标映射、维度检查与重排序结果去重。
class BailianMappingTest {
    // 验证 Embedding 乱序响应会按照 text_index 恢复输入顺序。
    @Test void embeddingRestoresInputOrder() {
        // 先构造属于输入第二项的响应向量。
        var second = embedding(1, List.of(0.0, 1.0));
        // 再构造属于输入第一项的响应向量。
        var first = embedding(0, List.of(1.0, 0.0));
        // 以倒序结果调用映射函数，预期恢复两个二维输入的原顺序。
        var vectors = BailianEmbeddingClient.map(List.of(second, first), 2, 2);
        // 断言列表首项对应原始输入第一项的 [1,0] 向量。
        assertArrayEquals(new float[]{1, 0}, vectors.getFirst());
    }
    // 验证重复输入下标和错误向量维度都会被拒绝。
    @Test void rejectsDuplicateOrIncorrectDimensions() {
        // 构造合法的第零项二维向量响应。
        var item = embedding(0, List.of(1.0, 0.0));
        // 将同一个响应重复两次，断言重复 text_index 触发状态异常。
        assertThrows(IllegalStateException.class, () -> BailianEmbeddingClient.map(List.of(item, item), 2, 2));
        // 把二维响应按三维配置映射，断言维度不一致被拒绝。
        assertThrows(IllegalStateException.class, () -> BailianEmbeddingClient.map(List.of(item), 1, 3));
    }
    // 验证 Rerank 响应 index 指向原始候选列表，而非分块主键。
    @Test void rerankIndexMapsToOriginalChunk() {
        // 创建来源不同的 A 和 B 候选，便于检查下标映射保留元数据。
        var chunks = List.of(new KnowledgeChunk("A", "a", "a.md", 0), new KnowledgeChunk("B", "b", "b.md", 0));
        // 让下标一的分数高于下标零，仅保留最高分一项。
        var results = BailianRerankClient.map(List.of(rank(1, .9), rank(0, .3)), chunks, 1);
        // 断言最高分命中映射到原候选 B。
        assertEquals("B", results.getFirst().chunk().chunkId());
        // 断言映射后仍保留 B 的原始来源路径。
        assertEquals("b.md", results.getFirst().chunk().source());
        // 断言超出候选数量的响应下标被拒绝。
        assertThrows(IllegalStateException.class, () -> BailianRerankClient.map(List.of(rank(2, .9)), chunks, 1));
        // 断言同一下标重复出现时被拒绝，避免返回重复证据。
        assertThrows(IllegalStateException.class, () -> BailianRerankClient.map(List.of(rank(0, .9), rank(0, .5)), chunks, 2));
    }
    // 构造包含输入位置和向量值的官方 Embedding 响应条目。
    private static TextEmbeddingResultItem embedding(int index, List<Double> values) {
        // 创建结果项，设置 text_index 和向量，再返回供测试使用。
        var item = new TextEmbeddingResultItem(); item.setTextIndex(index); item.setEmbedding(values); return item;
    }
    // 构造包含候选下标和相关性分数的官方 Rerank 响应条目。
    private static TextReRankOutput.Result rank(int index, double score) {
        // 创建结果项，设置 index 及 relevance_score，再返回供测试使用。
        var result = new TextReRankOutput.Result(); result.setIndex(index); result.setRelevanceScore(score); return result;
    }
}
