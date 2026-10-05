// 将 HTTP 对话入口放在 API 包，便于 Spring 扫描控制器。
package com.example.salesagent.api;

// 导入负责意图判断、检索和工具调用的销售问答服务。
import com.example.salesagent.agent.SalesAssistant;
// 导入对话请求、响应等传输对象。
import com.example.salesagent.model.*;
// 导入 Jakarta 参数校验注解，让请求对象上的约束生效。
import jakarta.validation.Valid;
// 导入环境限定注解，只在问答应用进程启用此入口。
import org.springframework.context.annotation.Profile;
// 导入 REST 控制器、请求体绑定和路由注解。
import org.springframework.web.bind.annotation.*;

// 将方法返回值序列化为 HTTP 响应正文。
@RestController
// app 环境负责对外提供问答 API。
@Profile("app")
// 暴露聊天接口，并把业务执行交给 SalesAssistant。
public class ChatController {
    // 保存构造器注入的问答服务，控制器不自行创建 Agent。
    private final SalesAssistant assistant;

    // 通过构造器接收 Spring 管理的问答服务。
    public ChatController(SalesAssistant assistant) {
        // 记录服务引用，供每次请求复用。
        this.assistant = assistant;
    }

    // 使用 POST 承载包含问题和会话 ID 的 JSON 请求。
    @PostMapping("/api/chat")
    // 将 JSON 反序列化为 ChatRequest，并在进入方法前校验字段约束。
    public ChatResponse chat(@RequestBody @Valid ChatRequest request) {
        // 返回问答服务生成的回答、来源、执行步骤和耗时。
        return assistant.chat(request);
    }
}
