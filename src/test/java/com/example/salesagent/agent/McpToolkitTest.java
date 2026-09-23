package com.example.salesagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
/** 验证仓库工具可以通过 Agent 的工具箱执行。 */
class McpToolkitTest {
    /** 工具箱能执行仓库工具。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "null", "{\"repository\":\"codedXX/redis-cache-demo\"}"})
    void executesRepositoryToolThroughToolkit(String rawArguments) {
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.initialize()).thenReturn(Mono.empty());
        when(client.getName()).thenReturn("test");
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        when(client.listTools()).thenReturn(Mono.just(List.of(McpSchema.Tool.builder()
                .name("listRepositoryFiles").description("List repository").inputSchema(schema).build())));
        when(client.callTool(eq("listRepositoryFiles"), anyMap())).thenReturn(Mono.just(
                McpSchema.CallToolResult.builder().addTextContent("{\"source\":\"https://github.com/example/repo\"}").isError(false).build()));
        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(client).block();
        ToolUseBlock use = ToolUseBlock.builder().id("one").name("listRepositoryFiles").input(Map.of()).content(rawArguments).build();
        ToolTrace trace = new ToolTrace(new ObjectMapper());
        io.agentscope.core.hook.PreActingEvent pre = new io.agentscope.core.hook.PreActingEvent(mock(io.agentscope.core.agent.Agent.class), toolkit, use);
        trace.onEvent(pre).block();
        ToolResultBlock result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(pre.getToolUse()).build()).block();
        assertNotNull(result);
        for (Object block : result.getOutput()) {
            if (block instanceof TextBlock) {
                TextBlock text = (TextBlock) block;
                trace.recordResult("listRepositoryFiles", text.getText());
                assertFalse(trace.sources.isEmpty(), text.getText());
            }
        }
        verify(client).callTool("listRepositoryFiles", Map.of());
    }
}
