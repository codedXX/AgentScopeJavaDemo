package com.example.salesagent.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务参数集中管理；API Key 仅来自环境变量，不写入代码或日志。 */
@ConfigurationProperties("demo")
public record DemoProperties(Bailian bailian, Rag rag, Milvus milvus, Mcp mcp, Github github) {
    public record Bailian(String apiKey, String baseUrl, String chatModel, String embeddingModel,
                          String rerankModel, int dimension, int timeoutSeconds) {}
    public record Rag(Path knowledgeDir, Path indexDir, int chunkSize, int overlap,
                      int recallTopK, int finalTopK, Double minRerankScore) {}
    public record Milvus(String uri, String token, String collection) {}
    public record Mcp(String url, String businessUrl) {}
    public record Github(String apiUrl, String token) {}
}
