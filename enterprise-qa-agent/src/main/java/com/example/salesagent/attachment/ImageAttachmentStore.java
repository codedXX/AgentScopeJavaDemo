package com.example.salesagent.attachment;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 图片与会话隔离保存；附件 ID 仅用于定位，模型请求携带真实图片内容。 */
public final class ImageAttachmentStore {
    public static final int MAX_IMAGES = 4;
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;
    private final Path root;

    public ImageAttachmentStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public record Uploaded(String id, String url, String contentType, long size) {}
    public record Stored(byte[] bytes, String contentType) {
        public ImageBlock block() {
            return ImageBlock.builder().source(Base64Source.builder().mediaType(contentType)
                    .data(Base64.getEncoder().encodeToString(bytes)).build()).build();
        }
    }

    public Uploaded upload(String sessionId, byte[] bytes) {
        Path dir = directory(sessionId);
        String type = inspect(bytes);
        String id = UUID.randomUUID().toString();
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(id), bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败", e);
        }
        return new Uploaded(id, "/api/sessions/" + sessionId + "/images/" + id, type, bytes.length);
    }

    public Stored load(String sessionId, String id) {
        Path dir = directory(sessionId);
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw badRequest("图片附件 ID 不合法");
        Path file = dir.resolve(id);
        try {
            if (!Files.isRegularFile(file))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在或不属于当前会话，请重新上传");
            if (Files.size(file) > MAX_BYTES) throw badRequest("图片不能超过 5 MB");
            byte[] bytes = Files.readAllBytes(file);
            // 已入库的图片通过过完整解码校验，读取时只需识别已验证的格式。
            String type = bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                    ? "image/jpeg" : "image/png";
            return new Stored(bytes, type);
        } catch (IOException e) {
            throw new IllegalStateException("图片读取失败", e);
        }
    }

    public List<ImageBlock> blocks(String sessionId, List<String> ids) {
        if (ids.size() > MAX_IMAGES) throw badRequest("每次最多发送 4 张图片");
        return ids.stream().map(id -> load(sessionId, id).block()).toList();
    }

    private Path directory(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9-]{1,64}"))
            throw badRequest("会话 ID 不合法");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8)));
            return root.resolve(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String inspect(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw badRequest("请选择非空且不超过 5 MB 的图片");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw badRequest("图片损坏或格式不支持，请上传 PNG/JPEG 图片");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg"))
                    throw badRequest("目前支持 PNG/JPEG 图片");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS)
                    throw badRequest("图片像素过大，请缩小到 1600 万像素以内");
                if (reader.read(0) == null) throw badRequest("图片无法解码，请重新选择");
                return format.equals("jpeg") ? "image/jpeg" : "image/png";
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw badRequest("图片损坏，请重新选择 PNG/JPEG 图片");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
