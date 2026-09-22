package com.example.salesagent.api;
import com.example.salesagent.rag.KnowledgeIngestionService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("app")
public class KnowledgeController {
    private final KnowledgeIngestionService ingestion;
    public KnowledgeController(KnowledgeIngestionService ingestion) { this.ingestion = ingestion; }
    @PostMapping("/api/knowledge/rebuild") public Object rebuild() { return ingestion.rebuild(); }
    @GetMapping("/api/knowledge/status") public Object status() { return ingestion.status(); }
}
