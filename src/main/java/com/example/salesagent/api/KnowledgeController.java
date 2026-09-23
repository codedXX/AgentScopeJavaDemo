package com.example.salesagent.api;
import com.example.salesagent.rag.KnowledgeIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** 提供知识文件上传、重建和状态查询接口。 */
@RestController @Profile("app")
public class KnowledgeController {
    @Autowired private KnowledgeIngestionService ingestion;
    /** 用现有资料重建知识索引。 */
    @PostMapping("/api/knowledge/rebuild") public Object rebuild() { return ingestion.rebuild(); }
    /** 接收上传文件，交给入库服务校验并重建。 */
    @PostMapping(value = "/api/knowledge/upload", consumes = "multipart/form-data")
    public Object upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        try {
            return ingestion.upload(file.getOriginalFilename(), file.getBytes());
        } catch (IllegalArgumentException ex) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
    /** 读取当前知识库状态。 */
    @GetMapping("/api/knowledge/status") public Object status() { return ingestion.status(); }
}
