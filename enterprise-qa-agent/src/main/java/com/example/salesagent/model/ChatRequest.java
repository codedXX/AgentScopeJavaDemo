// 请求数据对象放在 model 包中，供 Web 层与 Agent 层共同使用。
package com.example.salesagent.model;
// 引入参数校验注解，约束会话标识与用户输入的格式和长度。
import jakarta.validation.constraints.*;
// 使用不可变 record 表示一次问答请求。
public record ChatRequest(
        // 会话 ID 仅允许 1～64 个英文字母、数字或短横线；可为空以创建新会话。
        @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String sessionId,
        // 用户消息必须含非空白字符，且最多 2000 个字符。
        @NotBlank @Size(max = 2000) String message) {}
