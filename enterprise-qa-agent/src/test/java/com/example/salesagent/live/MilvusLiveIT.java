// 声明所属包，组织 com.example.salesagent.live 的类型并避免类名冲突。
package com.example.salesagent.live;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 MilvusChunkStore，用于对真实 Milvus 集合执行建库、计数与向量召回。
import com.example.salesagent.rag.MilvusChunkStore;
// 引入 Milvus 连接配置与客户端，用于真实服务联调。
import io.milvus.v2.client.*;
// 引入 DropCollectionReq，用于只删除集成测试独立创建的 Milvus 集合。
import io.milvus.v2.service.collection.request.DropCollectionReq;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;
// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;
// 引入 JUnit 前提检查，在缺少真实服务配置时明确跳过集成测试。
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// 用独立随机集合验证真实 Milvus 写入、检索和清空重建。
class MilvusLiveIT {
    // 覆盖发布后的记录计数、向量命中和再次重建为空。
    @Test void persistsSearchesAndRebuildsRealCollection() {
        // 从环境变量读取真实 Milvus 服务地址。
        String uri = System.getenv("MILVUS_URI");
        // 未配置服务地址时明确跳过集成测试。
        assumeTrue(uri != null && !uri.isBlank(), "未设置 MILVUS_URI，跳过真实 Milvus 测试");
        // 读取可选 Milvus 令牌，未设置时使用空字符串。
        String token = System.getenv().getOrDefault("MILVUS_TOKEN", "");
        // 为每次测试生成独立集合名，避免与业务知识集合冲突。
        String collection = "sales_demo_it_" + UUID.randomUUID().toString().replace("-", "");
        // 创建真实向量存储，并在测试结束时自动关闭客户端。
        try (var store = new MilvusChunkStore(uri, token, collection)) {
            // 按二维向量创建测试集合和索引。
            store.reset(2);
            // 准备与 [1,0] 向量对应的产品知识 A。
            store.upsert(List.of(new KnowledgeChunk("A", "产品营养", "products.md", 0),
                    // 再加入与 [0,1] 向量对应的退货知识 B，形成明确的近邻差异。
                    new KnowledgeChunk("B", "退货政策", "business.md", 0)), List.of(new float[]{1, 0}, new float[]{0, 1}));
            // 发布两条向量并断言实际集合计数为二。
            store.publish(); assertEquals(2, store.count());
            // 查询 [1,0] 并断言第一近邻为产品知识 A。
            assertEquals("A", store.search(new float[]{1, 0}, 1).getFirst().chunk().chunkId());
            // 再次重建空集合并发布，断言记录数量归零。
            store.reset(2); store.publish(); assertEquals(0, store.count());
        // 不论测试成功或失败，都进入随机测试集合的清理流程。
        } finally {
            // 仅清理本测试新建的随机 collection，绝不触碰 sales_knowledge。
            // 创建短生命周期管理客户端，仅用于删除当前测试集合。
            var client = new MilvusClientV2(ConnectConfig.builder().uri(uri).token(token).build());
            // 删除本次生成的随机集合，防止集成测试留下数据。
            try { client.dropCollection(DropCollectionReq.builder().collectionName(collection).build()); }
            // 即使删除失败也关闭管理客户端，释放连接。
            finally { client.close(); }
        }
    }
}
