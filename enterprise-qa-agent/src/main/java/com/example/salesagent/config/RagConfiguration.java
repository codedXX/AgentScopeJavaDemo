// RAG 组件装配归入 config 包，集中管理可替换的检索后端。
package com.example.salesagent.config;
// 引入查询扩展所需的统一模型网关。
import com.example.salesagent.agent.LlmGateway;
// 引入 Embedding 与 Rerank 客户端接口。
import com.example.salesagent.bailian.*;
// 引入切分、父文档、索引、入库与混合检索组件。
import com.example.salesagent.rag.*;
// 导入配置、Bean 与 profile 注解。
import org.springframework.context.annotation.*;
// 声明 RAG 的 Spring Bean 工厂。
@Configuration
// 只在问答应用启动时建立 RAG 组件。
@Profile("app")
// 将知识检索的各阶段实现和配置组合成可注入组件。
public class RagConfiguration {
    // 注册持久化父文档存储，服务于子块召回后的上下文回溯。
    @Bean
    // 父文档存储工厂使用配置中的映射文件路径。
    ParentDocumentStore parents(EnterpriseProperties e) {
        // 根据 enterprise 配置选择父文档映射文件。
        return new ParentDocumentStore(e.parentFile());
    }
    // 注册文档切分器，同时注入语义分块所需的向量模型。
    @Bean
    // 文档切分工厂同时支持普通和语义父子分块。
    DocumentChunker chunker(DemoProperties p, EnterpriseProperties e, ParentDocumentStore parents, EmbeddingClient embedding) {
        // 开启语义切分时构造父块分割器；否则传 null 使用普通父块切分。
        var semantic=e.semanticChunkingEnabled() ? new SemanticParentSplitter(embedding,e.parentSize(),p.rag().chunkSize(),e.semanticThreshold()) : null;
        // 指定知识目录、子块大小、重叠、父文档存储与可选语义分割器。
        return new DocumentChunker(p.rag().knowledgeDir(), p.rag().chunkSize(), p.rag().overlap(), parents, e.parentSize(),semantic);
    }
    // 注册 Lucene BM25 索引；应用关闭时调用 close 释放文件资源。
    @Bean(destroyMethod="close")
    // 关键词索引工厂从 demo.rag 取得索引目录。
    LuceneKeywordIndex keywordIndex(DemoProperties p) {
        // 使用配置目录保存关键词索引。
        return new LuceneKeywordIndex(p.rag().indexDir());
    }
    // 注册向量存储统一接口；各后端在应用关闭时执行 close。
    @Bean(destroyMethod="close")
    // 统一向量存储工厂根据 enterprise.vector-backend 选择实现。
    WritableVectorChunkStore vectorStore(DemoProperties p, EnterpriseProperties e) {
        // 按后端名称选择实现，调用方不需要了解后端连接细节。
        return switch (e.vectorBackend().toLowerCase()) {
            // Milvus 后端使用服务 URI、token 与集合名称。
            case "milvus" -> new MilvusChunkStore(p.milvus().uri(), p.milvus().token(), p.milvus().collection());
            // PGVector 后端使用 PostgreSQL JDBC 连接参数。
            case "pgvector" -> new PgVectorChunkStore(e.pgUrl(), e.pgUser(), e.pgPassword());
            // FAISS 后端调用本地 Python 向量服务。
            case "faiss" -> new FaissChunkStore(e.faissUrl());
            // 配置拼写错误时及时失败，防止静默连接到错误后端。
            default -> throw new IllegalArgumentException("VECTOR_BACKEND 只支持 milvus/pgvector/faiss");
        };
    }
    // 注册入库服务，以关键词索引、向量索引和模型客户端协同建立知识索引。
    @Bean
    // 入库工厂注入已配置的切分器与关键词索引。
    KnowledgeIngestionService ingestion(DocumentChunker chunker, LuceneKeywordIndex keyword,
        // 注入可写向量存储、Embedding 客户端以及两组配置。
        WritableVectorChunkStore vector, EmbeddingClient embedding, DemoProperties p, EnterpriseProperties e) {
        // 将模型名称、服务地址和后端名称纳入索引签名，识别会改变向量含义的配置变化。
        String signature=String.join("|",p.bailian().embeddingModel(),p.bailian().baseUrl(),e.vectorBackend(),
            // 分块大小、重叠和父块大小也参与签名，确保参数变化后重新入库。
            Integer.toString(p.rag().chunkSize()),Integer.toString(p.rag().overlap()),Integer.toString(e.parentSize()),
            // 语义分块开关和阈值参与签名，避免复用不同切分策略的索引。
            Boolean.toString(e.semanticChunkingEnabled()),Double.toString(e.semanticThreshold()));
        // 将目录、向量维度和配置签名交给入库服务执行一致性检查。
        return new KnowledgeIngestionService(chunker,keyword,vector,embedding,p.rag().knowledgeDir(),p.rag().indexDir(),p.bailian().dimension(),signature);
    }
    // 注册双路召回、融合、重排和父文档回溯的检索器。
    @Bean
    // 检索工厂首先接收 BM25 索引与可替换的向量后端。
    HybridRetriever retriever(LuceneKeywordIndex keyword, WritableVectorChunkStore vector,
        // 同时注入向量化、重排与入库就绪组件，确保检索使用已建立的索引。
        EmbeddingClient embedding, RerankClient rerank, KnowledgeIngestionService ready, DemoProperties p,
        // 注入扩展配置、模型网关和父文档存储。
        EnterpriseProperties e, LlmGateway llm, ParentDocumentStore parents) {
        // 将双路索引、模型客户端和各阶段 TopK 参数交给混合检索器。
        return new HybridRetriever(keyword,vector,embedding,rerank,ready,p.rag().recallTopK(),p.rag().finalTopK(),
            // 传入分数门槛、Query Rewrite/HyDE、父文档回溯、RRF 参数及缓存时长。
            p.rag().minRerankScore(),new QueryExpansion(llm,e.hydeEnabled()),parents,e.rrfK(),e.fusionTopK(),e.cacheSeconds());
    }
}
