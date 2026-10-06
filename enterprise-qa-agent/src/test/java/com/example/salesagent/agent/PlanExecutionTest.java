// 测试类位于生产组件同包，可直接检查包内可见的状态与工具轨迹。
package com.example.salesagent.agent;
// 复用生产配置记录及模型工厂，避免测试跳过 SDK 适配逻辑。
import com.example.salesagent.config.*;
// 使用生产请求与检索结果模型构造确定性问答数据。
import com.example.salesagent.model.*;
// 检索器使用替身提供固定证据，隔离真实向量库依赖。
import com.example.salesagent.rag.HybridRetriever;
// 引入真实业务数据库、Text2SQL 和 Excel 导出组件。
import com.example.salesagent.sql.*;
// 使用生产数据库工具，验证 Agent 的 Function Calling 执行。
import com.example.salesagent.tool.DataTools;
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
// Path 保存文件测试使用的临时目录路径。
import java.nio.file.Path;
// 引入 List、Map、UUID 等测试输入和响应的数据结构。
import java.util.*;
// 引入 JUnit 测试注解与测试生命周期相关类型。
import org.junit.jupiter.api.*;
// TempDir 为文件测试提供独立、自动清理的临时目录。
import org.junit.jupiter.api.io.TempDir;
// 模拟 Spring 延迟依赖提供器，按测试需要返回模型或 MCP 客户端。
import org.springframework.beans.factory.ObjectProvider;
// 引入断言方法，验证结果、异常与隔离边界。
import static org.junit.jupiter.api.Assertions.*;
// 引入 mock、when、verify 等方法，控制外部依赖并检查调用行为。
import static org.mockito.Mockito.*;
/** 真实 AgentScope 工具循环执行经验证的计划，SQL 部分使用真实只读 H2。 */
// 通过真实 AgentScope 循环和 H2 查询验证计划执行、导出与持久化的协作。
class PlanExecutionTest {
    // 为导出文件和会话文件提供自动清理的临时根目录。
    @TempDir Path temp;
    // Mockito 使用泛型 ObjectProvider 时会有未检查转换，这里仅屏蔽该编译警告。
    @SuppressWarnings("unchecked")
    // 执行先知识检索、再依赖数据库查询的完整计划，并检查证据与会话。
    @Test void executesKnowledgeThenDependentSqlAndPersistsConversation() throws Exception {
        // 创建 JSON 映射器和随机本地端口的 HTTP 模型替身。
        var mapper=new ObjectMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        // 模型替身接收全部路径的请求，按当前消息阶段返回确定性响应。
        server.createContext("/",exchange->{
            // 解析 SDK 发出的请求，并读取 input.messages 中的消息序列。
            var request=mapper.readTree(exchange.getRequestBody());var messages=request.path("input").path("messages");
            // 用固定分类提示词判断当前是否为意图路由调用。
            boolean routing=messages.get(0).path("content").toString().contains("把问题分为");
            // 检查消息是否包含统计子问题，识别数据库步骤。
            boolean data=messages.toString().contains("统计华东销售额");
            // 检查工具结果来源是否已进入上下文，判断数据库查询是否完成。
            boolean completed=messages.toString().contains("business://products-sales");
            // 为接下来选择的工具名称与参数准备局部变量。
            String tool;Object args;
            // 路由阶段返回 MULTI_TASK 和原复合查询，使主服务进入计划执行。
            if(routing){tool="generate_response";args=Map.of("response",Map.of("intent","MULTI_TASK","query","先介绍产品A，再统计华东销售额"));}
            // 数据库步骤尚未取得结果时要求调用 queryBusinessData，并开启 Excel 导出。
            else if(data&&!completed){tool="queryBusinessData";args=Map.of("question","华东总销售额","export",true);}
            // 知识或查询完成后返回结构化回答；数据库答案含 3770 元及对应来源。
            else {tool="generate_response";args=Map.of("response",Map.of("answer",completed?"华东销售额3770元。":"产品A每袋蛋白质15克。","sources",List.of(completed?"business://products-sales":"products.md")));}
            // 构造符合模型 API 格式的函数调用，参数以 JSON 字符串封装。
            var call=Map.of("id",UUID.randomUUID().toString(),"type","function","function",Map.of("name",tool,"arguments",mapper.writeValueAsString(args)));
            // 构造 tool_calls 完成原因、助手消息和 token 用量字段，供真实 SDK 解析。
            var output=Map.of("output",Map.of("choices",List.of(Map.of("finish_reason","tool_calls","message",Map.of("role","assistant","content","","tool_calls",List.of(call))))),"usage",Map.of("input_tokens",1,"output_tokens",1));
            // 把响应序列化为 JSON 字节，返回 HTTP 200，写入响应并关闭交换资源。
            byte[] bytes=mapper.writeValueAsBytes(output);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        // 完成 HTTP 处理器注册并启动本地模型替身。
        });server.start();
        // 以测试 API Key 和本地地址建立模型配置，真实外部接口不会被访问。
        var p=new DemoProperties(new DemoProperties.Bailian("test-key","http://127.0.0.1:"+server.getAddress().getPort(),"test","e","r",2,5),null,null,null,null);
        // 模拟模型提供器，实际返回通过生产 AgentConfiguration 创建的模型适配器。
        ObjectProvider<DashScopeChatModel> models=mock(ObjectProvider.class);when(models.getObject()).thenReturn(new AgentConfiguration().chatModel(p));
        // 模拟 MCP 提供器始终离线，验证知识证据和本地 DATA 工具仍能执行计划。
        ObjectProvider<McpClientWrapper> mcp=mock(ObjectProvider.class);when(mcp.getObject()).thenThrow(new IllegalStateException("offline"));
        // 模拟检索返回产品文档中的蛋白质证据和固定十毫秒耗时。
        var retriever=mock(HybridRetriever.class);when(retriever.retrieve(anyString())).thenReturn(new RagResult(List.of(new SearchHit(new KnowledgeChunk("a","蛋白质15克","products.md",0),.9,"rerank")),false,10));
        // 模拟规划器返回确定性两步计划，避免把计划正确性依赖于外部模型。
        var planner=mock(TaskPlanner.class);when(planner.plan(anyString())).thenReturn(new TaskPlanner.Plan(List.of(
            // 第一步为产品 A 知识查询，无前置依赖。
            new TaskPlanner.Step("a",TaskPlanner.Action.KNOWLEDGE,"介绍产品A",List.of()),
            // 第二步为华东销售额统计，显式依赖第一步 a。
            new TaskPlanner.Step("b",TaskPlanner.Action.DATA,"统计华东销售额",List.of("a")))));
        // 用模型网关替身控制 SQL 生成与最终汇总结果。
        var llm=mock(LlmGateway.class);
        // 把销售额问题映射到受测试的 SUM 只读 SQL，筛选华东地区。
        when(llm.structured(anyString(),eq("华东总销售额"),eq(Text2SqlService.GeneratedSql.class))).thenReturn(new Text2SqlService.GeneratedSql("SELECT SUM(amount) AS total FROM sales WHERE region='华东'"));
        // 汇总返回真实知识和数据库来源，并故意加入 invented 以验证来源过滤。
        when(llm.structured(anyString(),anyString(),eq(SalesAssistant.Answer.class))).thenReturn(new SalesAssistant.Answer("产品A蛋白质15克；华东销售额3770元（模拟数据）。",List.of("products.md","business://products-sales","invented")));
        // 建立唯一命名的 H2 内存数据库，使用 PostgreSQL 模式和小写表名。
        var db=new BusinessDatabase("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE","reader","password");
        // 组合真实 Text2SQL、数据库工具和临时目录下的 Excel 导出器。
        var data=new DataTools(new Text2SqlService(db,llm,new ExcelExporter(temp.resolve("exports"))));
        // 创建真实会话文件存储，写入临时 sessions 目录。
        var store=new PersistentConversationStore(temp.resolve("sessions"));
        // 模拟企业配置并开启 planningEnabled，让 MULTI_TASK 走计划执行。
        var config=mock(EnterpriseProperties.class);when(config.planningEnabled()).thenReturn(true);
        // 使用完整生产构造器装配 Agent、工具、计划、模型替身和持久化存储。
        var assistant=new SalesAssistant(retriever,models,mcp,mapper,store,planner,llm,data,config);
        // 执行端到端断言后在 finally 停止本地模型服务。
        try {
            // 以固定会话 ID 提交知识与统计组成的复合问题。
            var result=assistant.chat(new ChatRequest("one","先介绍产品A，再统计华东销售额"));
            // 断言结果只保留两条真实来源，伪造的 invented 已过滤。
            assertEquals(List.of("products.md","business://products-sales"),result.sources());
            // 断言执行步骤确实记录数据库工具调用，而非仅生成统计文字。
            assertTrue(result.steps().stream().anyMatch(s->s.contains("queryBusinessData")));
            // 断言评测上下文包含真实 H2 计算出的 3770，证明工具证据被收集。
            assertTrue(result.retrievedContexts().stream().anyMatch(s->s.contains("3770")));
            // 重新读取会话文件，断言已保存用户和助手两条消息。
            assertEquals(2,new PersistentConversationStore(temp.resolve("sessions")).load("one").turns().size());
            // 断言导出目录恰好生成一个 Excel 文件，并自动关闭目录流。
            try(var files=java.nio.file.Files.list(temp.resolve("exports"))){assertEquals(1,files.count());}
        // 无论断言成败都停止 HTTP 模型替身，避免端口占用。
        }finally {server.stop(0);}
    }
}
