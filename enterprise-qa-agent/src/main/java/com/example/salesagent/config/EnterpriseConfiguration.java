// 企业功能 Bean 的装配类归入 config 包。
package com.example.salesagent.config;
// 引入持久化会话、任务规划和模型调用适配器。
import com.example.salesagent.agent.*;
// 引入业务数据库、Text2SQL 和 Excel 导出组件。
import com.example.salesagent.sql.*;
// DataTools 将受限数据库查询暴露为 AgentScope 可调用工具。
import com.example.salesagent.tool.DataTools;
// 导入配置类、Bean 工厂方法与 profile 注解。
import org.springframework.context.annotation.*;
// 声明此类提供 Spring Bean 定义。
@Configuration
// 仅在问答应用 profile 中装配这些业务组件。
@Profile("app")
// 装配会话持久化、任务规划、业务查询和导出所需的企业组件。
public class EnterpriseConfiguration {
    @Bean
    com.example.salesagent.attachment.ImageAttachmentStore images(
            @org.springframework.beans.factory.annotation.Value("${enterprise.image-dir:./data/images}") String directory) {
        return new com.example.salesagent.attachment.ImageAttachmentStore(java.nio.file.Path.of(directory));
    }
    // 注册持久化会话存储，路径来自 enterprise.session-dir。
    @Bean
    // 会话存储工厂通过企业配置取得持久化目录。
    PersistentConversationStore conversations(EnterpriseProperties e) {
        // 创建按会话 ID 哈希保存 JSON 的文件存储。
        return new PersistentConversationStore(e.sessionDir());
    }
    // 注册敏感请求检查器，供 Spring 依赖注入使用。
    @Bean
    // 敏感问题检查器工厂，不依赖外部服务。
    SensitiveQuestionGuard guard() {
        // 构造使用固定规则匹配敏感请求的检查器。
        return new SensitiveQuestionGuard();
    }
    // 注册由统一 LLM 网关驱动的任务规划器。
    @Bean
    // 规划器工厂复用统一结构化生成网关。
    TaskPlanner planner(LlmGateway llm) {
        // 将模型网关注入规划器，负责生成受验证的只读步骤。
        return new TaskPlanner(llm);
    }
    // 注册用于示例业务数据查询的数据库组件。
    @Bean
    // 业务数据库工厂接收 JDBC 连接配置。
    BusinessDatabase businessDatabase(EnterpriseProperties e) {
        // 根据 JDBC 地址、用户名和密码初始化业务数据连接组件。
        return new BusinessDatabase(e.businessJdbcUrl(),e.businessUser(),e.businessPassword());
    }
    // 注册 Excel 导出器。
    @Bean
    // 导出器工厂接收统一的 Excel 输出目录。
    ExcelExporter excel(EnterpriseProperties e) {
        // 将导出目录注入组件，所有生成文件由该组件统一保存。
        return new ExcelExporter(e.exportDir());
    }
    // 注册自然语言到受限只读 SQL 的查询服务。
    @Bean
    // Text2SQL 工厂注入数据库、模型与导出三项依赖。
    Text2SqlService text2sql(BusinessDatabase db,LlmGateway llm,ExcelExporter excel) {
        // 组合数据库、模型生成与 Excel 输出能力。
        return new Text2SqlService(db,llm,excel);
    }
    // 注册供 ReAct Agent 使用的数据库工具。
    @Bean
    // 本地数据工具工厂将查询服务暴露给 AgentScope。
    DataTools dataTools(Text2SqlService sql) {
        // 将 Text2SQL 服务包装为带参数定义的 Function Calling 工具。
        return new DataTools(sql);
    }
}
