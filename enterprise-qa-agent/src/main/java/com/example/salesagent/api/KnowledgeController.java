// 将知识库维护 HTTP 接口归入 API 包。
package com.example.salesagent.api;

// 导入上传文档、重建索引和读取构建状态的服务。
import com.example.salesagent.rag.KnowledgeIngestionService;
// 导入环境注解，限定索引维护入口所在进程。
import org.springframework.context.annotation.Profile;
// 导入上传参数、HTTP 路由和 REST 响应注解。
import org.springframework.web.bind.annotation.*;

// 让方法结果直接成为 JSON 响应正文。
@RestController
// 只让 app 进程写入知识索引。
@Profile("app")
// 暴露知识库重建、文档上传和就绪状态接口。
public class KnowledgeController {
    // 保存知识摄取服务，由它统一控制分块和索引写入。
    private final KnowledgeIngestionService ingestion;

    // 通过构造器注入知识摄取服务。
    public KnowledgeController(KnowledgeIngestionService ingestion) {
        // 将服务引用保存到不可变字段。
        this.ingestion = ingestion;
    }

    // POST 触发知识库全量重建，避免 GET 请求产生索引写入。
    @PostMapping("/api/knowledge/rebuild")
    // 返回服务层生成的重建状态对象。
    public Object rebuild() {
        // 重新摄取已配置的文档，并交由服务报告完成状态。
        return ingestion.rebuild();
    }

    // 限定请求必须采用 multipart/form-data，以接收文件内容。
    @PostMapping(value = "/api/knowledge/upload", consumes = "multipart/form-data")
    // 绑定名为 file 的表单文件；读取文件字节可能抛出 IOException。
    public Object upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        // 将文件校验错误转换成保留具体原因的 HTTP 响应。
        try {
            // 把原始文件名和完整字节交给知识摄取服务校验、分块和索引。
            return ingestion.upload(file.getOriginalFilename(), file.getBytes());
        // 捕获服务层的文件名、编码或内容校验失败。
        } catch (IllegalArgumentException ex) {
            // 保留错误说明，便于上传页面向用户展示具体修复方式。
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    // GET 只读取当前索引状态，不触发重建。
    @GetMapping("/api/knowledge/status")
    // 输出是否可检索、分块数量和最近构建错误等状态。
    public Object status() {
        // 直接返回知识摄取服务维护的最新状态。
        return ingestion.status();
    }
}
