package com.example.salesagent.bailian;
import com.example.salesagent.model.*;
import java.util.List;
/** 重排接口：从候选知识片段中选出最相关的结果。 */
public interface RerankClient { List<SearchHit> rank(String query, List<KnowledgeChunk> chunks, int topK); }
