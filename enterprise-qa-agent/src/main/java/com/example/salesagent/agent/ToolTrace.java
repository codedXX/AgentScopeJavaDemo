// 工具事件记录器位于 agent 包，与当前会话的 Agent 绑定。
package com.example.salesagent.agent;

// ObjectMapper 用于解析工具返回的 JSON 证据。
import com.fasterxml.jackson.databind.ObjectMapper;
// 引入工具执行前后事件与 Hook 接口。
import io.agentscope.core.hook.*;
// TextBlock 表示工具结果中的文本输出。
import io.agentscope.core.message.TextBlock;
// ToolUseBlock 表示模型生成的工具调用请求。
import io.agentscope.core.message.ToolUseBlock;
// 引入有序集合、Map、Locale 等辅助类型。
import java.util.*;
// 写时复制列表支持工具异步回调与结果读取之间的安全访问。
import java.util.concurrent.CopyOnWriteArrayList;
// AtomicInteger 用于线程安全地限制每轮工具调用次数。
import java.util.concurrent.atomic.AtomicInteger;
// Mono 与 AgentScope 的响应式 Hook 契约保持一致。
import reactor.core.publisher.Mono;

/** 记录可观察工具事件，不记录或返回模型的隐式思考过程。每个会话独立。 */
// 包内使用的 Hook 实现，只保存工具执行事件与可引用证据。
final class ToolTrace implements Hook {
    // 解析 JSON 工具响应的共享映射器。
    private final ObjectMapper mapper;
    // 本轮可展示给用户的工具开始、成功或失败步骤。
    final List<String> steps = new CopyOnWriteArrayList<>();
    // 去重且保序的全部有效来源，并用同步包装保护并发修改。
    final Set<String> sources = Collections.synchronizedSet(new LinkedHashSet<>());
    // 实际工具证据文本，用于回答审计与离线忠实度评测。
    final List<String> contexts = new CopyOnWriteArrayList<>();
    // 保留每次成功来源的顺序，计划执行通过下标截取本步骤新增证据。
    final List<String> successfulSources = new CopyOnWriteArrayList<>();
    // 已清理敏感上游内容的失败诊断文本。
    final List<String> failures = new CopyOnWriteArrayList<>();
    // 当前轮已触发的工具调用计数。
    final AtomicInteger calls = new AtomicInteger();
    // 仅跟踪应用已知的仓库、业务、知识、数据和模型工具。
    private final Set<String> tools = Set.of("listRepositoryFiles", "readRepositoryFile", "getProductStatus", "queryBusinessData", "searchKnowledge", "queryDataSource", "generateText", "embedText");
    // 构造会话专属 trace 时注入 JSON 映射器。
    ToolTrace(ObjectMapper mapper) {
        // 保存映射器供 recordResult 解析响应。
        this.mapper = mapper;
    }
    // 新一轮问答开始时清空上轮记录，避免证据与统计串轮。
    void reset() {
        // 删除上轮的可观察步骤。
        steps.clear();
        // 删除上轮的去重来源。
        sources.clear();
        // 删除上轮的失败原因。
        failures.clear();
        // 删除上轮的证据正文。
        contexts.clear();
        // 删除上轮的成功来源顺序记录。
        successfulSources.clear();
        // 将工具调用计数重置为零。
        calls.set(0);
    }
    // 实现 Hook 的泛型事件处理契约。
    @Override
    // 按执行前后事件类型分别处理参数、调用次数和工具证据。
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 工具执行前，仅对已知工具计数、修正固定无参工具并记录事件。
        if (event instanceof PreActingEvent pre && tools.contains(pre.getToolUse().getName())) {
            // 每轮最多十二次工具调用，超出上限则以响应式错误阻止执行。
            if (calls.incrementAndGet() > 12) return Mono.error(new IllegalStateException("本轮工具调用超过12次上限"));
            // 固定仓库的文件树工具无参数。模型可能省略 arguments 或带入仓库名，
            // 在 SDK 的 JSON Schema 校验前统一为合法空对象；其他工具仍严格校验。
            // 只有 listRepositoryFiles 的参数固定归一化为空对象。
            if ("listRepositoryFiles".equals(pre.getToolUse().getName())) {
                // 保留原调用 ID 与名称，确保工具结果能正确关联本次调用。
                var original = pre.getToolUse();
                // 用相同 ID、名称创建合法的新工具调用。
                pre.setToolUse(ToolUseBlock.builder().id(original.getId()).name(original.getName())
                        // 输入 Map 与序列化 content 同时设为空对象，满足 SDK 参数校验。
                        .input(Map.of()).content("{}").build());
            }
            // 向本轮步骤追加可观察的工具名称。
            steps.add("调用工具：" + pre.getToolUse().getName());
        }
        // 工具执行完成后读取已知工具的返回内容。
        if (event instanceof PostActingEvent post && tools.contains(post.getToolUse().getName())) {
            // 仅解析文本块，忽略当前记录器不支持的媒体输出。
            for (var block : post.getToolResult().getOutput()) if (block instanceof TextBlock text) {
                // 按工具名解析来源、证据或经过清理的失败原因。
                recordResult(post.getToolUse().getName(), text.getText());
            }
        }

