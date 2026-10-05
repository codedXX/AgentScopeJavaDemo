// 将 MCP HTTP 传输、Servlet 和工具注册配置归入 MCP 包。
package com.example.salesagent.mcp;

// 导入模型、仓库认证和业务 HTTP 地址等演示配置。
import com.example.salesagent.config.DemoProperties;
// 导入 GitHub、业务数据和平台能力工具适配器。
import com.example.salesagent.tool.*;
// 导入统一 JSON 编解码器，把工具返回对象转换为 MCP 文本内容。
import com.fasterxml.jackson.databind.ObjectMapper;
// 导入官方 MCP JSON 映射接口，供传输层处理协议消息。
import io.modelcontextprotocol.json.McpJsonMapper;
// 导入官方 MCP 服务构建器及同步服务类型。
import io.modelcontextprotocol.server.*;
// 导入基于 Servlet 的 Streamable HTTP 传输实现。
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
// 导入 MCP 工具、JSON Schema、服务能力与调用结果结构。
import io.modelcontextprotocol.spec.McpSchema;
// 导入映射和列表实现，为工具参数生成 JSON Schema。
import java.util.*;
// 导入延迟执行接口，将不同工具调用统一交给错误包装器。
import java.util.function.Supplier;
// 导入 Servlet 注册 Bean，把 MCP 传输绑定到 HTTP 路由。
import org.springframework.boot.web.servlet.ServletRegistrationBean;
// 导入配置类、Bean 和环境选择注解。
import org.springframework.context.annotation.*;

/** 真正的 MCP Streamable HTTP 服务，协议处理完全复用官方 SDK。 */
// 告诉 Spring 该类声明运行所需的 MCP 组件。
@Configuration
// 只在 mcp-server 环境启动工具协议服务。
@Profile("mcp-server")
// 组合官方协议实现与项目工具，提供统一 MCP 访问入口。
public class McpServerConfiguration {
    // 把传输提供器注册为 Spring Bean，供 Servlet 和 MCP 服务共享。
    @Bean
    // 创建处理 /mcp 请求的官方 Streamable HTTP 传输。
    public HttpServletStreamableServerTransportProvider mcpTransport() {
        // 使用默认协议 JSON 映射器，并明确 MCP 端点路径。
        return HttpServletStreamableServerTransportProvider.builder().jsonMapper(McpJsonMapper.getDefault()).mcpEndpoint("/mcp").build();
    }

    // 注册 Servlet，使嵌入式 Web 服务器能够接收 MCP HTTP 请求。
    @Bean
    // 使用同一传输实例处理 /mcp URL。
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(HttpServletStreamableServerTransportProvider transport) {
        // 将传输提供器作为 Servlet 映射到协议端点。
        var registration = new ServletRegistrationBean<>(transport, "/mcp");
        // Streamable HTTP 传输需要异步 Servlet 支持，允许连接处理不阻塞容器线程。
        registration.setAsyncSupported(true);
        // 交给 Spring Boot 自动注册到 Web 容器。
        return registration;
    }

