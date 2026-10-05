// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;

// 在关键词召回契约上扩展全量重建所需的写入能力。
public interface WritableKeywordIndex extends KeywordIndex {
    // 删除已有关键词索引数据。
    void reset();
    // 根据稳定分块 ID 新增或覆盖知识子块。
    void upsert(List<KnowledgeChunk> chunks);
    // 返回有效记录数，用于建库清单一致性验证。
    long count();
}
