package com.example.salesagent.rag;

import com.example.salesagent.model.KnowledgeChunk;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 将知识目录内的 UTF-8 文件转换为可检索分块。统一换行和空白字符后，使用 LangChain4j 递归切分器
 * 按配置的大小与重叠量拆分；每个分块保留相对路径和在文档中的顺序，供最终回答追溯来源。
 */
public final class DocumentChunker {
    private final Path knowledgeRoot;
    private final DocumentSplitter splitter;

    public DocumentChunker(Path knowledgeRoot, int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkSize 必须大于 overlap，且两者不能为负数");
        }
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        this.splitter = DocumentSplitters.recursive(chunkSize, overlap);
    }

    /** 读取文件并切成带来源信息的片段。 */
    public List<KnowledgeChunk> split(Path file) {
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(knowledgeRoot)) {
            throw new IllegalArgumentException("知识文件必须位于知识目录内: " + file);
        }
        String source = knowledgeRoot.relativize(normalizedFile).toString().replace('\\', '/');
        String text;
        try {
            text = java.nio.file.Files.readString(normalizedFile, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n')
                    .replaceAll("[\\t\\x0B\\f ]+", " ")
                    .replaceAll(" *\\n *", "\n").trim();
        } catch (IOException e) {
            throw new IllegalStateException("读取知识文件失败: " + source, e);
        }
        if (text.isBlank()) return List.of();

        List<KnowledgeChunk> result = new ArrayList<>();
        List<TextSegment> segments = splitter.split(Document.from(text));
        for (int i = 0; i < segments.size(); i++) {
            String chunkText = segments.get(i).text();
            result.add(new KnowledgeChunk(hash(source, i, chunkText), chunkText, source, i));
        }
        return List.copyOf(result);
    }

    /**
     * 对相对路径、分块序号和正文计算 SHA-256。相同文件再次重建得到稳定 ID；
     * 文件内容或切分位置变化时产生新 ID，便于两路索引按同一键去重。
     */
    private static String hash(String source, int ordinal, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((source + "\n" + ordinal + "\n" + text)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}