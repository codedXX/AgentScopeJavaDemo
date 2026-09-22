package com.example.salesagent.bailian;
import java.util.List;
/** 让业务测试可以使用确定性的向量；生产实现调用百炼 SDK。 */
public interface EmbeddingClient { List<float[]> embed(List<String> texts); }
