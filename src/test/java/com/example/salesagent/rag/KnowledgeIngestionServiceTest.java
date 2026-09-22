package com.example.salesagent.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.example.salesagent.model.KnowledgeChunk;
import com.example.salesagent.model.SearchHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeIngestionServiceTest {
    @TempDir Path tempDir;

    @Test
    void successfulRebuildPublishesMatchingIndexesAndManifest() throws Exception {
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        Files.writeString(knowledge.resolve("one.md"), "演示知识一");
        FakeWritableKeyword keyword = new FakeWritableKeyword();
        FakeWritableVector vector = new FakeWritableVector();
        KnowledgeIngestionService service = service(knowledge, keyword, vector);

        RebuildStatus rebuilt = service.rebuild();

        assertTrue(rebuilt.ready());
        assertEquals(1, rebuilt.chunkCount());
        assertEquals(keyword.ids, vector.ids);
        assertTrue(Files.exists(tempDir.resolve("index/manifest.json")));
        assertTrue(service.isReady());
    }

    @Test
    void failedSecondIndexLeavesServiceNotReady() throws Exception {
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        Files.writeString(knowledge.resolve("one.md"), "演示知识一");
        FakeWritableKeyword keyword = new FakeWritableKeyword();
        FakeWritableVector vector = new FakeWritableVector();
        vector.failUpsert = true;
        KnowledgeIngestionService service = service(knowledge, keyword, vector);

        assertThrows(IllegalStateException.class, service::rebuild);
        assertFalse(service.isReady());
        assertFalse(service.status().ready());
    }

    @Test
    void startupRequiresManifestCountsToMatchBothIndexes() throws Exception {
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        Path index = Files.createDirectories(tempDir.resolve("index"));
        Files.writeString(index.resolve("manifest.json"),
                "{\"chunkCount\":2,\"rebuiltAt\":\"2026-09-22T00:00:00Z\",\"dimension\":2}");
        FakeWritableKeyword keyword = new FakeWritableKeyword(); keyword.count = 2;
        FakeWritableVector vector = new FakeWritableVector(); vector.count = 1;

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                new DocumentChunker(knowledge, 100, 10), keyword, vector,
                texts -> texts.stream().map(t -> new float[]{1, 2}).toList(), knowledge, index, 2);

        assertFalse(service.isReady());
    }

    private KnowledgeIngestionService service(Path knowledge, FakeWritableKeyword keyword, FakeWritableVector vector) {
        return new KnowledgeIngestionService(new DocumentChunker(knowledge, 100, 10), keyword, vector,
                texts -> texts.stream().map(t -> new float[]{1, 2}).toList(), knowledge, tempDir.resolve("index"), 2);
    }

    private static final class FakeWritableKeyword implements WritableKeywordIndex {
        Set<String> ids = new LinkedHashSet<>(); long count;
        public void reset() { ids.clear(); count = 0; }
        public void upsert(List<KnowledgeChunk> chunks) { chunks.forEach(c -> ids.add(c.chunkId())); count = ids.size(); }
        public List<SearchHit> search(String query, int topK) { return List.of(); }
        public long count() { return count; }
    }

    private static final class FakeWritableVector implements WritableVectorChunkStore {
        Set<String> ids = new LinkedHashSet<>(); long count; boolean failUpsert;
        public void reset(int dimension) { ids.clear(); count = 0; }
        public void upsert(List<KnowledgeChunk> chunks, List<float[]> vectors) {
            if (failUpsert) throw new IllegalStateException("Milvus unavailable");
            chunks.forEach(c -> ids.add(c.chunkId())); count = ids.size();
        }
        public void publish() {}
        public List<SearchHit> search(float[] vector, int topK) { return List.of(); }
        public long count() { return count; }
    }
}
