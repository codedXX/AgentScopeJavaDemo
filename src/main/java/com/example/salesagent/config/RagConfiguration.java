package com.example.salesagent.config;

import com.example.salesagent.bailian.*;
import com.example.salesagent.rag.*;
import org.springframework.context.annotation.*;

/** 在这里组装 RAG 组件，业务类本身不依赖 Spring，方便独立阅读和测试。 */
@Configuration @Profile("app")
public class RagConfiguration {
    @Bean
    DocumentChunker chunker(DemoProperties properties) {
        return new DocumentChunker(properties.getRag().getKnowledgeDir(), properties.getRag().getChunkSize(),
                properties.getRag().getOverlap());
    }

    @Bean(destroyMethod = "close")
    LuceneKeywordIndex keywordIndex(DemoProperties properties) {
        return new LuceneKeywordIndex(properties.getRag().getIndexDir());
    }

    @Bean(destroyMethod = "close")
    MilvusChunkStore vectorStore(DemoProperties properties) {
        return new MilvusChunkStore(properties.getMilvus().getUri(), properties.getMilvus().getToken(),
                properties.getMilvus().getCollection());
    }

    @Bean
    KnowledgeIngestionService ingestion(DocumentChunker chunker, LuceneKeywordIndex keywordIndex,
                                        MilvusChunkStore vectorStore, EmbeddingClient embedding,
                                        DemoProperties properties) {
        return new KnowledgeIngestionService(chunker, keywordIndex, vectorStore, embedding,
                properties.getRag().getKnowledgeDir(), properties.getRag().getIndexDir(),
                properties.getBailian().getDimension());
    }

    @Bean
    HybridRetriever retriever(LuceneKeywordIndex keywordIndex, MilvusChunkStore vectorStore,
                              EmbeddingClient embedding, RerankClient rerank,
                              KnowledgeIngestionService readiness, DemoProperties properties) {
        return new HybridRetriever(keywordIndex, vectorStore, embedding, rerank, readiness,
                properties.getRag().getRecallTopK(), properties.getRag().getFinalTopK(),
                properties.getRag().getMinRerankScore());
    }
}
