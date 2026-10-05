// 声明所属包，组织 com.example.salesagent.bailian 的类型并避免类名冲突。
package com.example.salesagent.bailian;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
/** 让业务测试可以使用确定性的向量；生产实现调用百炼 SDK。 */
// 定义批量向量化接口；每个输入文本必须按同序对应一条 float 向量。
public interface EmbeddingClient { List<float[]> embed(List<String> texts); }
