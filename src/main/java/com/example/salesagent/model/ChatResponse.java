package com.example.salesagent.model;
import java.util.List;
public record ChatResponse(String sessionId, String answer, List<String> sources,
                           List<String> steps, long retrievalMs, long totalMs) {}
