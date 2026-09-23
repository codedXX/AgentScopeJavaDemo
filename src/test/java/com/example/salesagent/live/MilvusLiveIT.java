package com.example.salesagent.live;

import com.example.salesagent.model.KnowledgeChunk;
import com.example.salesagent.rag.MilvusChunkStore;
import io.milvus.v2.client.*;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MilvusLiveIT {
    @Test void persistsSearchesAndRebuildsRealCollection() {
        String uri = System.getenv("MILVUS_URI");
        assumeTrue(uri != null && !uri.isBlank(), "未设置 MILVUS_URI，跳过真实 Milvus 测试");
        String token = System.getenv().getOrDefault("MILVUS_TOKEN", "");
        String collection = "sales_demo_it_" + UUID.randomUUID().toString().replace("-", "");
        try (MilvusChunkStore store = new MilvusChunkStore(uri, token, collection)) {
            store.reset(2);
            store.upsert(List.of(new KnowledgeChunk("A", "产品营养", "products.md", 0),
                    new KnowledgeChunk("B", "退货政策", "business.md", 0)), List.of(new float[]{1, 0}, new float[]{0, 1}));
            store.publish(); assertEquals(2, store.count());
            assertEquals("A", store.search(new float[]{1, 0}, 1).getFirst().getChunk().getChunkId());
            store.reset(2); store.publish(); assertEquals(0, store.count());
        } finally {
            // 仅清理本测试新建的随机 collection，绝不触碰 sales_knowledge。
            MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder().uri(uri).token(token).build());
            try { client.dropCollection(DropCollectionReq.builder().collectionName(collection).build()); }
            finally { client.close(); }
        }
    }
}
