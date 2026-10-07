package com.example.salesagent.rag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** 上传校验与重建共用的 PDF / Markdown 正文读取规则。 */
final class KnowledgeDocumentReader {
    private KnowledgeDocumentReader() {}

    static boolean supports(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).matches(".+\\.(pdf|md)");
    }

    static String read(Path file) throws IOException {
        return read(file.getFileName().toString(), Files.readAllBytes(file));
    }

    static String read(String filename, byte[] content) {
        if (!supports(filename)) throw new IllegalArgumentException("仅支持 PDF、Markdown 文件");
        if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) return readPdf(content);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
            if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("请上传包含正文的 UTF-8 Markdown 文件");
            return text.replace("\uFEFF", "");
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Markdown 文件必须使用 UTF-8 编码", e);
        }
    }

    private static String readPdf(byte[] content) {
        if (content.length < 5 || !new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new IllegalArgumentException("PDF 文件无效或已损坏，请上传有效的 PDF 文档");
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) throw new IllegalArgumentException("暂不支持加密 PDF，请先解除密码保护后上传");
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new IllegalArgumentException("PDF 不允许提取正文，请上传允许文本提取的文档");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document).replace("\uFEFF", "");
            if (text.isBlank()) {
                throw new IllegalArgumentException("PDF 没有可提取的文字，扫描版或图片 PDF 请先进行 OCR 识别后上传");
            }
            return text;
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException("暂不支持加密 PDF，请先解除密码保护后上传", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF 文件无效或已损坏，请上传有效的 PDF 文档", e);
        }
    }
}
