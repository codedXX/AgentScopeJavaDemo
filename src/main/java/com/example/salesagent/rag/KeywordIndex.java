package com.example.salesagent.rag;

import com.example.salesagent.model.SearchHit;
import java.util.List;

@FunctionalInterface
public interface KeywordIndex {
    List<SearchHit> search(String query, int topK);
}
