package com.example.salesagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.TextBlock;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/** 记录可观察工具事件，不记录或返回模型的隐式思考过程。每个会话独立。 */
final class ToolTrace implements Hook {
    private final ObjectMapper mapper;
    final List<String> steps = new CopyOnWriteArrayList<>();
    final Set<String> sources = Collections.synchronizedSet(new LinkedHashSet<>());
    final AtomicInteger calls = new AtomicInteger();
    private final Set<String> tools = Set.of("listRepositoryFiles", "readRepositoryFile", "getProductStatus");
    ToolTrace(ObjectMapper mapper) { this.mapper = mapper; }
    void reset() { steps.clear(); sources.clear(); calls.set(0); }
    @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent pre && tools.contains(pre.getToolUse().getName())) {
            if (calls.incrementAndGet() > 5) return Mono.error(new IllegalStateException("本轮工具调用超过5次上限"));
            steps.add("调用工具：" + pre.getToolUse().getName());
        }
        if (event instanceof PostActingEvent post && tools.contains(post.getToolUse().getName())) {
            for (var block : post.getToolResult().getOutput()) if (block instanceof TextBlock text) {
                try {
                    var node = mapper.readTree(text.getText());
                    if (node != null && node.hasNonNull("source")) sources.add(node.get("source").asText());
                } catch (Exception ignored) { steps.add("工具返回说明或错误：" + post.getToolUse().getName()); }
            }
        }
        return Mono.just(event);
    }
}