        // 将当前事件继续传回 Hook 链，不替换工具执行结果。
        return Mono.just(event);
    }
    // 解析一次文本工具结果，只有真实来源能进入最终引用白名单。
    void recordResult(String tool, String output) {
        // 尝试解析受支持的 JSON 工具结构，解析失败进入固定诊断分支。
        try {
            // 尝试按 JSON 解析工具文本；普通错误文本进入后面的诊断路径。
            var node = mapper.readTree(output);
            // 模型生成与向量化结果不是外部事实来源，不允许作为引用证据。
            if ("generateText".equals(tool) || "embedText".equals(tool)) {
                // 仅记录操作完成，提示其输出不属于事实证据。
                steps.add("模型/向量工具完成，输出不作为事实证据："+tool);
                // 结束解析，避免把生成内容误当成有来源的资料。
                return;
            }
            // 检索工具返回证据数组时，逐条提取知识分块来源。
            if (node!=null && "searchKnowledge".equals(tool) && node.path("evidence").isArray()) {
                // 遍历每个命中证据节点。
                for(var hit:node.path("evidence")) {
                    // 从命中分块中读取 source，缺失时 Jackson 返回空字符串。
                    String source=hit.path("chunk").path("source").asText();
                    // 只有非空来源才可登记为有效证据。
                    if(!source.isBlank()) {
                        // 去重记录本轮合法来源。
                        sources.add(source);
                        // 同时保留来源出现顺序，用于区分计划步骤的新增证据。
                        successfulSources.add(source);
                        // 保存对应分块正文，供后续评测与审计。
                        contexts.add(hit.path("chunk").path("text").asText());
                    }
                }
                // 记录知识检索工具已完成处理。
                steps.add("检索工具完成："+tool);
                // 检索结果的特殊结构已解析，不再进入通用 source 路径。
                return;
            }
            // 普通业务或仓库工具必须给出非空文本 source 才视为成功。
            if (node != null && node.path("source").isTextual() && !node.path("source").asText().isBlank()) {
                // 将 source 加入引用白名单。
                sources.add(node.path("source").asText());
                // 记录当前工具产生的成功来源顺序。
                successfulSources.add(node.path("source").asText());
                // 保存完整工具响应作为本轮数据证据。
                contexts.add(output);
                // 追加用户可观察的成功状态。
                steps.add("工具成功：" + tool);
                // 成功响应不再记录失败诊断。
                return;
            }
        // JSON 解析失败时不公开原异常，继续使用受控的失败原因分类。
        } catch (Exception ignored) {
            // 工具失败通常返回普通文本，下面给出安全的诊断信息。
        }
        // 没有取得有效来源时，根据受控特征生成诊断文本。
        String reason = failureReason(output);
        // 保存工具名和清理后的失败原因，供兜底回答使用。
        failures.add(tool + "：" + reason);
        // 将失败步骤加入可观察执行轨迹。
        steps.add("工具失败：" + tool + "；" + reason);
    }

    // 不把任意上游异常原文回传浏览器，避免暴露认证信息或响应内容。
    // 将工具失败响应转换为有限的固定诊断信息。
    private static String failureReason(String output) {
        // null 输出统一转换为空文本，后续匹配不需要特殊分支。
        String text = output == null ? "" : output;
        // 提取 GitHub HTTP 的三位状态码，只公开状态码和固定解释。
        var http = java.util.regex.Pattern.compile("GitHub HTTP (\\d{3})").matcher(text);
        // 找到 GitHub 状态码时返回对应的诊断类别。
        if (http.find()) {
            // 根据已提取的 HTTP 状态码选择提示。
            return switch (http.group(1)) {
                // 401 表示认证未通过，提示检查 MCP 服务凭证。
                case "401" -> "GitHub 认证失败（401），请检查 MCP 服务的 GITHUB_TOKEN";
                // 403 与 429 可能涉及权限或额度，提示稍后按授权状态重试。
                case "403", "429" -> "GitHub 拒绝访问或触发限流（" + http.group(1) + "），请检查权限与额度后重试";
                // 404 既可能是资源不存在，也可能是当前账号没有访问权限。
                case "404" -> "GitHub 仓库或文件不存在，或当前账号无权访问（404）";
                // 其他状态码只报告类别，不转发上游响应正文。
                default -> "GitHub 请求失败（HTTP " + http.group(1) + "），请稍后重试";
            };
        }
        // 用固定区域的小写规则匹配英文错误，避免受机器语言环境影响。
        String lower = text.toLowerCase(Locale.ROOT);
        // 参数或 Schema 验证失败表示尚未发送到 MCP 服务。
        if (lower.contains("parameter validation failed") || lower.contains("schema validation error"))
            // 提示检查模型产出的 JSON 参数格式。
            return "工具参数格式校验失败，请检查模型返回的工具参数 JSON（请求尚未发送到 MCP）";
        // 工具查找失败通常表示当前会话没有注册该工具。
        if (lower.contains("tool not found"))
            // 建议重新建立会话以重新注册工具定义。
            return "工具未注册，请新建对话后重试";
        // 识别英文和中文超时信息。
        if (lower.contains("timeout") || lower.contains("timed out") || text.contains("超时"))
            // 给出网络与工具服务的定位方向。
            return "工具调用超时，请检查 MCP 服务访问 GitHub 的网络后重试";
        // 识别仓库工具已封装的网络或响应错误。
        if (text.contains("GitHub 网络或响应异常"))
            // 提示检查 MCP 到 GitHub 的网络连接。
            return "MCP 服务访问 GitHub 时发生网络或响应异常，请检查网络后重试";
        // 识别提交 SHA、参数缺失和仓库白名单校验失败。
        if (text.contains("commitSha") || text.contains("缺少工具参数") || text.contains("仅允许仓库"))
            // 指导工具先读文件树再使用其返回的路径与提交 SHA。
            return "仓库工具参数不合法，请先获取文件树，再使用返回的文件路径和 commitSha";
        // 对其余未知错误返回固定兜底，详细信息只在服务端日志排查。
        return "工具返回错误或无有效来源，请查看 MCP 服务及问答服务控制台日志";
    }
}
