// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入知识分块、命中证据与检索结果等业务数据类型。
import com.example.salesagent.model.*;
// 引入 LlmGateway，用于生成结构化查询改写与 HyDE 文本。
import com.example.salesagent.agent.LlmGateway;
// 引入路径、文件读写及原子替换操作。
import java.nio.file.*;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入原子计数器，观测并发请求次数及知识库版本变化。
import java.util.concurrent.atomic.*;
// 引入 JUnit 测试声明与断言相关类型。
import org.junit.jupiter.api.*;
// 引入 TempDir，用于由 JUnit 自动隔离和清理测试临时目录。
import org.junit.jupiter.api.io.TempDir;
// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;
// 引入 Mockito 模拟与参数匹配工具，隔离模型依赖。
import static org.mockito.Mockito.*;
// 验证企业检索中的父块恢复、RRF、HyDE 证据边界和缓存版本失效。
class EnterpriseRetrievalTest {
    // 为持久化父子映射和知识文件提供隔离临时目录。
    @TempDir Path temp;
    // 验证命中子块可从磁盘父子清单恢复带章节标题的上下文。
    @Test void childHitsExpandToPersistedHeadingParents() throws Exception {
        // 创建 JSON 父文档存储，准备登记子块关联关系。
        var parents=new ParentDocumentStore(temp.resolve("parents.json"));
        // 写入产品 A 和产品 B 两个章节，A 的长正文用于触发父子切分。
        Files.writeString(temp.resolve("guide.md"),"# 产品A\n\n产品A的说明。"+"每袋蛋白质15克。".repeat(30)+"\n\n# 产品B\n\n产品B含维生素。");
        // 配置 80 字符子块、10 字符重叠与 600 字符父块。
        var chunker=new DocumentChunker(temp,80,10,parents,600);
        // 切分知识文件，再把生成的父子映射发布到磁盘。
        var chunks=chunker.split(temp.resolve("guide.md"));chunker.publishParents();
        // 断言长正文被切成足够多的子块。
        assertTrue(chunks.size()>3);
        // 重新创建存储实例，验证映射可从磁盘恢复。
        var restored=new ParentDocumentStore(temp.resolve("parents.json"));
        // 断言恢复后的父块正文比原召回子块更完整。
        assertTrue(restored.expand(chunks.getFirst()).text().length()>chunks.getFirst().text().length());
        // 断言产品 A 子块恢复后保留所属章节标题。
        assertTrue(restored.expand(chunks.getFirst()).text().startsWith("# 产品A"));
        // 断言父块恢复没有混入其他产品章节的正文。
        assertFalse(restored.expand(chunks.getFirst()).text().contains("产品B"));
    }
    // 验证 RRF 对同通道重复 ID 去重，且不受原始分数尺度影响。
    @Test void rrfDeduplicatesPerChannelAndIgnoresRawScoreScale() {
        // 创建两个稳定 ID 不同的候选，供两路召回交叉命中。
        var a=new KnowledgeChunk("a","a","a.md",0);var b=new KnowledgeChunk("b","b","b.md",0);
        // 关键词通道中让 a 重复出现、b 排第三，构造去重与名次融合场景。
        var fused=ReciprocalRankFusion.fuse(List.of(List.of(new SearchHit(a,100,"bm25"),new SearchHit(a,90,"bm25"),new SearchHit(b,80,"bm25")),
            // 向量通道只命中 b，使用 k=60 和最大 10 条进行融合。
            List.of(new SearchHit(b,.99,"vector"))),60,10);
        // 断言 b 因跨通道累计贡献排第一，且结果中只有两个唯一候选。
        assertEquals("b",fused.getFirst().chunk().chunkId());assertEquals(2,fused.size());
        // 断言 a 只获得关键词通道第一名的一次 1/61 贡献，误差允许 1e-9。
        assertEquals(1.0/61,fused.get(1).score(),1e-9);
    }
    // 验证 HyDE 只参与向量召回，同时验证知识版本变化及强制检索会绕过缓存。
    @Test void hydeIsOnlyUsedForRecallAndCacheInvalidatesOnRevision() {
        // 模拟 LLM 网关，隔离真实模型请求。
        var llm=mock(LlmGateway.class);
        // 让模型固定返回一个同义查询和假设回答，方便追踪其用途。
        when(llm.structured(anyString(),anyString(),eq(QueryExpansion.Expanded.class))).thenReturn(new QueryExpansion.Expanded(List.of("改写问题"),"假设内容不应成为证据"));
        // 分别记录送入 Embedding 的文本、调用次数与可变知识版本。
        var embedded=new ArrayList<String>();var calls=new AtomicInteger();var revision=new AtomicInteger();
        // 创建真实知识证据，区别于模型产生的假设回答。
        var chunk=new KnowledgeChunk("a","真实证据","actual.md",0);
        // 用始终就绪、版本可递增的存储状态实现模拟知识库更新。
        KnowledgeReadiness ready=new KnowledgeReadiness(){public boolean isReady(){return true;}public String revision(){return "v"+revision.get();}};
        // 关键词和向量通道均返回同一真实分块，构造可稳定融合的候选。
        var retriever=new HybridRetriever((q,k)->List.of(new SearchHit(chunk,1,"bm25")),(v,k)->List.of(new SearchHit(chunk,1,"vector")),
            // Embedding 替身记录文本与次数，并为每条查询返回固定向量。
            texts->{embedded.addAll(texts);calls.incrementAndGet();return texts.stream().map(t->new float[]{1}).toList();},
            // 重排序替身返回真实候选；启用 HyDE 与 60 秒缓存并限制融合候选数。
            (q,chunks,k)->List.of(new SearchHit(chunks.getFirst(),.9,"rerank")),ready,10,5,null,new QueryExpansion(llm,true),null,60,30,60);
        // 首次查询后断言假设文本确实进入向量化集合。
        var first=retriever.retrieve("原问题");assertTrue(embedded.contains("假设内容不应成为证据"));
        // 断言最终返回的是实际知识正文，假设文本没有被当作证据。
        assertEquals("真实证据",first.evidence().getFirst().chunk().text());
        // 再次查询同一问题，断言缓存命中使向量化次数仍为一次。
        retriever.retrieve("原问题");assertEquals(1,calls.get());
        // 递增知识版本后再次查询，断言旧缓存失效并重新向量化。
        revision.incrementAndGet();retriever.retrieve("原问题");assertEquals(2,calls.get());
        // 显式跳过缓存后再查询，断言又执行一次真实召回流程。
        retriever.retrieve("原问题",true);assertEquals(3,calls.get());
    }
}
