package com.example.salesagent.rag;

import com.example.salesagent.model.KnowledgeChunk;
import java.util.List;

public interface WritableVectorChunkStore extends VectorChunkStore {
    void reset(int dimension);
    void upsert(List<KnowledgeChunk> chunks, List<float[]> vectors);
    void publish();
    long count();
}
