// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 Files，用于文件读取、写入和目录操作。
import java.nio.file.Files;
// 引入 Path，用于安全组合与规范化文件路径。
import java.nio.file.Path;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;
// 引入 TempDir，用于由 JUnit 自动隔离和清理测试临时目录。
import org.junit.jupiter.api.io.TempDir;

// 验证中文文档切分的长度、引用来源、空白处理与稳定分块 ID。
class DocumentChunkerTest {
    // 让 JUnit 为每项测试提供隔离的临时目录。
    @TempDir Path tempDir;

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证长中文文档被切成多个短块，并保留规范化相对来源路径。
    void longChineseDocumentIsSplitAndKeepsRelativeSource() throws Exception {
        // 在临时目录中建立嵌套产品知识目录。
        Path nested = Files.createDirectories(tempDir.resolve("product"));
        // 指定嵌套目录中的 Markdown 测试文件。
        Path file = nested.resolve("demo.md");
        // 写入带标题和重复中文正文的长文档，保证触发多块切分。
        Files.writeString(file, "# 产品说明\n\n" + "这是用于演示的中文产品资料。".repeat(80));

        // 设置子块上限 120 字符、重叠 20 字符，执行文件切分。
        List<KnowledgeChunk> chunks = new DocumentChunker(tempDir, 120, 20).split(file);

        // 断言长文档确实产生了多条知识子块。
        assertTrue(chunks.size() > 1);
        // 断言每个子块都满足最大 120 字符限制。
        assertTrue(chunks.stream().allMatch(chunk -> chunk.text().length() <= 120));
        // 断言所有来源都使用相对路径 product/demo.md，而不是绝对路径。
        assertTrue(chunks.stream().allMatch(chunk -> chunk.source().equals("product/demo.md")));
        // 断言第一个子块序号从零开始。
        assertEquals(0, chunks.getFirst().ordinal());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证只含空白符的文件不会进入知识索引。
    void blankDocumentProducesNoChunks() throws Exception {
        // 准备空白 Markdown 文件路径。
        Path file = tempDir.resolve("blank.md");
        // 写入空格、Windows 换行和制表符，覆盖清洗后的空白场景。
        Files.writeString(file, " \r\n\t\n ");

        // 断言清洗后没有正文，因此返回空分块列表。
        assertTrue(new DocumentChunker(tempDir, 100, 10).split(file).isEmpty());
    }

    // 标记下一个方法为 JUnit 测试，由测试运行器独立执行。
    @Test
    // 验证分块 ID 可重复，并且不同来源的相同正文不会碰撞。
    void chunkIdsAreStableButIncludeSource() throws Exception {
        // 准备第一份知识文件的来源路径。
        Path first = tempDir.resolve("a.md");
        // 准备第二份内容相同但来源不同的文件路径。
        Path second = tempDir.resolve("b.md");
        // 向第一份文件写入测试正文。
        Files.writeString(first, "相同的演示内容");
        // 向第二份文件写入完全相同的正文。
        Files.writeString(second, "相同的演示内容");
        // 复用同一基础分块器，避免配置差异影响 ID 对比。
        DocumentChunker chunker = new DocumentChunker(tempDir, 100, 10);

        // 取得第一次处理第一份文件的分块。
        List<KnowledgeChunk> one = chunker.split(first);
        // 再次处理同一文件，验证稳定性。
        List<KnowledgeChunk> repeated = chunker.split(first);
        // 处理另一来源的相同正文。
        List<KnowledgeChunk> otherSource = chunker.split(second);

        // 断言同一来源重复切分生成相同 ID。
        assertEquals(one.getFirst().chunkId(), repeated.getFirst().chunkId());
        // 断言来源变化后 ID 随之变化，防止跨文件误去重。
        assertNotEquals(one.getFirst().chunkId(), otherSource.getFirst().chunkId());
    }
}
