// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;

// 引入 EmbeddingClient，用于可替换的文本向量化接口。
import com.example.salesagent.bailian.EmbeddingClient;
// 引入 RerankClient，用于可替换的候选重排序接口。
import com.example.salesagent.bailian.RerankClient;
// 引入知识分块、命中证据与检索结果等业务数据类型。
import com.example.salesagent.model.*;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;

// 验证混合检索的名次融合、空候选、就绪检查与最低证据分数。
class HybridRetrieverTest {
    // 按给定 ID 创建带唯一正文和来源的确定性候选。
    private static KnowledgeChunk chunk(String id) {
        // 生成可追踪 ID、内容和来源的测试知识分块。
        return new KnowledgeChunk(id, "内容-" + id, "source-" + id + ".md", 0);
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证 RRF 先融合各通道名次，再把候选交给重排序模型。
    void fusesRanksBeforeRerankingWithoutComparingChannelScores() {
        // 建立四个不同候选，覆盖重复命中和单路命中。
        var a = chunk("A"); var b = chunk("B"); var c = chunk("C"); var d = chunk("D");
        // 用 Lambda 提供固定排名的关键词通道。
        KeywordIndex keyword = (query, topK) -> List.of(
                // 设置 BM25 分数 100、90、80，故意区别于向量分数尺度。
                new SearchHit(a, 100, "bm25"), new SearchHit(b, 90, "bm25"), new SearchHit(c, 80, "bm25"));
        // 用 Lambda 提供固定排名的向量通道。
        VectorChunkStore vector = (queryVector, topK) -> List.of(
                // 设置向量排名 B、D、A 及接近 1 的分数，验证不会直接比较两路原始分值。
                new SearchHit(b, .99, "vector"), new SearchHit(d, .98, "vector"), new SearchHit(a, .97, "vector"));
        // 记录传给 Rerank 的候选顺序，用于检查融合排名。
        List<KnowledgeChunk> submitted = new ArrayList<>();
        // 构造可观测的重排序替身。
        RerankClient reranker = (query, chunks, topK) -> {
            // 保存重排序实际收到的候选列表。
            submitted.addAll(chunks);
            // 让重排序只返回融合列表第四项，验证最终证据由 Rerank 决定。
            return List.of(new SearchHit(chunks.get(3), .95, "rerank"));
        };
        // 注入固定召回与向量化替身，避免网络依赖。
        HybridRetriever retriever = new HybridRetriever(keyword, vector, texts -> List.of(new float[]{1, 2}),
                // 将知识库设为就绪，召回 10 条、最终 5 条且不启用分数阈值。
                reranker, () -> true, 10, 5, null);

        // 执行一次完整混合检索。
        RagResult result = retriever.retrieve("问题");

        // 断言传给 Rerank 的融合候选顺序为 B、A、D、C。
        assertEquals(List.of("B", "A", "D", "C"), submitted.stream().map(KnowledgeChunk::chunkId).toList());
        // 断言最终证据采用重排序选中的 C。
        assertEquals("C", result.evidence().getFirst().chunk().chunkId());
        // 断言非空且无最低分限制的证据不会标记为不足。
        assertFalse(result.insufficient());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证两路都没有候选时不调用重排序且返回证据不足。
    void emptyCandidatesSkipRerankerAndReturnInsufficient() {
        // 将重排序替身设为调用即失败，使误调用立即暴露。
        RerankClient reranker = (query, chunks, topK) -> { throw new AssertionError("空候选不应调用重排序"); };
        // 注入均为空结果的关键词和向量通道。
        HybridRetriever retriever = new HybridRetriever((q, k) -> List.of(), (v, k) -> List.of(),
                // 提供固定查询向量及就绪知识库，保留正常检索配置。
                texts -> List.of(new float[]{1}), reranker, () -> true, 10, 5, null);

        // 执行没有相关候选的问题检索。
        RagResult result = retriever.retrieve("没有答案的问题");

        // 断言空召回被标记为证据不足。
        assertTrue(result.insufficient());
        // 断言结果证据集合为空。
        assertTrue(result.evidence().isEmpty());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证未就绪知识库拒绝检索，而不继续调用召回或模型。
    void disabledKnowledgeBaseRejectsRetrieval() {
        // 准备空召回替身，隔离具体检索实现。
        HybridRetriever retriever = new HybridRetriever((q, k) -> List.of(), (v, k) -> List.of(),
                // 把知识库就绪检查设为 false，构造应被提前拦截的检索器。
                texts -> List.of(new float[]{1}), (q, c, k) -> List.of(), () -> false, 10, 5, null);

        // 断言请求明确抛出 KnowledgeNotReadyException。
        assertThrows(KnowledgeNotReadyException.class, () -> retriever.retrieve("问题"));
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证最终相关性分数低于配置阈值时仍判定证据不足。
    void configuredThresholdMarksLowScoredEvidenceInsufficient() {
        // 创建关键词可召回的候选 A。
        KnowledgeChunk a = chunk("A");
        // 只让关键词通道召回 A。
        HybridRetriever retriever = new HybridRetriever((q, k) -> List.of(new SearchHit(a, 1, "bm25")),
                // 向量通道返回空结果，Embedding 返回固定查询向量。
                (v, k) -> List.of(), texts -> List.of(new float[]{1}),
                // 让 Rerank 返回 0.39，而证据最低分配置为 0.4。
                (q, c, k) -> List.of(new SearchHit(a, .39, "rerank")), () -> true, 10, 5, .4);

        // 断言存在候选但低于阈值的检索结果标记为不足。
        assertTrue(retriever.retrieve("问题").insufficient());
    }
}
