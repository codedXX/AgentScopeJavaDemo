package com.example.salesagent.api;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.example.salesagent.rag.KnowledgeNotReadyException.class)
    public ResponseEntity<?> notReady(Exception ex) { return ResponseEntity.status(503).body(Map.of("error", ex.getMessage())); }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> httpStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", ex.getReason() == null ? "请求失败" : ex.getReason()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception ex) { return ResponseEntity.badRequest().body(Map.of("error", "请求参数不合法，请检查问题长度、会话ID或工具参数")); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> unavailable(IllegalStateException ex) {
        String message = ex.getMessage() == null ? "服务暂不可用" : ex.getMessage();
        int code = message.contains("正在") || message.contains("上限") ? 409 : 503;
        return ResponseEntity.status(code).body(Map.of("error", message));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> oversized(Exception ex) {
        return ResponseEntity.status(413).body(Map.of("error", "文件过大，请上传不超过 5 MB 的文件"));
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<?> missingFile(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "请选择需要上传的文件"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failure(Exception ex) { return ResponseEntity.status(502).body(Map.of("error", "上游服务调用失败，请检查模型权限、服务连接与配置")); }
}
