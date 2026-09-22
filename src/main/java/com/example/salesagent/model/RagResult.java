package com.example.salesagent.model;
import java.util.List;
public record RagResult(List<SearchHit> evidence, boolean insufficient, long retrievalMs) {}
