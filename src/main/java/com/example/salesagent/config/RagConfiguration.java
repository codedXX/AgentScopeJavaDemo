package com.example.salesagent.config;

import com.example.salesagent.bailian.*;
import com.example.salesagent.rag.*;
import org.springframework.context.annotation.*;

/** 在这里组装 RAG 组件，业务类本身不依赖 Spring，方便独立阅读和测试。 */
@Configuration @Profile("app")
public class RagConfiguration {
    @Bean DocumentChunker chunker(DemoProperties p) { return new DocumentChunker(p.rag().knowledgeDir(), p.rag().chunkSize(), p.rag().overlap()); }
    @Bean(destroyMethod = "close") LuceneKeywordIndex keywordIndex(DemoProperties p) { return new LuceneKeywordIndex(p.rag().indexDir()); }
    @Bean(destroyMethod = "close") MilvusChunkStore vectorStore(DemoProperties p) { return new MilvusChunkStore(p.milvus().uri(), p.milvus().token(), p.milvus().collection()); }
    @Bean KnowledgeIngestionService ingestion(DocumentChunker chunker, LuceneKeywordIndex keywordIndex,
                                              MilvusChunkStore vectorStore, EmbeddingClient embedding, DemoProperties p) {
        return new KnowledgeIngestionService(chunker, keywordIndex, vectorStore, embedding,
                p.rag().knowledgeDir(), p.rag().indexDir(), p.bailian().dimension());
    }
    @Bean HybridRetriever retriever(LuceneKeywordIndex keywordIndex, MilvusChunkStore vectorStore,
                                    EmbeddingClient embedding, RerankClient rerank, KnowledgeIngestionService readiness, DemoProperties p) {
        return new HybridRetriever(keywordIndex, vectorStore, embedding, rerank, readiness,
                p.rag().recallTopK(), p.rag().finalTopK(), p.rag().minRerankScore());
    }
}
