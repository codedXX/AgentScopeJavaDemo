// 将跨控制器的 HTTP 异常映射归入 API 包。
package com.example.salesagent.api;

// 导入键值映射，为错误响应构造统一的 error 字段。
import java.util.Map;
// 导入响应实体，以显式设置 HTTP 状态码。
import org.springframework.http.ResponseEntity;
// 导入请求对象字段校验失败时的异常类型。
import org.springframework.web.bind.MethodArgumentNotValidException;
// 导入全局控制器增强及异常处理注解。
import org.springframework.web.bind.annotation.*;

// 全局捕获 REST 控制器异常，并把返回值序列化为 JSON。
@RestControllerAdvice
// 将领域错误、参数错误和上游故障转换成可读的 HTTP 响应。
public class ApiExceptionHandler {
    // 知识索引未完成时使用专门的服务不可用响应。
    @ExceptionHandler(com.example.salesagent.rag.KnowledgeNotReadyException.class)
    // 接收索引未就绪异常并读取其说明。
    public ResponseEntity<?> notReady(Exception ex) {
        // HTTP 503 表示知识服务暂未准备好，客户端可稍后重试。
        return ResponseEntity.status(503).body(Map.of("error", ex.getMessage()));
    }

    // 保留控制器显式指定的状态码，例如上传校验的 400 和商品不存在的 404。
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    // 接收携带状态码和可读原因的 Spring 异常。
    public ResponseEntity<?> httpStatus(org.springframework.web.server.ResponseStatusException ex) {
        // 使用异常自己的状态码，未提供原因时给出通用说明。
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", ex.getReason() == null ? "请求失败" : ex.getReason()));
    }

    // 将 Bean 校验失败和业务参数校验失败归为客户端参数错误。
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class,
            // 无法解析 JSON 正文也属于输入不合法。
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    // 不直接回传内部异常细节，而是提供可操作的参数检查说明。
    public ResponseEntity<?> invalid(Exception ex) {
        // 使用 HTTP 400 告知客户端检查问题长度、会话 ID 和工具参数。
        return ResponseEntity.badRequest().body(Map.of("error", "请求参数不合法，请检查问题长度、会话ID或工具参数"));
    }

    // 捕获重建冲突、资源上限及服务不可用等状态错误。
    @ExceptionHandler(IllegalStateException.class)
    // 根据可读错误信息区分冲突和暂时不可用。
    public ResponseEntity<?> unavailable(IllegalStateException ex) {
        // 避免 null 放入 Map.of，并为无说明的状态异常提供默认文本。
        String message = ex.getMessage() == null ? "服务暂不可用" : ex.getMessage();
        // 正在执行或达到上限返回 409，其余状态问题返回 503。
        int code = message.contains("正在") || message.contains("上限") ? 409 : 503;
        // 让前端显示原有业务状态说明。
        return ResponseEntity.status(code).body(Map.of("error", message));
    }

    // 捕获 multipart 文件超过 Spring 上传大小限制的情况。
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    // 输出上传过大时的专用响应。
    public ResponseEntity<?> oversized(Exception ex) {
        // HTTP 413 表示请求体过大，同时说明允许的 5 MB 上限。
        return ResponseEntity.status(413).body(Map.of("error", "文件过大，请上传不超过 5 MB 的文件"));
    }

    // 缺少必需的 file 表单部分时提供上传提示。
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    // 将缺失上传文件映射为客户端错误。
    public ResponseEntity<?> missingFile(Exception ex) {
        // 使用 400 并要求用户先选择文件。
        return ResponseEntity.badRequest().body(Map.of("error", "请选择需要上传的文件"));
    }

    // 为其他未被专门处理的异常提供最终兜底。
    @ExceptionHandler(Exception.class)
    // 隐藏堆栈、认证信息及上游响应细节。
    public ResponseEntity<?> failure(Exception ex) {
        // HTTP 502 表示上游调用失败，并提示检查模型权限、连接和配置。
        return ResponseEntity.status(502).body(Map.of("error", "上游服务调用失败，请检查模型权限、服务连接与配置"));
    }
}