    // 应用退出时调用 close，释放 MCP 服务资源。
    @Bean(destroyMethod = "close")
    // 注入传输、配置和 JSON 映射器，为工具创建底层客户端。
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, DemoProperties p, ObjectMapper mapper,
            // appUrl 指向主应用，检索与业务数据库能力通过 HTTP 调用，避免重复写索引。
            com.example.salesagent.agent.LlmGateway llm, @org.springframework.beans.factory.annotation.Value("${enterprise.app-url:http://127.0.0.1:8085}") String appUrl,
            // repository 限定可读取的代码仓库，未配置时使用演示仓库。
            @org.springframework.beans.factory.annotation.Value("${enterprise.github-repository:codedXX/redis-cache-demo}") String repository) {
        // 将 GitHub 地址、认证 token 和固定仓库注入只读仓库工具。
        var github = new GitHubRepositoryTools(p.github().apiUrl(), p.github().token(), mapper, repository);
        // 创建访问业务 HTTP 接口的工具，结果明确标记模拟数据。
        var business = new BusinessTools(p.mcp().businessUrl(), mapper);
        // 组合文本生成、Embedding、检索与数据源访问能力。
        var platform = new PlatformTools(llm, new com.example.salesagent.bailian.BailianEmbeddingClient(p), appUrl, mapper);
        // 用官方同步服务构建器绑定传输，并声明服务名称和版本。
        return McpServer.sync(transport).serverInfo("sales-demo-tools", "1.0.0")
                // 声明提供工具能力；false 表示不发送工具列表变更通知。
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // 注册无需参数的仓库文件树工具，说明结果包含固定提交与来源。
                .toolCall(tool("listRepositoryFiles", "列出已配置仓库的源码文件，返回固定 commitSha 和来源。", Map.of()),
                        // 真正执行文件树读取，并由统一包装器产生 MCP 成功或失败结果。
                        (exchange, request) -> result(github::listRepositoryFiles, mapper))
                // 注册文件读取工具，要求路径和从文件树取得的完整提交 SHA。
                .toolCall(tool("readRepositoryFile", "读取仓库源码；必须先从文件树取得 path 和 commitSha。", Map.of("path", "文件路径", "commitSha", "文件树返回的40位提交SHA")),
                        // 先校验两个字符串参数，再按固定提交读取源码。
                        (exchange, request) -> result(() -> github.readRepositoryFile(arg(request, "path"), arg(request, "commitSha")), mapper))
                // 注册价格库存工具，提示 DEMO-A 是模拟商品。
                .toolCall(tool("getProductStatus", "查询当前价格库存。演示商品 SKU 为 DEMO-A；结果是模拟业务数据。", Map.of("sku", "商品SKU")),
                        // 校验 SKU 参数后，通过 HTTP 获取业务状态。
                        (exchange, request) -> result(() -> business.getProductStatus(arg(request, "sku")), mapper))
                // 注册统一文本生成工具，输入参数名为 prompt。
                .toolCall(tool("generateText", "调用统一模型生成接口。", Map.of("prompt", "输入文本")),
                        // 平台工具先检查输入，再请求生成模型并返回来源。
                        (exchange, request) -> result(() -> platform.generate(arg(request, "prompt")), mapper))
                // 注册向量化工具，输入参数名为 text。
                .toolCall(tool("embedText", "调用统一Embedding接口，返回向量。", Map.of("text", "待向量化文本")),
                        // 将文本交给统一 Embedding 客户端生成向量。
                        (exchange, request) -> result(() -> platform.embed(arg(request, "text")), mapper))
                // 注册 RAG 工具，通过主应用索引返回知识证据。
                .toolCall(tool("searchKnowledge", "调用RAG检索接口，返回真实知识证据。", Map.of("query", "检索问题")),
                        // 校验 query 参数后调用主应用检索 HTTP API。
                        (exchange, request) -> result(() -> platform.search(arg(request, "query")), mapper))
                // 注册自然语言业务查询工具，export 在协议中以字符串 true/false 传入。
                .toolCall(tool("queryDataSource", "自然语言只读查询业务库。export传true或false，支持Excel导出。", Map.of("question", "业务问题", "export", "true或false")),
                        // 将 export 字符串转换为布尔值，再交给只读数据源查询入口。
                        (exchange, request) -> result(() -> platform.data(arg(request, "question"), Boolean.parseBoolean(arg(request, "export"))), mapper))
                // 完成工具注册并启动协议服务。
                .build();
    }

    // 将工具名称、说明和字符串字段描述转为官方 MCP Tool 元数据。
    private static McpSchema.Tool tool(String name, String description, Map<String, String> fields) {
        // 使用有序映射保存属性定义，使工具参数结构稳定可读。
        Map<String, Object> properties = new LinkedHashMap<>();
        // 每个声明字段都采用 string 类型，并给模型展示中文参数说明。
        fields.forEach((key, label) -> properties.put(key, Map.of("type", "string", "description", label)));
        // 设置协议工具的名称和用途说明。
        return McpSchema.Tool.builder().name(name).description(description)
                // 输入是对象，全部已声明字段必填，禁止额外字段，其余 Schema 元数据留空。
                .inputSchema(new McpSchema.JsonSchema("object", properties, new ArrayList<>(fields.keySet()), false, null, null)).build();
    }

    // 从 MCP 调用请求中读取一个必需的非空字符串参数。
    private static String arg(McpSchema.CallToolRequest request, String name) {
        // arguments 可能为空，安全地提取指定字段。
        Object value = request.arguments() == null ? null : request.arguments().get(name);
        // 类型必须是字符串且不能只有空白，避免工具收到未校验对象。
        if (!(value instanceof String text) || text.isBlank())
            // 参数校验发生在 result 的 Supplier 内，会转换为 MCP 工具错误。
            throw new IllegalArgumentException("缺少工具参数 " + name);
        // 返回已校验文本，不更改其实际值。
        return text;
    }

    // 把任意工具动作统一包装为 MCP 文本结果及 isError 标志。
    private static McpSchema.CallToolResult result(Supplier<?> action, ObjectMapper mapper) {
        // 执行工具和序列化的异常都在此处处理。
        try {
            // 将工具对象编码为 JSON 文本，并声明本次工具执行成功。
            return McpSchema.CallToolResult.builder().addTextContent(mapper.writeValueAsString(action.get())).isError(false).build();
        // 工具失败仍返回合法 MCP 结果，便于 Agent 自行选择兜底或重试。
        } catch (Exception ex) {
            // 不回传堆栈/认证头。工具错误仍是正常 MCP 响应，便于 Agent 决定如何回答。
            // 参数和状态异常使用已有可读说明，其他异常只返回固定文案。
            String message = ex instanceof IllegalArgumentException || ex instanceof IllegalStateException ? ex.getMessage() : "工具执行失败";
            // 显式设置 isError=true，使客户端不会把错误文本当成知识证据。
            return McpSchema.CallToolResult.builder().addTextContent("工具失败：" + message).isError(true).build();
        }
    }
}
