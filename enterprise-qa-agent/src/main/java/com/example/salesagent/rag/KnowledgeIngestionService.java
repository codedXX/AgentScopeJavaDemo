// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 EmbeddingClient，用于可替换的文本向量化接口。
import com.example.salesagent.bailian.EmbeddingClient;
// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 ObjectMapper，用于JSON 序列化与反序列化。
import com.fasterxml.jackson.databind.ObjectMapper;
// 引入 IOException，用于文件或网络 I/O 受检异常。
import java.io.IOException;
// 引入 StandardCharsets，用于明确指定 UTF-8 文本编码。
import java.nio.charset.StandardCharsets;
// 引入 Files，用于文件读取、写入和目录操作。
import java.nio.file.Files;
// 引入 Path，用于安全组合与规范化文件路径。
import java.nio.file.Path;
// 引入 StandardCopyOption，用于原子移动与覆盖替换文件选项。
import java.nio.file.StandardCopyOption;
// 引入 Instant，用于持久化成功重建的时间点。
import java.time.Instant;
// 引入 ArrayList，用于按插入顺序收集可变列表。
import java.util.ArrayList;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
// 引入 ReentrantReadWriteLock，用于协调建库独占写入与检索共享读取。
import java.util.concurrent.locks.ReentrantReadWriteLock;
// 引入 AtomicBoolean，用于无锁竞争重建执行资格。
import java.util.concurrent.atomic.AtomicBoolean;
// 引入 Supplier，用于延迟创建连接或执行读锁操作。
import java.util.function.Supplier;
// 引入 Stream，用于关闭递归文件扫描流。
import java.util.stream.Stream;

/** 以写锁全量重建两路索引；只有计数与清单一致时发布 ready=true。 */
// 管理文件上传、索引全量重建、持久化清单和可并发查询的就绪状态。
public final class KnowledgeIngestionService implements KnowledgeReadiness {
    // 固定清单文件名；清单记录被成功发布的索引版本。
    private static final String MANIFEST = "manifest.json";
    // 知识文档切分器，同时负责可选父子映射的生成和发布。
    private final DocumentChunker chunker;
    // 可清空、写入和计数的 BM25 索引。
    private final WritableKeywordIndex keywordIndex;
    // 可重建并发布的向量存储，实现与关键词索引相同的分块集合。
    private final WritableVectorChunkStore vectorStore;
    // 为知识子块批量生成语义向量的客户端。
    private final EmbeddingClient embeddingClient;
    // 待入库的知识资料根目录。
    private final Path knowledgeDir;
    // 本地索引和重建清单的持久化目录。
    private final Path indexDir;
    // 配置中的向量维度，用于写入前校验和存储初始化。
    private final int dimension;
    // Embedding 模型与分块配置签名，防止配置变化后误用旧索引。
    private final String signature;
    // 使用公平读写锁协调检索和重建，避免写入长期等待。
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    // 原子重建标志用于拒绝并发重建，并提前阻止新检索进入。
    private final AtomicBoolean rebuilding = new AtomicBoolean();
    // 自动注册 Jackson 模块，支持清单中的 Instant 时间字段。
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    // volatile 让读线程立即看到最新状态；初始状态要求先重建。
    private volatile RebuildStatus state = new RebuildStatus(false, 0, null, "需要重建");

    // 构造兼容旧调用方式的知识入库服务。
    public KnowledgeIngestionService(DocumentChunker chunker, WritableKeywordIndex keywordIndex,
                                     // 接收向量存储和 Embedding 客户端作为入库依赖。
                                     WritableVectorChunkStore vectorStore, EmbeddingClient embeddingClient,
                                     // 接收知识目录、索引目录以及向量维度。
                                     Path knowledgeDir, Path indexDir, int dimension) {
        // 为旧调用方式使用固定 legacy 配置签名。
        this(chunker,keywordIndex,vectorStore,embeddingClient,knowledgeDir,indexDir,dimension,"legacy");
    }
    // 构造带模型与切分配置签名的知识入库服务。
    public KnowledgeIngestionService(DocumentChunker chunker, WritableKeywordIndex keywordIndex,
                                     // 接收向量存储与向量模型依赖。
                                     WritableVectorChunkStore vectorStore, EmbeddingClient embeddingClient,
                                     // 接收目录、维度和恢复旧索引时需要比对的配置签名。
                                     Path knowledgeDir, Path indexDir, int dimension, String signature) {
        // 保存知识分块依赖。
        this.chunker = chunker;
        // 保存关键词索引写入依赖。
        this.keywordIndex = keywordIndex;
        // 保存向量索引写入依赖。
        this.vectorStore = vectorStore;
        // 保存向量化客户端。
        this.embeddingClient = embeddingClient;
        // 保存知识文件所在目录。
        this.knowledgeDir = knowledgeDir;
        // 保存清单和本地索引所在目录。
        this.indexDir = indexDir;
        // 保存预期向量维度。
        this.dimension = dimension;
        // 保存用于判断旧索引兼容性的配置签名。
        this.signature = signature;
        // 启动时检查持久化清单与两个索引，尝试恢复可查询状态。
        recoverReadiness();
    }

