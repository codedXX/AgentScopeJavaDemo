// 将会话查询入口归入 API 包。
package com.example.salesagent.api;

// 导入能够从磁盘恢复会话的持久化存储。
import com.example.salesagent.agent.PersistentConversationStore;
// 导入环境注解，避免 MCP 工具进程重复提供会话 API。
import org.springframework.context.annotation.Profile;
// 导入 REST 路由和路径变量绑定注解。
import org.springframework.web.bind.annotation.*;

// 将查询结果自动输出为 JSON。
@RestController
// 只在主问答应用环境中注册控制器。
@Profile("app")
// 提供按 ID 读取会话历史的接口。
public class SessionController {
    // 保存会话存储依赖，读取逻辑由存储层统一处理。
    private final PersistentConversationStore store;

    // 由 Spring 注入已配置的持久化会话存储。
    public SessionController(PersistentConversationStore store) {
        // 保留存储引用以处理后续历史查询。
        this.store = store;
    }

    // GET 路由中的 id 用于定位需要读取的会话。
    @GetMapping("/api/sessions/{id}")
    // 将路径段绑定为会话 ID，并返回完整会话对象。
    public PersistentConversationStore.Conversation history(@PathVariable String id) {
        // 只接受长度 1～80 的字母、数字、下划线和短横线，限制文件定位输入。
        if (!id.matches("[A-Za-z0-9_-]{1,80}"))
            // 参数错误交给全局异常处理器转换成 HTTP 400。
            throw new IllegalArgumentException("会话ID不合法");
        // 由存储层加载此会话的历史消息和摘要。
        return store.load(id);
    }
}
