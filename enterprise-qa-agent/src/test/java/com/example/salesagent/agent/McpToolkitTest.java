// 测试类位于生产组件同包，可直接检查包内可见的状态与工具轨迹。
package com.example.salesagent.agent;

// JSON 映射器负责构造或解析模型与工具测试响应。
import com.fasterxml.jackson.databind.ObjectMapper;
// 引入 AgentScope 消息角色、文本输出与工具调用块。
import io.agentscope.core.message.*;
// 引入真实 Toolkit 与工具调用参数，测试实际注册和执行链。
import io.agentscope.core.tool.*;
// MCP 客户端用 Mockito 替身模拟工具发现与工具返回。
import io.agentscope.core.tool.mcp.McpClientWrapper;
// 使用 MCP 协议工具 Schema 和结果类型构造合法测试响应。
import io.modelcontextprotocol.spec.McpSchema;
// 引入 List、Map、UUID 等测试输入和响应的数据结构。
import java.util.*;
// Test 标记需要由 JUnit 执行的测试方法。
import org.junit.jupiter.api.Test;
// Mono 模拟 AgentScope 与 MCP 接口的响应式完成结果。
import reactor.core.publisher.Mono;
// 引入 mock、when、verify 等方法，控制外部依赖并检查调用行为。
import static org.mockito.Mockito.*;
// 引入断言方法，验证结果、异常与隔离边界。
import static org.junit.jupiter.api.Assertions.*;

// 验证工具参数归一化可通过真实 Toolkit 的 Schema 校验和执行路径。
class McpToolkitTest {
    // 同一测试针对多种原始参数字符串重复执行。
    @org.junit.jupiter.params.ParameterizedTest
    // 将 null 和空字符串也作为模型可能产生的参数输入。
    @org.junit.jupiter.params.provider.NullAndEmptySource
    // 额外覆盖空对象、JSON null 和携带多余仓库字段的参数。
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "null", "{\"repository\":\"codedXX/redis-cache-demo\"}"})
    // 验证文件树工具最终总是以空参数对象执行。
    void executesRepositoryToolThroughToolkit(String rawArguments) {
        // 用 Mockito 替代远程 MCP 客户端，避免访问真实服务。
        var client = mock(McpClientWrapper.class);
        // 模拟 MCP 初始化成功且没有额外返回内容。
        when(client.initialize()).thenReturn(Mono.empty());
        // 给模拟客户端一个稳定名称，供 Toolkit 注册时标识来源。
        when(client.getName()).thenReturn("test");
        // 定义无字段、无必填项且不允许额外字段的空对象参数 Schema。
        var schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        // 让模拟 MCP 服务返回一个工具定义列表。
        when(client.listTools()).thenReturn(Mono.just(List.of(McpSchema.Tool.builder()
                // 工具名称设为 listRepositoryFiles，并绑定前面定义的无参数 Schema。
                .name("listRepositoryFiles").description("List repository").inputSchema(schema).build())));
        // 无论 Toolkit 传入何种 Map，模拟文件树工具调用都返回固定成功结果。
        when(client.callTool(eq("listRepositoryFiles"), anyMap())).thenReturn(Mono.just(
                // 构造含真实 source 字段的文本工具响应，并标记为非错误。
                McpSchema.CallToolResult.builder().addTextContent("{\"source\":\"https://github.com/example/repo\"}").isError(false).build()));
        // 使用真实 AgentScope Toolkit，保留注册、参数校验和执行流程。
        var toolkit = new Toolkit();
        // 同步等待 Toolkit 将模拟 MCP 工具注册完成。
        toolkit.registerMcpClient(client).block();
        // 把当前参数化用例的原始字符串填入 ToolUseBlock.content。
        var use = ToolUseBlock.builder().id("one").name("listRepositoryFiles").input(Map.of()).content(rawArguments).build();
        // 创建真实工具 Hook，验证其预执行参数修正能力。
        var trace = new ToolTrace(new ObjectMapper());
        // 构造工具执行前事件，关联 Toolkit 和当前调用请求。
        var pre = new io.agentscope.core.hook.PreActingEvent(mock(io.agentscope.core.agent.Agent.class), toolkit, use);
        // 运行 Hook，将无参数文件树请求归一化为合法空对象。
        trace.onEvent(pre).block();
        // 通过真实 Toolkit 执行修正后的调用，等待其返回。
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(pre.getToolUse()).build()).block();
        // 断言工具执行没有返回空结果。
        assertNotNull(result);
        // 逐个读取输出块，仅对文本块进行来源解析。
        for (var block : result.getOutput()) if (block instanceof TextBlock text) {
            // 把实际工具文本送给 trace，以验证合法来源能被识别。
            trace.recordResult("listRepositoryFiles", text.getText());
            // 断言响应形成有效来源，失败时以原文本辅助测试定位。
            assertFalse(trace.sources.isEmpty(), text.getText());
        }
        // 验证客户端收到的参数严格等于空 Map，多余字段已被去除。
        verify(client).callTool("listRepositoryFiles", Map.of());
    }
}
