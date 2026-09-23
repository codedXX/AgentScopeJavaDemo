package com.example.salesagent.api;
import com.example.salesagent.agent.SalesAssistant;
import com.example.salesagent.history.ChatHistoryStore;
import com.example.salesagent.model.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/**
 * 问答与历史记录的 HTTP 入口。POST 提交问题，GET 列出会话或读取某个会话的完整轮次。
 * 模型执行和持久化由 SalesAssistant 与 ChatHistoryStore 负责。
 */
@RestController @Profile("app")
public class ChatController {
    @Autowired private SalesAssistant assistant;
    @Autowired private ChatHistoryStore history;
    /** 接收问题并返回本轮回答。 */
    @PostMapping("/api/chat") public ChatResponse chat(@RequestBody @Valid ChatRequest request) { return assistant.chat(request); }
    /** 列出已保存的会话。 */
    @GetMapping("/api/sessions") public java.util.List<com.example.salesagent.history.ChatSession> sessions() {
        return history.listSessions();
    }
    /** 先校验路径中的会话 ID，再区分非法 ID 和数据库中不存在的会话。 */
    @GetMapping("/api/sessions/{id}/turns") public java.util.List<com.example.salesagent.history.ChatTurn> turns(@PathVariable String id) {
        if (!id.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("会话 ID 不合法");
        if (!history.exists(id)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在");
        return history.turns(id);
    }
}
