package com.example.salesagent.rag;

import com.example.salesagent.model.KnowledgeChunk;
import java.util.List;

/** 关键词索引的重建接口：清空旧内容、写入分块，再用记录数核对重建结果。 */
public interface WritableKeywordIndex extends KeywordIndex {
    void reset();
    void upsert(List<KnowledgeChunk> chunks);
    long count();
}
