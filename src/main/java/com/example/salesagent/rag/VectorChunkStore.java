package com.example.salesagent.rag;

import com.example.salesagent.model.SearchHit;
import java.util.List;

/** 向量检索接口：按语义相似度查找知识片段。 */
@FunctionalInterface
public interface VectorChunkStore {
    List<SearchHit> search(float[] queryVector, int topK);
}
