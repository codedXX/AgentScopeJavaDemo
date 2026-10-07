// 请求数据对象放在 model 包中，供 Web 层与 Agent 层共同使用。
package com.example.salesagent.model;
// 引入参数校验注解，约束会话标识与用户输入的格式和长度。
import jakarta.validation.constraints.*;
import java.util.List;
// 使用不可变 record 表示一次问答请求。
public record ChatRequest(
        // 会话 ID 仅允许 1～64 个英文字母、数字或短横线；可为空以创建新会话。
        @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String sessionId,
        // 最多 2000 字；有图片时允许正文为空。
        @Size(max = 2000) String message,
        @Size(max = 4) List<@NotNull @Pattern(regexp = "[a-f0-9-]{36}") String> imageIds) {
    public ChatRequest {
        message = message == null ? "" : message.strip();
        imageIds = imageIds == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(imageIds));
    }

    public ChatRequest(String sessionId, String message) {
        this(sessionId, message, List.of());
    }

    @AssertTrue(message = "请输入文字或选择图片；图片必须属于当前会话")
    public boolean isContentValid() {
        return (!message.isBlank() || !imageIds.isEmpty())
                && (imageIds.isEmpty() || sessionId != null);
    }
}
