// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;

// 在向量召回契约上扩展可重建与可发布的数据写入能力。
public interface WritableVectorChunkStore extends VectorChunkStore {
    // 按指定维度创建或重建空向量存储。
    void reset(int dimension);
    // 批量保存同序的知识子块与向量。
    void upsert(List<KnowledgeChunk> chunks, List<float[]> vectors);
    // 刷新、加载或持久化新增向量，使结果可被检索。
    void publish();
    // 返回已保存的知识记录数，用于与关键词索引核对。
    long count();
}
