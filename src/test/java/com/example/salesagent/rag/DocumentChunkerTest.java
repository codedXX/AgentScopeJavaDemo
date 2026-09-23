package com.example.salesagent.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.example.salesagent.model.KnowledgeChunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentChunkerTest {
    @TempDir Path tempDir;

    @Test
    void longChineseDocumentIsSplitAndKeepsRelativeSource() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("product"));
        Path file = nested.resolve("demo.md");
        Files.writeString(file, "# 产品说明\n\n" + "这是用于演示的中文产品资料。".repeat(80));

        List<KnowledgeChunk> chunks = new DocumentChunker(tempDir, 120, 20).split(file);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getText().length() <= 120));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getSource().equals("product/demo.md")));
        assertEquals(0, chunks.getFirst().getOrdinal());
    }

    @Test
    void blankDocumentProducesNoChunks() throws Exception {
        Path file = tempDir.resolve("blank.txt");
        Files.writeString(file, " \r\n\t\n ");

        assertTrue(new DocumentChunker(tempDir, 100, 10).split(file).isEmpty());
    }

    @Test
    void chunkIdsAreStableButIncludeSource() throws Exception {
        Path first = tempDir.resolve("a.txt");
        Path second = tempDir.resolve("b.txt");
        Files.writeString(first, "相同的演示内容");
        Files.writeString(second, "相同的演示内容");
        DocumentChunker chunker = new DocumentChunker(tempDir, 100, 10);

        List<KnowledgeChunk> one = chunker.split(first);
        List<KnowledgeChunk> repeated = chunker.split(first);
        List<KnowledgeChunk> otherSource = chunker.split(second);

        assertEquals(one.getFirst().getChunkId(), repeated.getFirst().getChunkId());
        assertNotEquals(one.getFirst().getChunkId(), otherSource.getFirst().getChunkId());
    }
}
