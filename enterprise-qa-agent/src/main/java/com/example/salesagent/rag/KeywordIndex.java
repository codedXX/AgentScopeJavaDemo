// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 SearchHit，用于检索命中分块、分数和通道。
import com.example.salesagent.model.SearchHit;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;

// 约束接口只保留一个抽象方法，允许使用 Lambda 替换实现。
@FunctionalInterface
// 定义关键词召回契约，允许生产 BM25 与测试 Lambda 实现互换。
public interface KeywordIndex {
    // 根据文本问题返回最多 topK 条带分数和来源的有序命中。
    List<SearchHit> search(String query, int topK);
}
