package com.example.salesagent.model;
/** score 仅在同一 channel 内比较；混合召回不能直接比较两路原始分数。 */
public record SearchHit(KnowledgeChunk chunk, double score, String channel) {}
