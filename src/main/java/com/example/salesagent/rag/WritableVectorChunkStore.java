package com.example.salesagent.rag;

import com.example.salesagent.model.KnowledgeChunk;
import java.util.List;

/**
 * 向量索引的重建接口。调用方负责保证 chunks 与 vectors 逐项对应；
 * 写入结束后调用 publish，使数据可供计数校验和检索。
 */
public interface WritableVectorChunkStore extends VectorChunkStore {
    void reset(int dimension);
    void upsert(List<KnowledgeChunk> chunks, List<float[]> vectors);
    void publish();
    long count();
}
