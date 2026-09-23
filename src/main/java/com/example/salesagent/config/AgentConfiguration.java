package com.example.salesagent.config;

import io.agentscope.core.model.*;
import io.agentscope.core.tool.mcp.*;
import java.time.Duration;
import org.springframework.context.annotation.*;

@Configuration @Profile("app")
public class AgentConfiguration {
    @Bean @Lazy public DashScopeChatModel chatModel(DemoProperties p) {
        if (p.getBailian().getApiKey().isBlank()) throw new IllegalStateException("请先配置 DASHSCOPE_API_KEY");
        return DashScopeChatModel.builder().apiKey(p.getBailian().getApiKey()).modelName(p.getBailian().getChatModel())
                // AgentScope 自己补 /api/v1；百炼 Embedding/Rerank SDK 则需要该前缀。
                .baseUrl(p.getBailian().getBaseUrl().replaceFirst("/api/v1/?$", "")).stream(false).enableThinking(false)
                // Qwen3.7-Flash 是多模态模型；即使只发文本也必须走多模态端点。
                .endpointType(io.agentscope.core.model.EndpointType.MULTIMODAL)
                .defaultOptions(GenerateOptions.builder().temperature(0.1).maxTokens(2048)
                        .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(p.getBailian().getTimeoutSeconds()))
                                .maxAttempts(1).build()).build()).build();
    }
    @Bean(destroyMethod = "close") @Lazy public McpClientWrapper mcpClient(DemoProperties p) {
        McpClientWrapper client = McpClientBuilder.create("sales-tools").streamableHttpTransport(p.getMcp().getUrl())
                .initializationTimeout(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(20)).buildSync();
        // Spring 单例工厂只初始化一次，避免并发会话重复发起 MCP 握手。
        try { client.initialize().block(Duration.ofSeconds(15)); return client; }
        catch (RuntimeException ex) { client.close(); throw ex; }
    }
}
