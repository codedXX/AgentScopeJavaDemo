// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 SearchHit，用于检索命中分块、分数和通道。
import com.example.salesagent.model.SearchHit;
// 引入 Files，用于文件读取、写入和目录操作。
import java.nio.file.Files;
// 引入 Path，用于安全组合与规范化文件路径。
import java.nio.file.Path;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;
// 引入 TempDir，用于由 JUnit 自动隔离和清理测试临时目录。
import org.junit.jupiter.api.io.TempDir;

// 验证知识库上传、全量重建、恢复检查以及失败后的就绪状态。
class KnowledgeIngestionServiceTest {
    // 为每个测试创建独立知识目录和索引清单目录。
    @TempDir Path tempDir;

    // 验证重启恢复时必须匹配 Embedding 模型或切分配置签名。
    @Test void startupRejectsChangedModelOrChunkingSignature() throws Exception {
        // 创建隔离的知识目录。
        Path knowledge=Files.createDirectories(tempDir.resolve("knowledge"));
        // 写入一个可以形成单条知识分块的 Markdown 文件。
        Files.writeString(knowledge.resolve("one.md"),"演示知识");
        // 使用内存关键词和向量替身，隔离真实索引服务。
        var keyword=new FakeWritableKeyword();var vector=new FakeWritableVector();
        // 配置 100 字符分块上限和 10 字符重叠。
        var chunker=new DocumentChunker(knowledge,100,10);
        // 为每条正文返回固定二维向量，保持测试结果确定。
        com.example.salesagent.bailian.EmbeddingClient embedding=texts->texts.stream().map(t->new float[]{1,2}).toList();
        // 用 model-v1 签名创建入库服务。
        var service=new KnowledgeIngestionService(chunker,keyword,vector,embedding,knowledge,tempDir.resolve("index"),2,"model-v1");
        // 完成一次重建，生成带配置签名的成功清单。
        service.rebuild();
        // 断言配置签名不变时，新的服务实例可以恢复就绪。
        assertTrue(new KnowledgeIngestionService(chunker,keyword,vector,embedding,knowledge,tempDir.resolve("index"),2,"model-v1").isReady());
        // 断言签名改为 model-v2 后不能复用旧索引。
        assertFalse(new KnowledgeIngestionService(chunker,keyword,vector,embedding,knowledge,tempDir.resolve("index"),2,"model-v2").isReady());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证成功重建只有在两路记录与清单一致后发布就绪状态。
    void successfulRebuildPublishesMatchingIndexesAndManifest() throws Exception {
        // 创建测试知识目录。
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        // 写入一条基础知识正文。
        Files.writeString(knowledge.resolve("one.md"), "演示知识一");
        // 创建可记录写入 ID 的关键词索引替身。
        FakeWritableKeyword keyword = new FakeWritableKeyword();
        // 创建可记录写入 ID 的向量索引替身。
        FakeWritableVector vector = new FakeWritableVector();
        // 使用统一辅助方法构造二维向量入库服务。
        KnowledgeIngestionService service = service(knowledge, keyword, vector);

        // 执行全量重建并保存发布状态。
        RebuildStatus rebuilt = service.rebuild();

        // 断言重建报告 ready=true。
        assertTrue(rebuilt.ready());
        // 断言本次发布的分块数为一。
        assertEquals(1, rebuilt.chunkCount());
        // 断言关键词与向量索引保存完全相同的分块 ID。
        assertEquals(keyword.ids, vector.ids);
        // 断言成功清单已持久化到 manifest.json。
        assertTrue(Files.exists(tempDir.resolve("index/manifest.json")));
        // 断言服务层也已经开放检索。
        assertTrue(service.isReady());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证第二路索引写入失败时，不会把半成品索引标记为可用。
    void failedSecondIndexLeavesServiceNotReady() throws Exception {
        // 创建含一条知识的目录。
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        // 写入触发本次入库的正文。
        Files.writeString(knowledge.resolve("one.md"), "演示知识一");
        // 准备可正常写入的关键词替身。
        FakeWritableKeyword keyword = new FakeWritableKeyword();
        // 准备可控制失败的向量替身。
        FakeWritableVector vector = new FakeWritableVector();
        // 开启向量 upsert 故障，模拟远程存储不可用。
        vector.failUpsert = true;
        // 创建持有两路替身的入库服务。
        KnowledgeIngestionService service = service(knowledge, keyword, vector);

        // 断言全量重建向上层传播状态异常。
        assertThrows(IllegalStateException.class, service::rebuild);
        // 断言重建失败后检索就绪检查为 false。
        assertFalse(service.isReady());
        // 断言状态接口返回的 ready 标志也为 false。
        assertFalse(service.status().ready());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证启动恢复时不能接受两路记录数量不一致的清单。
    void startupRequiresManifestCountsToMatchBothIndexes() throws Exception {
        // 准备空的知识目录。
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        // 准备保存伪造成功清单的索引目录。
        Path index = Files.createDirectories(tempDir.resolve("index"));
        // 写入人工构造的清单，模拟之前成功重建的磁盘记录。
        Files.writeString(index.resolve("manifest.json"),
                // 清单声明二维向量和两个分块，以便与实际索引数量核对。
                "{\"chunkCount\":2,\"rebuiltAt\":\"2026-09-22T00:00:00Z\",\"dimension\":2}");
        // 让关键词索引替身报告两个记录。
        FakeWritableKeyword keyword = new FakeWritableKeyword(); keyword.count = 2;
        // 让向量索引替身仅报告一个记录，制造不一致。
        FakeWritableVector vector = new FakeWritableVector(); vector.count = 1;

        // 创建服务实例并自动执行清单恢复检查。
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                // 注入基础切分器及两个计数不一致的索引。
                new DocumentChunker(knowledge, 100, 10), keyword, vector,
                // 提供确定性二维向量配置，避免模型因素干扰恢复测试。
                texts -> texts.stream().map(t -> new float[]{1, 2}).toList(), knowledge, index, 2);

        // 断言服务保持未就绪，拒绝不一致的索引集合。
        assertFalse(service.isReady());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证上传同名文件会保留已有资料，并全量更新两路索引。
    void uploadPreservesExistingFilesAndPublishesBothIndexes() throws Exception {
        // 创建已有资料的知识目录。
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        // 保存原始 one.md 正文，稍后验证没有被覆盖。
        Files.writeString(knowledge.resolve("one.md"), "原有知识");
        // 准备记录 ID 的关键词替身。
        FakeWritableKeyword keyword = new FakeWritableKeyword();
        // 准备记录 ID 的向量替身。
        FakeWritableVector vector = new FakeWritableVector();
        // 创建上传和重建服务。
        KnowledgeIngestionService service = service(knowledge, keyword, vector);
        // 把新增知识正文按 UTF-8 编码为上传字节。
        byte[] content = "新增产品说明".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // 上传与原文件同名的 one.md，并断言建库成功。
        assertTrue(service.upload("one.md", content).ready());
        // 断言原始文件和本次上传各形成一条分块。
        assertEquals(2, service.status().chunkCount());
        // 断言根目录旧文件正文完全保留。
        assertEquals("原有知识", Files.readString(knowledge.resolve("one.md")));
        // 断言两路索引写入的稳定 ID 一致。
        assertEquals(keyword.ids, vector.ids);
        // 再次同名上传后断言新增第三条记录，验证独立上传目录策略。
        assertEquals(3, service.upload("one.md", content).chunkCount());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证非法上传在写入前就被拒绝，不破坏已有就绪索引。
    void invalidUploadDoesNotChangeReadyIndex() throws Exception {
        // 创建包含已有知识的目录。
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        // 保存可成功重建的旧正文。
        Files.writeString(knowledge.resolve("one.md"), "原有知识");
        // 使用正常的内存替身创建入库服务。
        KnowledgeIngestionService service = service(knowledge, new FakeWritableKeyword(), new FakeWritableVector());
        // 先发布可用索引，作为验证非法输入无副作用的基准。
        service.rebuild();
        // 遍历路径穿越、路径分隔符、不支持格式和控制字符等非法名称。
        for (String name : List.of("../escape.pdf", "a/b.md", "a\\b.md", "file.txt", "bad\n.pdf")) {
            // 断言每个非法名称均在上传验证阶段抛出参数异常。
            assertThrows(IllegalArgumentException.class, () -> service.upload(name, new byte[]{65}));
        }
        // 断言零字节正文被拒绝。
        assertThrows(IllegalArgumentException.class, () -> service.upload("a.pdf", new byte[0]));
        // 断言非法 UTF-8 字节序列被拒绝。
        assertThrows(IllegalArgumentException.class, () -> service.upload("a.md", new byte[]{(byte) 0xff}));
        // 断言超过 5 MiB 的上传被拒绝。
        assertThrows(IllegalArgumentException.class, () -> service.upload("a.pdf", new byte[5 * 1024 * 1024 + 1]));
        for (byte[] pdf : List.of(new byte[]{65}, "%PDF-1.7\ninvalid".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                PdfTestDocuments.text(""), PdfTestDocuments.encrypted("secret"), PdfTestDocuments.encrypted(""))) {
            assertThrows(IllegalArgumentException.class, () -> service.upload("invalid.pdf", pdf));
        }
        // 断言所有非法输入之后旧索引仍然就绪。
        assertTrue(service.isReady());
        // 断言原来的一条知识没有被添加或删除。
        assertEquals(1, service.status().chunkCount());
        // 断言验证失败没有创建 uploads 目录。
        assertFalse(Files.exists(knowledge.resolve("uploads")));
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证保存上传文件后建库失败，可以直接重建重试而不重复上传。
    void failedUploadCanBeRetriedWithoutReuploading() throws Exception {
        // 让知识根目录初始不存在，覆盖上传时自动创建目录的情况。
        Path knowledge = tempDir.resolve("knowledge");
        // 准备可控制写入故障的向量替身。
        FakeWritableVector vector = new FakeWritableVector();
        // 开启向量写入失败。
        vector.failUpsert = true;
        // 使用正常关键词替身和故障向量替身构造服务。
        KnowledgeIngestionService service = service(knowledge, new FakeWritableKeyword(), vector);
        // 断言首次上传后的建库失败，但上传正文已保存到知识目录。
        assertThrows(IllegalStateException.class, () -> service.upload("new.pdf", PdfTestDocuments.text("New product details")));
        // 断言失败期间知识库不可查询。
        assertFalse(service.isReady());
        // 关闭模拟故障，代表远程向量服务恢复。
        vector.failUpsert = false;
        // 直接执行 rebuild 并断言能从已保存上传文件重建一条分块。
        assertEquals(1, service.rebuild().chunkCount());
        // 断言重试成功后知识库恢复就绪。
        assertTrue(service.isReady());
    }

    @Test
    void pdfUploadAndRebuildPreserveOriginalFileAndExtractBothPages() throws Exception {
        Path knowledge = Files.createDirectories(tempDir.resolve("knowledge"));
        Files.writeString(knowledge.resolve("existing.md"), "现有资料");
        Files.writeString(knowledge.resolve("ignored.txt"), "不再摄取的旧 TXT");
        var keyword = new FakeWritableKeyword();
        var vector = new FakeWritableVector();
        var embedded = new ArrayList<String>();
        var service = new KnowledgeIngestionService(new DocumentChunker(knowledge, 100, 10), keyword, vector,
                texts -> { embedded.clear(); embedded.addAll(texts); return texts.stream().map(t -> new float[]{1, 2}).toList(); },
                knowledge, tempDir.resolve("index"), 2);
        byte[] pdf = PdfTestDocuments.text("Product warranty is two years.", "Contact support for repairs.");

        var uploaded = service.upload("product.PDF", pdf);
        assertTrue(uploaded.ready());
        assertTrue(String.join("\n", embedded).contains("Product warranty is two years."));
        assertTrue(String.join("\n", embedded).contains("Contact support for repairs."));
        assertTrue(embedded.contains("现有资料"));
        assertFalse(String.join("\n", embedded).contains("旧 TXT"));
        assertEquals(keyword.ids, vector.ids);
        Path saved;
        try (var paths = Files.walk(knowledge.resolve("uploads"))) {
            saved = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        assertArrayEquals(pdf, Files.readAllBytes(saved));
        var chunks = new DocumentChunker(knowledge, 100, 10).split(saved);
        assertTrue(chunks.stream().allMatch(c -> c.source().endsWith("/product.PDF")));
        var ids = Set.copyOf(keyword.ids);
        assertEquals(uploaded.chunkCount(), service.rebuild().chunkCount());
        assertEquals(ids, keyword.ids);
        assertEquals(keyword.ids, vector.ids);
    }

    // 统一创建具备相同分块、向量维度和清单目录配置的测试服务。
    private KnowledgeIngestionService service(Path knowledge, FakeWritableKeyword keyword, FakeWritableVector vector) {
        // 注入固定分块策略及调用方提供的两路索引替身。
        return new KnowledgeIngestionService(new DocumentChunker(knowledge, 100, 10), keyword, vector,
                // 为每段文本生成二维固定向量，并把清单保存到临时 index 目录。
                texts -> texts.stream().map(t -> new float[]{1, 2}).toList(), knowledge, tempDir.resolve("index"), 2);
    }

    // 用内存 ID 集合模拟可写关键词索引，只关注入库一致性。
    private static final class FakeWritableKeyword implements WritableKeywordIndex {
        // 保存唯一分块 ID 以及可调整的记录数量。
        Set<String> ids = new LinkedHashSet<>(); long count;
        // 模拟清空索引，同时重置 ID 和计数。
        public void reset() { ids.clear(); count = 0; }
        // 模拟幂等写入：对 ID 去重后更新实际记录数量。
        public void upsert(List<KnowledgeChunk> chunks) { chunks.forEach(c -> ids.add(c.chunkId())); count = ids.size(); }
        // 本类用于入库验证，不执行召回，因此固定返回空命中。
        public List<SearchHit> search(String query, int topK) { return List.of(); }
        // 返回当前模拟记录数，供清单与双索引一致性检查。
        public long count() { return count; }
    }

    // 用内存集合模拟向量索引，并提供可开关的 upsert 失败。
    private static final class FakeWritableVector implements WritableVectorChunkStore {
        // 保存分块 ID、实际计数和故障开关。
        Set<String> ids = new LinkedHashSet<>(); long count; boolean failUpsert;
        // 模拟按维度重建空向量索引，清空 ID 与计数。
        public void reset(int dimension) { ids.clear(); count = 0; }
        // 模拟向量批量入库，按分块 ID 维护唯一记录。
        public void upsert(List<KnowledgeChunk> chunks, List<float[]> vectors) {
            // 开启故障时直接抛错，模拟真实 Milvus 连接失败。
            if (failUpsert) throw new IllegalStateException("Milvus unavailable");
            // 正常状态下保存唯一 ID，并同步更新记录数。
            chunks.forEach(c -> ids.add(c.chunkId())); count = ids.size();
        }
        // 内存替身没有刷盘和加载过程，publish 不需要额外操作。
        public void publish() {}
        // 本替身只测试建库，不返回向量召回命中。
        public List<SearchHit> search(float[] vector, int topK) { return List.of(); }
        // 返回实际或测试手工设置的向量记录数。
        public long count() { return count; }
    }
}
