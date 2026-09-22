package com.example.salesagent.bailian;
import com.example.salesagent.model.*;
import java.util.List;
public interface RerankClient { List<SearchHit> rank(String query, List<KnowledgeChunk> chunks, int topK); }
