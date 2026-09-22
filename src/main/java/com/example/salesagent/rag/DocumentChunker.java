package com.example.salesagent.rag;

import com.example.salesagent.model.KnowledgeChunk;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 清洗 UTF-8 文档，并委托 LangChain4j 的字符递归切分器处理中文文本。 */
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
        var segments = splitter.split(Document.from(text));
        for (int i = 0; i < segments.size(); i++) {
            String chunkText = segments.get(i).text();
            result.add(new KnowledgeChunk(hash(source, i, chunkText), chunkText, source, i));
        }
        return List.copyOf(result);
    }

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
