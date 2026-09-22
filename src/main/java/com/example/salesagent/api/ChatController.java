package com.example.salesagent.api;
import com.example.salesagent.agent.SalesAssistant;
import com.example.salesagent.model.*;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("app")
public class ChatController {
    private final SalesAssistant assistant;
    public ChatController(SalesAssistant assistant) { this.assistant = assistant; }
    @PostMapping("/api/chat") public ChatResponse chat(@RequestBody @Valid ChatRequest request) { return assistant.chat(request); }
}
