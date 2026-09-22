package com.example.salesagent.rag;

import com.example.salesagent.model.KnowledgeChunk;
import java.util.List;

public interface WritableKeywordIndex extends KeywordIndex {
    void reset();
    void upsert(List<KnowledgeChunk> chunks);
    long count();
}
