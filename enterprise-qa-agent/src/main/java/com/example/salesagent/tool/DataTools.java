// 将 Agent 可直接调用的业务数据工具归入工具包。
package com.example.salesagent.tool;

// 导入负责自然语言转 SQL、只读执行和 Excel 导出的服务。
import com.example.salesagent.sql.Text2SqlService;
// 导入 AgentScope 工具和参数描述注解。
import io.agentscope.core.tool.*;

// 把业务数据查询封装为 ReAct Agent 可发现的 Function Calling 工具。
public class DataTools {
    // 保存统一查询服务，避免在工具中重复实现 SQL 安全控制。
    private final Text2SqlService sql;

    // 接收应用配置注入的 Text2SQL 服务。
    public DataTools(Text2SqlService sql) {
        // 保存服务引用，供工具调用时使用。
        this.sql = sql;
    }

    // 向 Agent 注册工具名称和说明，提示可查询的表、导出能力及模拟数据属性。
    @Tool(name = "queryBusinessData", description = "根据自然语言只读查询products/sales，支持筛选、统计、Excel导出；返回模拟数据、来源和下载地址。")
    // question 参数要求具体业务问题，工具返回查询服务的标准结果结构。
    public Text2SqlService.Result query(@ToolParam(name = "question", description = "具体业务查询问题") String question,
        // export 参数告知工具是否需要额外生成 Excel 文件。
        @ToolParam(name = "export", description = "是否导出Excel") boolean export) {
        // 在进入模型和数据库前拒绝空问题以及获取系统密钥等敏感意图。
        if (question == null || new com.example.salesagent.agent.SensitiveQuestionGuard().rejection(question) != null)
            // 参数错误供 Agent 的工具错误处理流程识别。
            throw new IllegalArgumentException("敏感或无效问题");
        // 由统一服务完成生成、只读校验、查询及可选导出。
        return sql.query(question, export);
    }
}
