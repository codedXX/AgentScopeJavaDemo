// 测试类位于生产组件同包，可直接检查包内可见的状态与工具轨迹。
package com.example.salesagent.agent;

// 复用生产配置记录及模型工厂，避免测试跳过 SDK 适配逻辑。
import com.example.salesagent.config.*;
// 使用生产请求与检索结果模型构造确定性问答数据。
import com.example.salesagent.model.*;
// 检索器使用替身提供固定证据，隔离真实向量库依赖。
import com.example.salesagent.rag.HybridRetriever;
// JSON 映射器负责构造或解析模型与工具测试响应。
import com.fasterxml.jackson.databind.ObjectMapper;
// 本地 HTTP 服务提供模型接口替身，让真实 AgentScope SDK 执行请求。
import com.sun.net.httpserver.HttpServer;
// 保留生产聊天模型类型，真实请求发送到本地测试服务器。
import io.agentscope.core.model.DashScopeChatModel;
// MCP 客户端用 Mockito 替身模拟工具发现与工具返回。
import io.agentscope.core.tool.mcp.McpClientWrapper;
// 指定服务器只监听本机并由系统分配空闲端口。
import java.net.InetSocketAddress;
// 显式指定 UTF-8，确保模型响应字节编码一致。
import java.nio.charset.StandardCharsets;
// 引入 List、Map、UUID 等测试输入和响应的数据结构。
import java.util.*;
// 用线程安全列表收集 HTTP 处理线程收到的路由请求。
import java.util.concurrent.CopyOnWriteArrayList;
// Test 标记需要由 JUnit 执行的测试方法。
import org.junit.jupiter.api.Test;
// 模拟 Spring 延迟依赖提供器，按测试需要返回模型或 MCP 客户端。
import org.springframework.beans.factory.ObjectProvider;
// 引入断言方法，验证结果、异常与隔离边界。
import static org.junit.jupiter.api.Assertions.*;
// 引入 mock、when、verify 等方法，控制外部依赖并检查调用行为。
import static org.mockito.Mockito.*;

