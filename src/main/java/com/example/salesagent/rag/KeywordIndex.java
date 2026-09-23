package com.example.salesagent.rag;

import com.example.salesagent.model.SearchHit;
import java.util.List;

/** 关键词检索接口：按文字匹配知识片段。 */
@FunctionalInterface
public interface KeywordIndex {
    List<SearchHit> search(String query, int topK);
}
