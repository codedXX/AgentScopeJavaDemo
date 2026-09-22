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

    /** 文件和索引更新共用同一写锁；失败时保留原文件，供重建重试。 */
    public RebuildStatus upload(String filename, byte[] content) {
        if (filename == null || filename.length() > 120 || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)
                || !filename.toLowerCase(java.util.Locale.ROOT).matches(".+\\.(md|txt)")) {
            throw new IllegalArgumentException("仅支持文件名不超过120字符的 TXT、Markdown 文件");
        }
        if (content.length == 0 || content.length > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("文件不能为空，且不能超过 5 MB");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
            if (text.replace("\uFEFF", "").isBlank() || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("请上传包含正文的 UTF-8 文本文件");
            }
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("文件必须使用 UTF-8 编码", e);
        }
        return rebuildWithUpload(filename, content);
    }

    public RebuildStatus rebuild() {
        return rebuildWithUpload(null, null);
    }

    private RebuildStatus rebuildWithUpload(String filename, byte[] content) {
        if (!rebuilding.compareAndSet(false, true)) throw new RebuildInProgressException();
        lock.writeLock().lock();
        long started = System.nanoTime();
        try {
            state = new RebuildStatus(false, 0, state.rebuiltAt(), "正在重建");
            Files.createDirectories(indexDir);
            Files.deleteIfExists(indexDir.resolve(MANIFEST));
            if (filename != null) {
                // 每次上传保存到独立目录，同名上传也不会覆盖已有资料。
                Path uploadDir = knowledgeDir.resolve("uploads").resolve(java.util.UUID.randomUUID().toString());
                Files.createDirectories(uploadDir);
                Files.write(uploadDir.resolve(filename), content, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
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