/** 模型 HTTP 响应使用确定性替身，AgentScope 的结构化输出、Memory、Hook 链实际执行。 */
// 验证主问答流程的 MCP 兜底、来源过滤、会话隔离及执行 Agent 的工具注册。
class SalesAssistantTest {
    // 测试泛型模型提供器使用 Mockito，屏蔽其未检查类型转换警告。
    @SuppressWarnings("unchecked")
    // 验证 MCP 离线时仍能使用知识证据，并保持不同会话的历史隔离。
    @Test void answersFromRagWhenMcpIsDownAndIsolatesHistory() throws Exception {
        // 创建 JSON 映射器，用于模拟模型请求和响应格式。
        var mapper = new ObjectMapper();
        // 线程安全收集路由器收到的历史，供后续检查会话隔离。
        List<String> routerInputs = new CopyOnWriteArrayList<>();
        // 创建仅监听本机、使用随机空闲端口的 HTTP 模型替身。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 注册接收 SDK 请求的处理器，使用确定性逻辑代替模型生成。
        server.createContext("/", exchange -> {
            // 读取请求完整字节并解析为 JSON。
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            // 提取 SDK 发送的 input.messages 消息列表。
            var messages = request.path("input").path("messages");
            // 以分类提示词识别意图路由调用，区分主回答调用。
            boolean routing = messages.get(0).path("content").toString().contains("把问题分为");
            // 只记录分类请求，便于检查第二轮和其他会话的历史内容。
            if (routing) routerInputs.add(messages.toString());
            // 路由请求固定返回 KNOWLEDGE 和产品 A 蛋白质的独立查询。
            Object output = routing ? Map.of("intent", "KNOWLEDGE", "query", "产品A的蛋白质含量")
                    // 回答请求返回正确正文和一真一假两个来源，检验应用的引用白名单。
                    : Map.of("answer", "每袋含15克蛋白质。", "sources", List.of("products.md", "https://invented.invalid"));
            // 构造带唯一 ID 的函数调用块，模拟结构化输出工具。
            var toolCall = Map.of("id", UUID.randomUUID().toString(), "type", "function", "function",
                    // 用 generate_response 返回 response 数据，并将参数序列化为 JSON 字符串。
                    Map.of("name", "generate_response", "arguments", mapper.writeValueAsString(Map.of("response", output))));
            // 把函数调用放入助手消息，文本内容保持空以走 SDK 的工具解析路径。
            var message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall));
            // 按模型 API 格式封装输出选项，并把完成原因设置为 tool_calls。
            var response = Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message))),
                    // 提供 SDK 所需的输入和输出 token 用量字段。
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1));
            // 将模拟响应编码为 UTF-8 JSON 字节。
            byte[] bytes = mapper.writeValueAsBytes(response);
            // 明确返回 application/json，符合模型 SDK 的响应解析约定。
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // 发送成功状态和长度，写出完整响应后关闭 HTTP 交换。
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        // 完成处理器注册并启动本地 HTTP 替身。
        }); server.start();
        // 配置含 /api/v1 后缀的本地基础地址，兼顾生产路径归一化行为。
        var p = new DemoProperties(new DemoProperties.Bailian("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                // 保留实际聊天模型参数结构，其余未使用配置组传 null。
                "qwen3.7-flash", "qwen3.7-text-embedding", "qwen3.7-text-rerank", 1024, 5), null, null, null, null);
        // 创建泛型模型提供器替身，方便注入生产问答服务。
        ObjectProvider<DashScopeChatModel> model = mock(ObjectProvider.class);
        // 使用生产模型工厂构造适配器，实际 HTTP 调用仍发往本地服务器。
        when(model.getObject()).thenReturn(new AgentConfiguration().chatModel(p));
        // 创建 MCP 提供器替身，以测试工具不可用场景。
        ObjectProvider<McpClientWrapper> mcp = mock(ObjectProvider.class);
        // 首次获取 MCP 客户端就抛出离线异常，触发知识证据兜底。
        when(mcp.getObject()).thenThrow(new IllegalStateException("offline"));
        // 模拟检索器，不依赖真实向量服务或文档索引。
        var retriever = mock(HybridRetriever.class);
        // 任意查询都返回固定 RagResult，准备唯一知识证据。
        when(retriever.retrieve(anyString())).thenReturn(new RagResult(List.of(new SearchHit(
                // 命中产品文档分块，分数为 0.9、证据足够且耗时十毫秒。
                new KnowledgeChunk("A", "每袋蛋白质15克", "products.md", 0), .9, "rerank")), false, 10));
        // 使用基础构造器创建仅内存会话的问答助手。
        var assistant = new SalesAssistant(retriever, model, mcp, mapper);
        // 将问答断言放在资源清理边界内，保证模型服务器和执行器关闭。
        try {
            // 第一轮问题加入独特标记，方便追踪后续分类历史的传播。
            var first = assistant.chat(new ChatRequest("one", "独特问题标记：产品A的蛋白质含量？"));
            // 断言伪造链接被过滤，仅剩检索提供的 products.md 来源。
            assertEquals(List.of("products.md"), first.sources());
            // 断言步骤明确记录 MCP 不可用，但没有阻止已有知识回答。
            assertTrue(first.steps().stream().anyMatch(s -> s.contains("MCP 不可用")));
            // 同一会话继续追问，应该把上一轮完整问题带给意图分类器。
            assistant.chat(new ChatRequest("one", "它呢？"));
            // 新会话提出类似问题，应该从空历史开始。
            assistant.chat(new ChatRequest("two", "产品A如何？"));
            // 断言同会话第二轮的分类上下文包含第一轮独特标记。
            assertTrue(routerInputs.get(1).contains("独特问题标记"));
            // 断言另一个会话的分类上下文没有第一会话的标记。
            assertFalse(routerInputs.get(2).contains("独特问题标记"));
        // 测试结束或失败时关闭问答线程池，并停止本地模型服务器。
        } finally { assistant.close(); server.stop(0); }
    }
    // 第二个测试也使用泛型 ObjectProvider 替身，屏蔽未检查类型警告。
    @SuppressWarnings("unchecked")
    // 验证动态 MCP 注册使用执行 Agent 真正持有的 Toolkit，而非构建前副本。
    @Test void repositoryToolsAreRegisteredOnTheExecutingAgent() throws Exception {
        // 创建 JSON 映射器用于工具及模型响应。
        var mapper = new ObjectMapper();
        // 构造固定四十位提交 SHA 的 README 来源，模拟可追溯仓库证据。
        var source = "https://github.com/example/repo/blob/" + "a".repeat(40) + "/README.md";
        // 用原子布尔值记录模型请求是否真实携带仓库工具 Schema。
        var sawRepositorySchema = new java.util.concurrent.atomic.AtomicBoolean();
        // 建立随机端口的本地 HTTP 模型服务器。
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 注册根据消息阶段切换函数调用的模型处理器。
        server.createContext("/", exchange -> {
            // 读取并解析模型请求 JSON。
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            // 提取对话消息，判断路由、工具调用或最终回答阶段。
            var messages = request.path("input").path("messages");
            // 识别只负责意图分类的请求。
            boolean routing = messages.get(0).path("content").toString().contains("把问题分为");
            // 保存当前响应需要调用的函数名称。
            String name;
            // 保存当前响应需要序列化的工具参数对象。
            Map<String, Object> arguments;
            // 分类阶段只返回仓库路由，不调用实际仓库工具。
            if (routing) {
                // 选择结构化回答函数 generate_response。
                name = "generate_response";
                // 返回 REPOSITORY 路由和读取 README 的独立查询。
                arguments = Map.of("response", Map.of("intent", "REPOSITORY", "query", "读取仓库 README"));
            // 尚未看见工具返回的仓库说明时，要求先获取仓库证据。
            } else if (!messages.toString().contains("测试仓库说明")) {
                // 记录模型请求的 tools 是否含文件树工具定义，证明已注册到实际 Agent。
                sawRepositorySchema.set(request.path("parameters").path("tools").toString().contains("listRepositoryFiles"));
                // 选择文件树 MCP 工具作为下一次函数调用。
                name = "listRepositoryFiles";
                // 文件树工具没有参数，返回合法空 Map。
                arguments = Map.of();
            // 已经收到仓库说明后转入最终回答生成阶段。
            } else {
                // 使用 generate_response 提交结构化正文和引用。
                name = "generate_response";
                // 返回根据工具资料生成的测试仓库说明，并引用真实工具来源。
                arguments = Map.of("response", Map.of("answer", "这是测试仓库。", "sources", List.of(source)));
            }
            // 构造函数调用块并分配唯一调用 ID。
            var toolCall = Map.of("id", UUID.randomUUID().toString(), "type", "function", "function",
                    // 把所选工具名和参数编码为模型 API 需要的 function 结构。
                    Map.of("name", name, "arguments", mapper.writeValueAsString(arguments)));
            // 构造包含 tool_calls 的助手消息。
            var message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall));
            // 把助手消息封装为模型输出选项，完成原因设置为工具调用。
            var response = Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message))),
                    // 提供最小 token 用量信息，使实际 SDK 正常解析响应。
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1));
            // 将模型输出序列化为 JSON 字节。
            byte[] bytes = mapper.writeValueAsBytes(response);
            // 设置 JSON 响应媒体类型。
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // 发送 HTTP 200，写入响应后关闭交换资源。
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        // 启动已注册处理器的本地模型服务器。
        server.start();
        // 用本地模型地址和测试 API Key 构造生产格式配置。
        var p = new DemoProperties(new DemoProperties.Bailian("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                // 聊天模型保留多模态配置，Embedding 与 Rerank 未调用所以设为 unused。
                "qwen3.7-flash", "unused", "unused", 1024, 5), null, null, null, null);
        // 创建可延迟获取模型的 ObjectProvider 替身。
        ObjectProvider<DashScopeChatModel> model = mock(ObjectProvider.class);
        // 模型提供器返回生产工厂创建的本地 HTTP 模型适配器。
        when(model.getObject()).thenReturn(new AgentConfiguration().chatModel(p));
        // 创建远程 MCP 客户端替身，工具由真实 Toolkit 调度。
        var client = mock(McpClientWrapper.class);
        // 客户端名称设为 test，用于注册标识。
        when(client.getName()).thenReturn("test");
        // 模拟 MCP 初始化成功并立即完成。
        when(client.initialize()).thenReturn(reactor.core.publisher.Mono.empty());
        // 定义严格的空对象参数 Schema，适配无参数文件树工具。
        var schema = new io.modelcontextprotocol.spec.McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        // 模拟 MCP 工具发现接口返回单个工具。
        when(client.listTools()).thenReturn(reactor.core.publisher.Mono.just(List.of(
                // 该工具的名称为 listRepositoryFiles，与模型随后生成的调用一致。
                io.modelcontextprotocol.spec.McpSchema.Tool.builder().name("listRepositoryFiles")
                        // 设置工具描述和 Schema，完成可注册工具定义。
                        .description("List repository").inputSchema(schema).build())));
        // 让 MCP 调用返回成功的响应式结果。
        when(client.callTool(eq("listRepositoryFiles"), anyMap())).thenReturn(reactor.core.publisher.Mono.just(
                // 构造 MCP CallToolResult，保留真实工具结果格式。
                io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder()
                        // 响应正文含固定仓库来源和说明，供 Hook 收集有效证据。
                        .addTextContent(mapper.writeValueAsString(Map.of("source", source, "text", "测试仓库说明")))
                        // 标记为非错误结果并完成构建。
                        .isError(false).build()));
        // 创建 MCP 客户端提供器替身，供 SalesAssistant 延迟获取。
        ObjectProvider<McpClientWrapper> mcp = mock(ObjectProvider.class);
        // 将已经配置工具发现及调用行为的客户端交给提供器。
        when(mcp.getObject()).thenReturn(client);
        // 创建检索器替身，后续验证仓库意图不会误触发固定 RAG。
        var retriever = mock(HybridRetriever.class);
        // 创建生产问答助手，让其真实注册与执行 MCP 工具。
        var assistant = new SalesAssistant(retriever, model, mcp, mapper);
        // 将仓库工具验证放在资源清理边界内，防止失败测试遗留工作线程。
        try {
            // 提出仓库 README 问题，触发 REPOSITORY 路由和工具循环。
            var result = assistant.chat(new ChatRequest("repository", "读取仓库 README"));
            // 断言最终正文来自模拟仓库工具说明。
            assertEquals("这是测试仓库。", result.answer());
            // 断言最终来源精确等于工具返回的固定提交链接。
            assertEquals(List.of(source), result.sources());
            // 断言模型收到注册后的真实工具定义，防止 Toolkit 副本注册回归。
            assertTrue(sawRepositorySchema.get(), "模型应收到实际已注册的工具定义");
            // 断言执行轨迹包含工具成功事件。
            assertTrue(result.steps().stream().anyMatch(step -> step.contains("工具成功")));
            // 验证 MCP 客户端确实执行无参数文件树调用。
            verify(client).callTool("listRepositoryFiles", Map.of());
            // 验证检索器从未被调用，仓库问题通过工具取得证据。
            verifyNoInteractions(retriever);
        // 无论测试是否成功，都停止问答执行器与 HTTP 服务器。
        } finally { assistant.close(); server.stop(0); }
    }

}
