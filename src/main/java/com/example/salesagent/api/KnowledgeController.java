package com.example.salesagent.api;
import com.example.salesagent.rag.KnowledgeIngestionService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("app")
public class KnowledgeController {
    private final KnowledgeIngestionService ingestion;
    public KnowledgeController(KnowledgeIngestionService ingestion) { this.ingestion = ingestion; }
    @PostMapping("/api/knowledge/rebuild") public Object rebuild() { return ingestion.rebuild(); }
    @PostMapping(value = "/api/knowledge/upload", consumes = "multipart/form-data")
    public Object upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        try {
            return ingestion.upload(file.getOriginalFilename(), file.getBytes());
        } catch (IllegalArgumentException ex) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
    @GetMapping("/api/knowledge/status") public Object status() { return ingestion.status(); }
}
