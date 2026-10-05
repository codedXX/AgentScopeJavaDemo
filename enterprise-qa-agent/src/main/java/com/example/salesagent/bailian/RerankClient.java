// 声明所属包，组织 com.example.salesagent.bailian 的类型并避免类名冲突。
package com.example.salesagent.bailian;
// 引入知识分块、命中证据与检索结果等业务数据类型。
import com.example.salesagent.model.*;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
// 定义重排序接口；输入原问题、真实知识候选和 TopK，返回带相关性分数的命中。
public interface RerankClient { List<SearchHit> rank(String query, List<KnowledgeChunk> chunks, int topK); }