    /** 文件和索引更新共用同一写锁；失败时保留原文件，供重建重试。 */
    // 校验上传文件名称和正文编码，然后保存资料并重建索引。
    public RebuildStatus upload(String filename, byte[] content) {
        // 拒绝空名称、过长名称和路径分隔符，防止上传路径逃逸。
        if (filename == null || filename.length() > 120 || filename.contains("/") || filename.contains("\\")
                // 继续拒绝换行、制表符等控制字符，保证文件名可安全展示。
                || filename.chars().anyMatch(Character::isISOControl)
                // 使用固定区域规则转小写，只允许 Markdown 与纯文本扩展名。
                || !filename.toLowerCase(java.util.Locale.ROOT).matches(".+\\.(md|txt)")) {
            // 向调用方返回上传名称或格式限制的具体说明。
            throw new IllegalArgumentException("仅支持文件名不超过120字符的 TXT、Markdown 文件");
        }
        // 校验正文至少一个字节且不超过 5 MiB。
        if (content.length == 0 || content.length > 5 * 1024 * 1024) {
            // 拒绝空文件或超过上传大小限制的内容。
            throw new IllegalArgumentException("文件不能为空，且不能超过 5 MB");
        }
        // 使用严格 UTF-8 解码确认上传内容可作为文本处理。
        try {
            // 由 UTF-8 解码器将上传字节解析为字符串；非法序列会抛出编码异常。
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
            // 忽略 BOM 后仍需有正文，并且不允许含有 NUL 字符。
            if (text.replace("\uFEFF", "").isBlank() || text.indexOf('\0') >= 0) {
                // 拒绝只有 BOM、空白或包含二进制特征的上传文件。
                throw new IllegalArgumentException("请上传包含正文的 UTF-8 文本文件");
            }
        // 捕获非法 UTF-8 字节序列导致的解码失败。
        } catch (java.nio.charset.CharacterCodingException e) {
            // 告知调用方上传内容必须使用 UTF-8 编码。
            throw new IllegalArgumentException("文件必须使用 UTF-8 编码", e);
        }
        // 名称和内容通过校验后，在同一次写锁保护下执行保存与重建。
        return rebuildWithUpload(filename, content);
    }

    // 对当前知识目录中的全部资料执行全量重建。
    public RebuildStatus rebuild() {
        // 不上传新文件，只使用已经存在的资料重建索引。
        return rebuildWithUpload(null, null);
    }

