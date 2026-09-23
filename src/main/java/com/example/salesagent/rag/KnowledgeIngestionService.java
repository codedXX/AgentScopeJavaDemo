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

/**
 * 管理知识文件入库和两路索引的就绪状态。上传与重建共用写锁，检索使用读锁，避免查询读到重建中的索引。
 * 磁盘清单保存预期分块数和向量维度；只有重建成功且两路计数一致时才标记为可检索。
 */
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

    /**
     * 校验文件名、大小和 UTF-8 正文后触发全量重建。上传文件保存在独立目录，失败时不会删除原文件；
     * 修复外部服务后可直接调用 rebuild() 重试，不必再次上传。
     */
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

    /** 用知识目录中的所有文件重新建立两路索引。 */
    public RebuildStatus rebuild() {
        return rebuildWithUpload(null, null);
    }

    /**
     * 原子标志先阻止重复重建，再获取写锁等待已有检索结束。先删除旧清单，使中途失败不会在下次启动时
     * 被误认为就绪；随后切分全部文件、生成向量、清空并写入两路索引、校验计数，最后发布新清单。
     */
    private RebuildStatus rebuildWithUpload(String filename, byte[] content) {
        if (!rebuilding.compareAndSet(false, true)) throw new RebuildInProgressException();
        lock.writeLock().lock();
        long started = System.nanoTime();
        try {
            state = new RebuildStatus(false, 0, state.getRebuiltAt(), "正在重建");
            Files.createDirectories(indexDir);
            Files.deleteIfExists(indexDir.resolve(MANIFEST));
            if (filename != null) {
                // 每次上传保存到独立目录，同名上传也不会覆盖已有资料。
                Path uploadDir = knowledgeDir.resolve("uploads").resolve(java.util.UUID.randomUUID().toString());
                Files.createDirectories(uploadDir);
                Files.write(uploadDir.resolve(filename), content, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            List<KnowledgeChunk> chunks = readChunks();
            List<float[]> vectors = embeddingClient.embed(chunks.stream().map(KnowledgeChunk::getText).toList());
            validateVectors(chunks, vectors);

            // 两路都从空索引开始；任何异常都会保持未就绪，下一次可安全重试。
            keywordIndex.reset();
            vectorStore.reset(dimension);
            keywordIndex.upsert(chunks);
            vectorStore.upsert(chunks, vectors);
            vectorStore.publish();
            verifyCounts(chunks.size());

            Instant rebuiltAt = Instant.now();
            writeManifest(new IndexManifest(chunks.size(), rebuiltAt, dimension));
            state = new RebuildStatus(true, chunks.size(), rebuiltAt,
                    "重建完成，耗时 " + ((System.nanoTime() - started) / 1_000_000) + "ms");
            return state;
        } catch (Exception e) {
            state = new RebuildStatus(false, 0, state.getRebuiltAt(), "重建失败: " + e.getMessage());
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IllegalStateException("知识库重建失败", e);
        } finally {
            lock.writeLock().unlock();
            rebuilding.set(false);
        }
    }

    /** 返回最近一次重建状态。 */
    public RebuildStatus status() { return state; }
    @Override public boolean isReady() { return state.isReady(); }

    @Override public <T> T withReadLock(Supplier<T> action) {
        // 重建一旦排队，新检索立即返回未就绪，避免等待后误以为服务一直可用。
        if (rebuilding.get() || !lock.readLock().tryLock()) throw new KnowledgeNotReadyException();
        try {
            if (rebuilding.get() || !state.isReady()) throw new KnowledgeNotReadyException();
            return action.get();
        }
        finally { lock.readLock().unlock(); }
    }

    /** 读取所有文本资料并切成片段。 */
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

    /** 确认向量数量、维度和数值都正确。 */
    private void validateVectors(List<KnowledgeChunk> chunks, List<float[]> vectors) {
        if (vectors == null || vectors.size() != chunks.size()) throw new IllegalStateException("Embedding 返回数量与分块数不一致");
        for (float[] vector : vectors) {
            if (vector == null || vector.length != dimension) throw new IllegalStateException("Embedding 向量维度不正确");
            for (float value : vector) if (!Float.isFinite(value)) throw new IllegalStateException("Embedding 包含非有限数值");
        }
    }

    /** 确认两路索引都写入了预期数量的片段。 */
    private void verifyCounts(long expected) {
        if (keywordIndex.count() != expected || vectorStore.count() != expected) {
            throw new IllegalStateException("两路索引记录数不一致");
        }
    }

    /**
     * 启动恢复只信任已落盘的清单，并检查配置维度以及 Lucene、Milvus 的实际记录数。
     * 任一检查失败都保持未就绪，等待显式重建。
     */
    private void recoverReadiness() {
        Path manifestPath = indexDir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestPath)) return;
        try {
            IndexManifest manifest = mapper.readValue(manifestPath.toFile(), IndexManifest.class);
            if (manifest.getDimension() != dimension) throw new IllegalStateException("向量维度已变化");
            verifyCounts(manifest.getChunkCount());
            state = new RebuildStatus(true, manifest.getChunkCount(), manifest.getRebuiltAt(), "已从清单恢复");
        } catch (Exception e) {
            state = new RebuildStatus(false, 0, null, "索引与清单不一致，需要重建");
        }
    }

    /**
     * 先完整写入临时 JSON，再尝试原子替换正式清单；文件系统不支持原子移动时退回普通替换。
     * 清单写入是重建流程的最后一步，供启动恢复判断索引是否已发布。
     */
    private void writeManifest(IndexManifest manifest) throws IOException {
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

}
