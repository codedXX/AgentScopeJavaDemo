package com.example.salesagent.rag;

import com.example.salesagent.bailian.EmbeddingClient;
import com.example.salesagent.model.KnowledgeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** 以写锁全量重建两路索引；只有计数与清单一致时发布 ready=true。 */
public final class KnowledgeIngestionService implements KnowledgeReadiness {
    private static final String MANIFEST = "manifest.json";
    private final DocumentChunker chunker;
    private final WritableKeywordIndex keywordIndex;
    private final WritableVectorChunkStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final Path knowledgeDir;
    private final Path indexDir;
    private final int dimension;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final AtomicBoolean rebuilding = new AtomicBoolean();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private volatile RebuildStatus state = new RebuildStatus(false, 0, null, "需要重建");

    public KnowledgeIngestionService(DocumentChunker chunker, WritableKeywordIndex keywordIndex,
                                     WritableVectorChunkStore vectorStore, EmbeddingClient embeddingClient,
                                     Path knowledgeDir, Path indexDir, int dimension) {
        this.chunker = chunker;
        this.keywordIndex = keywordIndex;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.knowledgeDir = knowledgeDir;
        this.indexDir = indexDir;
        this.dimension = dimension;
        recoverReadiness();
    }

    public RebuildStatus rebuild() {
        if (!rebuilding.compareAndSet(false, true)) throw new RebuildInProgressException();
        lock.writeLock().lock();
        long started = System.nanoTime();
        try {
            state = new RebuildStatus(false, 0, state.rebuiltAt(), "正在重建");
            Files.createDirectories(indexDir);
            Files.deleteIfExists(indexDir.resolve(MANIFEST));
            List<KnowledgeChunk> chunks = readChunks();
            List<float[]> vectors = embeddingClient.embed(chunks.stream().map(KnowledgeChunk::text).toList());
            validateVectors(chunks, vectors);

            // 两路都从空索引开始；任何异常都会保持未就绪，下一次可安全重试。
            keywordIndex.reset();
            vectorStore.reset(dimension);
            keywordIndex.upsert(chunks);
            vectorStore.upsert(chunks, vectors);
            vectorStore.publish();
            verifyCounts(chunks.size());

            Instant rebuiltAt = Instant.now();
            writeManifest(new Manifest(chunks.size(), rebuiltAt, dimension));
            state = new RebuildStatus(true, chunks.size(), rebuiltAt,
                    "重建完成，耗时 " + ((System.nanoTime() - started) / 1_000_000) + "ms");
            return state;
        } catch (Exception e) {
            state = new RebuildStatus(false, 0, state.rebuiltAt(), "重建失败: " + e.getMessage());
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("知识库重建失败", e);
        } finally {
            lock.writeLock().unlock();
            rebuilding.set(false);
        }
    }

    public RebuildStatus status() { return state; }
    @Override public boolean isReady() { return state.ready(); }

    @Override public <T> T withReadLock(Supplier<T> action) {
        // 重建一旦排队，新检索立即返回未就绪，避免等待后误以为服务一直可用。
        if (rebuilding.get() || !lock.readLock().tryLock()) throw new KnowledgeNotReadyException();
        try {
            if (rebuilding.get() || !state.ready()) throw new KnowledgeNotReadyException();
            return action.get();
        }
        finally { lock.readLock().unlock(); }
    }

    private List<KnowledgeChunk> readChunks() throws IOException {
        if (!Files.isDirectory(knowledgeDir)) throw new IllegalStateException("知识目录不存在: " + knowledgeDir);
        List<KnowledgeChunk> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(knowledgeDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".txt");
                    })
                    .sorted()
                    .forEach(path -> result.addAll(chunker.split(path)));
        }
        return List.copyOf(result);
    }

    private void validateVectors(List<KnowledgeChunk> chunks, List<float[]> vectors) {
        if (vectors == null || vectors.size() != chunks.size()) throw new IllegalStateException("Embedding 返回数量与分块数不一致");
        for (float[] vector : vectors) {
            if (vector == null || vector.length != dimension) throw new IllegalStateException("Embedding 向量维度不正确");
            for (float value : vector) if (!Float.isFinite(value)) throw new IllegalStateException("Embedding 包含非有限数值");
        }
    }

    private void verifyCounts(long expected) {
        if (keywordIndex.count() != expected || vectorStore.count() != expected) {
            throw new IllegalStateException("两路索引记录数不一致");
        }
    }

    private void recoverReadiness() {
        Path manifestPath = indexDir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestPath)) return;
        try {
            Manifest manifest = mapper.readValue(manifestPath.toFile(), Manifest.class);
            if (manifest.dimension() != dimension) throw new IllegalStateException("向量维度已变化");
            verifyCounts(manifest.chunkCount());
            state = new RebuildStatus(true, manifest.chunkCount(), manifest.rebuiltAt(), "已从清单恢复");
        } catch (Exception e) {
            state = new RebuildStatus(false, 0, null, "索引与清单不一致，需要重建");
        }
    }

    private void writeManifest(Manifest manifest) throws IOException {
        Files.createDirectories(indexDir);
        Path temporary = indexDir.resolve(MANIFEST + ".tmp");
        Files.writeString(temporary, mapper.writeValueAsString(manifest), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, indexDir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, indexDir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Manifest(long chunkCount, Instant rebuiltAt, int dimension) {}
}
