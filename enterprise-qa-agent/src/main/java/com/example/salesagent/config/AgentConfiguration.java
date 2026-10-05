// 模型与 MCP 客户端的 Spring 配置归入 config 包。
package com.example.salesagent.config;

// 引入聊天模型、生成参数和执行配置类型。
import io.agentscope.core.model.*;
// 引入 MCP 客户端包装器及构建器。
import io.agentscope.core.tool.mcp.*;
// Duration 表示各模型请求与握手操作的超时时间。
import java.time.Duration;
// 导入 Configuration、Bean 和 Lazy 等 Spring 注解。
import org.springframework.context.annotation.*;

// 将此类标记为 Spring Bean 工厂配置。
@Configuration
// 集中创建可延迟获取的聊天模型与 MCP 客户端。
public class AgentConfiguration {
    // 注册聊天模型；延迟创建，直到真正需要模型调用时才读取凭证。
    @Bean
    // 此 Bean 延迟到首次依赖获取时创建，避免启动时触发外部调用。
    @Lazy
    // 从 demo.bailian 配置构造 AgentScope 聊天模型适配器。
    public DashScopeChatModel chatModel(DemoProperties p) {
        // 缺少 API Key 时立即给出明确错误，避免发送无效模型请求。
        if (p.bailian().apiKey().isBlank()) throw new IllegalStateException("请先配置 DASHSCOPE_API_KEY");
        // 用外部 API Key 和配置中的模型名称建立聊天模型。
        return DashScopeChatModel.builder().apiKey(p.bailian().apiKey()).modelName(p.bailian().chatModel())
                // AgentScope 自己补 /api/v1；百炼 Embedding/Rerank SDK 则需要该前缀。
                // 删除地址结尾的 /api/v1，关闭流式输出与思考输出以适配结构化响应。
                .baseUrl(p.bailian().baseUrl().replaceFirst("/api/v1/?$", "")).stream(false).enableThinking(false)
                // Qwen3.7-Flash 是多模态模型；即使只发文本也必须走多模态端点。
                // 显式选择多模态端点，保持模型与请求 API 类型一致。
                .endpointType(io.agentscope.core.model.EndpointType.MULTIMODAL)
                // 采用低温度减少答案变化，单次生成限制为 2048 token。
                .defaultOptions(GenerateOptions.builder().temperature(0.1).maxTokens(2048)
                        // 按模型配置设置一次调用的执行时限。
                        .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(p.bailian().timeoutSeconds()))
                                // 只尝试一次，避免 SDK 重试叠加导致总问答时限失控；完成配置及模型构建。
                                .maxAttempts(1).build()).build()).build();
    }
    // 注册延迟创建的 MCP 客户端；Spring 销毁 Bean 时调用 close 释放连接。
    @Bean(destroyMethod = "close")
    // 此 Bean 延迟到首次依赖获取时创建，避免启动时触发外部调用。
    @Lazy
    // 从 demo.mcp 地址构造并初始化单例 MCP 客户端。
    public McpClientWrapper mcpClient(DemoProperties p) {
        // 连接指定的 Streamable HTTP MCP 地址，并为客户端设置稳定名称。
        var client = McpClientBuilder.create("sales-tools").streamableHttpTransport(p.mcp().url())
                // 初始化最多 10 秒、工具调用最多 20 秒，构建同步客户端包装器。
                .initializationTimeout(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(20)).buildSync();
        // Spring 单例工厂只初始化一次，避免并发会话重复发起 MCP 握手。
        try {
            // 等待 MCP 初始化完成，工厂层的等待上限为 15 秒。
            client.initialize().block(Duration.ofSeconds(15));
            // 返回已完成握手的客户端供各会话注册工具。
            return client;
        // 捕获初始化失败，先释放客户端资源再重新抛出原错误。
        } catch (RuntimeException ex) {
            // 初始化失败时关闭客户端，避免遗留连接资源。
            client.close();
            // 保留原异常，让调用方决定是否退回已有知识证据。
            throw ex;
        }
    }
}
