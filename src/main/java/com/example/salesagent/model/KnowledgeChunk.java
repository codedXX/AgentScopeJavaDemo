package com.example.salesagent.model;
/** 两路索引共用同一个分块标识，来源随证据一直传到最终回答。 */
public record KnowledgeChunk(String chunkId, String text, String source, int ordinal) {}
