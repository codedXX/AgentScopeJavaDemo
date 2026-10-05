// 配置记录集中放在 config 包，避免业务类自行读取环境变量。
package com.example.salesagent.config;

// Path 将配置中的文件目录绑定为可直接用于文件 API 的路径对象。
import java.nio.file.Path;
// Spring 通过该注解把 demo 前缀下的配置绑定到 record。
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务参数集中管理；API Key 仅来自环境变量，不写入代码或日志。 */
// 绑定 application.yml 或环境变量中的 demo 配置组。
@ConfigurationProperties("demo")
// 聚合模型、检索、Milvus、MCP 与 GitHub 的子配置。
public record DemoProperties(
        // 百炼聊天、Embedding 与 Rerank 模型的连接和生成配置。
        Bailian bailian,
        // 知识目录、分块参数与检索保留数量。
        Rag rag,
        // Milvus 服务连接及集合名称。
        Milvus milvus,
        // MCP 工具服务和模拟业务接口地址。
        Mcp mcp,
        // GitHub API 连接地址与认证凭证。
        Github github) {
    // 描述百炼模型适配器共用的配置。
    public record Bailian(
            // 从外部配置注入的百炼 API Key。
            String apiKey,
            // 百炼基础地址，由各 SDK 适配其路径约定。
            String baseUrl,
            // AgentScope 使用的聊天模型名称。
            String chatModel,
            // 文档及查询向量化使用的 Embedding 模型名称。
            String embeddingModel,
            // 候选证据重排序使用的模型名称。
            String rerankModel,
            // 向量维度，必须与模型输出和向量库列定义一致。
            int dimension,
            // 一次模型请求的超时秒数。
            int timeoutSeconds) {}
    // 描述文档处理与检索环节的可调参数。
    public record Rag(
            // 原始知识文档所在目录。
            Path knowledgeDir,
            // Lucene 索引及入库状态文件所在目录。
            Path indexDir,
            // 子分块目标字符数。
            int chunkSize,
            // 相邻子分块共享的字符数，减少边界上下文丢失。
            int overlap,
            // 每路初始召回需要保留的候选数量。
            int recallTopK,
            // Rerank 后供模型使用的最终证据数量。
            int finalTopK,
            // 重排分数门槛；低于门槛的证据会被过滤。
            Double minRerankScore) {}
    // 描述 Milvus 向量存储的连接信息。
    public record Milvus(
            // Milvus 服务 URI。
            String uri,
            // Milvus 认证 token。
            String token,
            // 知识分块向量所在集合名称。
            String collection) {}
    // 描述 MCP 服务与业务接口端点。
    public record Mcp(
            // 问答端连接的 MCP Streamable HTTP 地址。
            String url,
            // MCP 业务工具调用的模拟商品接口地址。
            String businessUrl) {}
    // 描述仓库工具调用 GitHub API 所需参数。
    public record Github(
            // GitHub API 基础地址。
            String apiUrl,
            // GitHub API 认证 token。
            String token) {}
}