    // 统一实现上传后的重建和单独的全量重建。
    private RebuildStatus rebuildWithUpload(String filename, byte[] content) {
        // 用 CAS 抢占重建资格；已有重建任务时立即拒绝重复请求。
        if (!rebuilding.compareAndSet(false, true)) throw new RebuildInProgressException();
        // 获取独占写锁，确保建库期间无法读到部分更新的索引。
        lock.writeLock().lock();
        // 记录重建开始时间，使用单调时钟测量耗时。
        long started = System.nanoTime();
        // 把重建和上传放在同一个异常处理与资源释放范围内。
        try {
            // 立即将知识库标记为未就绪，保留上次成功重建时间用于展示。
            state = new RebuildStatus(false, 0, state.rebuiltAt(), "正在重建");
            // 确保索引清单目录存在。
            Files.createDirectories(indexDir);
            // 删除上次成功清单，避免本次失败后重启误以为旧清单仍有效。
            Files.deleteIfExists(indexDir.resolve(MANIFEST));
            // 只有上传流程才需要把新文件写入知识目录。
            if (filename != null) {
                // 每次上传保存到独立目录，同名上传也不会覆盖已有资料。
                // 为每次上传创建随机独立目录，同名文件也保留多个版本。
                Path uploadDir = knowledgeDir.resolve("uploads").resolve(java.util.UUID.randomUUID().toString());
                // 创建本次上传专属目录。
                Files.createDirectories(uploadDir);
                // 以 CREATE_NEW 写入新文件，避免意外覆盖已存在资料。
                Files.write(uploadDir.resolve(filename), content, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            // 清空待重建的旧父子文档映射。
            chunker.resetParents();
            // 读取支持的知识文件并切分为完整的子块集合。
            List<KnowledgeChunk> chunks = readChunks();
            // 按子块顺序批量生成正文向量。
            List<float[]> vectors = embeddingClient.embed(chunks.stream().map(KnowledgeChunk::text).toList());
            // 入库前验证向量数量、维度与数值有效性。
            validateVectors(chunks, vectors);

            // 两路都从空索引开始；任何异常都会保持未就绪，下一次可安全重试。
            // 清空旧关键词索引，准备写入本次完整分块集合。
            keywordIndex.reset();
            // 按配置维度重建空向量索引。
            vectorStore.reset(dimension);
            // 把所有子块写入关键词索引。
            keywordIndex.upsert(chunks);
            // 把同一批子块及对应向量写入向量存储。
            vectorStore.upsert(chunks, vectors);
            // 刷盘或加载向量索引，确保后续检索能够读取新增数据。
            vectorStore.publish();
            // 确认关键词、向量和父子映射记录数一致。
            verifyCounts(chunks.size());

            // 持久化与本次索引相匹配的父子文档映射。
            chunker.publishParents();
            // 记录此次成功重建的时间，用作索引版本。
            Instant rebuiltAt = Instant.now();
            // 写入分块数量、时间、维度和配置签名组成的清单。
            writeManifest(new Manifest(chunks.size(), rebuiltAt, dimension, signature));
            // 只有所有索引和清单都发布成功后，才设置 ready=true。
            state = new RebuildStatus(true, chunks.size(), rebuiltAt,
                    // 将单调时钟测得的重建毫秒耗时写入状态消息。
                    "重建完成，耗时 " + ((System.nanoTime() - started) / 1_000_000) + "ms");
            // 返回已经发布成功的重建状态。
            return state;
        // 捕获任意重建阶段异常，统一撤销就绪状态。
        } catch (Exception e) {
            // 记录失败原因，保留上次成功重建时间供状态接口查询。
            state = new RebuildStatus(false, 0, state.rebuiltAt(), "重建失败: " + e.getMessage());
            // 原有运行时异常直接传播，保留其具体业务类型。
            if (e instanceof RuntimeException runtime) throw runtime;
            // 将 IOException 等受检异常包装为知识库重建失败。
            throw new IllegalStateException("知识库重建失败", e);
        // 无论成功还是失败，都执行锁和重建资格释放。
        } finally {
            // 释放独占写锁，允许后续检索或重建进入。
            lock.writeLock().unlock();
            // 撤销原子重建标志，允许调用方修复原因后重新建库。
            rebuilding.set(false);
        }
    }

    // 返回当前不可变重建状态；volatile 字段保障线程可见性。
    public RebuildStatus status() { return state; }
    // 以成功重建时间作为检索缓存版本，更新索引后自动隔离旧缓存。
    @Override public String revision() { return String.valueOf(state.rebuiltAt()); }
    // 返回当前状态中的 ready 标志，供检索与健康检查使用。
    @Override public boolean isReady() { return state.ready(); }

    // 在读锁保护下执行调用方提供的检索操作，并保留其返回类型。
    @Override public <T> T withReadLock(Supplier<T> action) {
        // 重建一旦排队，新检索立即返回未就绪，避免等待后误以为服务一直可用。
        // 有重建任务或无法立即取得读锁时拒绝请求，避免等待后误报持续可用。
        if (rebuilding.get() || !lock.readLock().tryLock()) throw new KnowledgeNotReadyException();
        // 在锁保护范围内再次检查状态并执行检索。
        try {
            // 防止第一次检查后发生的重建排队竞态，且要求知识库已经就绪。
            if (rebuilding.get() || !state.ready()) throw new KnowledgeNotReadyException();
            // 执行调用方提供的检索逻辑，并把结果原样返回。
            return action.get();
        }
        // 不论检索返回还是抛错，均释放读锁。
        finally { lock.readLock().unlock(); }
    }

    // 递归扫描知识目录中的 Markdown 和纯文本文件并切分。
    private List<KnowledgeChunk> readChunks() throws IOException {
        // 知识目录不存在或不是目录时明确报告配置问题。
        if (!Files.isDirectory(knowledgeDir)) throw new IllegalStateException("知识目录不存在: " + knowledgeDir);
        // 按扫描顺序累积所有文件的分块。
        List<KnowledgeChunk> result = new ArrayList<>();
        // 创建可关闭的递归文件路径流，避免目录句柄泄漏。
        try (Stream<Path> paths = Files.walk(knowledgeDir)) {
            // 过滤目录和其他非普通文件条目。
            paths.filter(Files::isRegularFile)
                    // 根据文件扩展名筛选受支持的知识文件。
                    .filter(path -> {
                        // 取得文件名称并转为小写，用于扩展名匹配。
                        String name = path.getFileName().toString().toLowerCase();
                        // 只接受 .md 与 .txt 文件。
                        return name.endsWith(".md") || name.endsWith(".txt");
                    })
                    // 排序路径，保证相同资料重建时分块顺序可重复。
                    .sorted()
                    // 切分每个受支持文件，并把其全部子块追加到结果。
                    .forEach(path -> result.addAll(chunker.split(path)));
        }
        // 返回不可变分块快照，防止建库过程中集合被外部更改。
        return List.copyOf(result);
    }

    // 检查模型响应是否能够与本次知识分块一一对应。
    private void validateVectors(List<KnowledgeChunk> chunks, List<float[]> vectors) {
        // 拒绝空响应或分块与向量条数不匹配的结果。
        if (vectors == null || vectors.size() != chunks.size()) throw new IllegalStateException("Embedding 返回数量与分块数不一致");
        // 逐条检查返回的知识向量。
        for (float[] vector : vectors) {
            // 要求每条向量非空且维度等于存储配置。
            if (vector == null || vector.length != dimension) throw new IllegalStateException("Embedding 向量维度不正确");
            // 拒绝 NaN 和无穷数，避免污染相似度检索。
            for (float value : vector) if (!Float.isFinite(value)) throw new IllegalStateException("Embedding 包含非有限数值");
        }
    }

    // 校验本次发布的全部索引记录数。
    private void verifyCounts(long expected) {
        // 先确认子块到父文档的映射数量符合预期。
        chunker.verifyParents(expected);
        // 分别比对关键词索引与向量索引的记录数。
        if (keywordIndex.count() != expected || vectorStore.count() != expected) {
            // 任一路数量不匹配都会阻止发布 ready=true。
            throw new IllegalStateException("两路索引记录数不一致");
        }
    }

    // 根据磁盘清单判断进程重启后是否可继续使用原索引。
    private void recoverReadiness() {
        // 定位当前索引目录下的持久化清单。
        Path manifestPath = indexDir.resolve(MANIFEST);
        // 没有清单说明尚无完整重建结果，保持初始未就绪状态。
        if (!Files.isRegularFile(manifestPath)) return;
        // 清单反序列化与索引检查在同一恢复异常处理范围内。
        try {
            // 从 JSON 文件恢复清单记录，包括成功重建时间。
            Manifest manifest = mapper.readValue(manifestPath.toFile(), Manifest.class);
            // 向量维度变化时拒绝复用旧数据。
            if (manifest.dimension() != dimension) throw new IllegalStateException("向量维度已变化");
            // 模型或切分策略签名变化时要求重新建库。
            if (!signature.equals(manifest.signature())) throw new IllegalStateException("Embedding或分块配置已变化");
            // 校验清单里的数量与实际两路索引、父子映射一致。
            verifyCounts(manifest.chunkCount());
            // 全部恢复检查通过后设置就绪状态并恢复索引版本时间。
            state = new RebuildStatus(true, manifest.chunkCount(), manifest.rebuiltAt(), "已从清单恢复");
        // 损坏清单、存储不可用或配置不兼容均使恢复失败。
        } catch (Exception e) {
            // 恢复失败时保持未就绪，并向用户提示需要重新建库。
            state = new RebuildStatus(false, 0, null, "索引与清单不一致，需要重建");
        }
    }

    // 把成功重建清单先写入临时文件，再替换最终清单。
    private void writeManifest(Manifest manifest) throws IOException {
        // 确保用于保存清单的目录存在。
        Files.createDirectories(indexDir);
        // 把临时文件放在同一目录，为原子移动提供条件。
        Path temporary = indexDir.resolve(MANIFEST + ".tmp");
        // 使用 UTF-8 写入序列化 JSON，包含 Instant 等清单字段。
        Files.writeString(temporary, mapper.writeValueAsString(manifest), StandardCharsets.UTF_8);
        // 优先尝试文件系统支持的原子替换。
        try {
            // 将临时清单移动为正式清单，并允许替换旧文件。
            Files.move(temporary, indexDir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING,
                    // 使用 ATOMIC_MOVE，避免读到写入一半的清单。
                    StandardCopyOption.ATOMIC_MOVE);
        // 文件系统不支持原子移动时执行兼容回退。
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 回退为普通替换移动，仍使用完整写好的临时清单。
            Files.move(temporary, indexDir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // 清单只保存验证索引一致性所需的数量、时间、维度和配置签名。
    private record Manifest(long chunkCount, Instant rebuiltAt, int dimension, String signature) {}
}
