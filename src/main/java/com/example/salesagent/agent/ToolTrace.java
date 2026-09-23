package com.example.salesagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/**
 * 每轮问答独立的工具调用记录器。只收集可观察的工具名称、来源和失败原因，供回答校验及前端步骤展示；
 * 不收集或返回模型的内部思考内容。
 */
final class ToolTrace implements Hook {
    private final ObjectMapper mapper;
    final List<String> steps = new CopyOnWriteArrayList<>();
    final Set<String> sources = Collections.synchronizedSet(new LinkedHashSet<>());
    final List<String> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    private final Set<String> tools = Set.of("listRepositoryFiles", "readRepositoryFile", "getProductStatus");
    ToolTrace(ObjectMapper mapper) { this.mapper = mapper; }
    void reset() { steps.clear(); sources.clear(); failures.clear(); calls.set(0); }
    /**
     * 在 PreActingEvent 中限制本轮最多五次指定工具调用，并将无参数的文件树工具规范为空对象。
     * 在 PostActingEvent 中解析工具文本；只有 JSON 包含非空 source 才算成功，其余结果记录安全的失败提示。
     */
    @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent) {
            PreActingEvent pre = (PreActingEvent) event;
            if (tools.contains(pre.getToolUse().getName())) {
                if (calls.incrementAndGet() > 5) return Mono.error(new IllegalStateException("本轮工具调用超过5次上限"));
                // 固定仓库的文件树工具无参数。模型可能省略 arguments 或带入仓库名，
                // 在 SDK 的 JSON Schema 校验前统一为合法空对象；其他工具仍严格校验。
                if ("listRepositoryFiles".equals(pre.getToolUse().getName())) {
                    ToolUseBlock original = pre.getToolUse();
                    pre.setToolUse(ToolUseBlock.builder().id(original.getId()).name(original.getName())
                            .input(Map.of()).content("{}").build());
                }
                steps.add("调用工具：" + pre.getToolUse().getName());
            }
        }
        if (event instanceof PostActingEvent) {
            PostActingEvent post = (PostActingEvent) event;
            if (tools.contains(post.getToolUse().getName())) {
                for (Object block : post.getToolResult().getOutput()) {
                    if (block instanceof TextBlock) {
                        TextBlock text = (TextBlock) block;
                        recordResult(post.getToolUse().getName(), text.getText());
                    }
                }
            }
        }
        return Mono.just(event);
    }
    void recordResult(String tool, String output) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(output);
            if (node != null && node.path("source").isTextual() && !node.path("source").asText().isBlank()) {
                sources.add(node.path("source").asText());
                steps.add("工具成功：" + tool);
                return;
            }
        } catch (Exception ignored) { /* 工具失败通常返回普通文本，下面给出安全的诊断信息。 */ }
        String reason = failureReason(output);
        failures.add(tool + "：" + reason);
        steps.add("工具失败：" + tool + "；" + reason);
    }

    // 不把任意上游异常原文回传浏览器，避免暴露认证信息或响应内容。
    private static String failureReason(String output) {
        String text = output == null ? "" : output;
        java.util.regex.Matcher http = java.util.regex.Pattern.compile("GitHub HTTP (\\d{3})").matcher(text);
        if (http.find()) {
            String reason;
            switch (http.group(1)) {
                case "401":
                    reason = "GitHub 认证失败（401），请检查 MCP 服务的 GITHUB_TOKEN";
                    break;
                case "403":
                case "429":
                    reason = "GitHub 拒绝访问或触发限流（" + http.group(1) + "），请检查权限与额度后重试";
                    break;
                case "404":
                    reason = "GitHub 仓库或文件不存在，或当前账号无权访问（404）";
                    break;
                default:
                    reason = "GitHub 请求失败（HTTP " + http.group(1) + "），请稍后重试";
                    break;
            }
            return reason;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("parameter validation failed") || lower.contains("schema validation error"))
            return "工具参数格式校验失败，请检查模型返回的工具参数 JSON（请求尚未发送到 MCP）";
        if (lower.contains("tool not found"))
            return "工具未注册，请新建对话后重试";
        if (lower.contains("timeout") || lower.contains("timed out") || text.contains("超时"))
            return "工具调用超时，请检查 MCP 服务访问 GitHub 的网络后重试";
        if (text.contains("GitHub 网络或响应异常"))
            return "MCP 服务访问 GitHub 时发生网络或响应异常，请检查网络后重试";
        if (text.contains("commitSha") || text.contains("缺少工具参数") || text.contains("仅允许仓库"))
            return "仓库工具参数不合法，请先获取文件树，再使用返回的文件路径和 commitSha";
        return "工具返回错误或无有效来源，请查看 MCP 服务及问答服务控制台日志";
    }

}
