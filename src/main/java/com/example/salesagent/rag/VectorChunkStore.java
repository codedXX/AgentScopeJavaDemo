package com.example.salesagent.rag;

import com.example.salesagent.model.SearchHit;
import java.util.List;

@FunctionalInterface
public interface VectorChunkStore {
    List<SearchHit> search(float[] queryVector, int topK);
}
