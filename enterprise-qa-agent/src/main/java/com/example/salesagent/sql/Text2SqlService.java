// 将自然语言业务查询、JDBC 执行和导出编排归入 SQL 包。
package com.example.salesagent.sql;

// 导入统一模型网关，使用结构化输出获取 SQL。
import com.example.salesagent.agent.LlmGateway;
// 导入 JDBC 查询结果、元数据和数据库异常类型。
import java.sql.*;
// 导入列表与集合实现，保存列名和查询行。
import java.util.*;

// 将模型生成的 SQL 经过只读校验后执行，并按需生成 Excel。
public class Text2SqlService {
    // 将模型输出限定为一个 sql 字段，便于结构化解析。
    public record GeneratedSql(String sql) {}
    // 统一返回实际 SQL、列名、数据、截断标志、演示属性、来源及可选下载地址。
    public record Result(String sql, List<String> columns, List<List<Object>> rows, boolean truncated, boolean demo, String source, String exportUrl) {}
    // 保存业务数据库连接与表结构服务。
    private final BusinessDatabase database;
    // 保存负责生成只读 SQL 的模型网关。
    private final LlmGateway llm;
    // 保存生成标准 XLSX 文件的导出器。
    private final ExcelExporter excel;

    // 注入数据库、模型和导出依赖，便于运行配置与测试替身复用。
    public Text2SqlService(BusinessDatabase database, LlmGateway llm, ExcelExporter excel) {
        // 保留业务数据库引用。
        this.database = database;
        // 保留模型网关引用。
        this.llm = llm;
        // 保留 Excel 导出器引用。
        this.excel = excel;
    }

    // 接收自然语言问题，并用 export 决定是否同时保存查询结果。
    public Result query(String question, boolean export) {
        // 拒绝缺失、空白或超过 2000 字符的问题。
        if (question == null || question.isBlank() || question.length() > 2000)
            // 参数错误直接返回给 API 或工具层，不调用模型。
            throw new IllegalArgumentException("查询问题不合法");
        // 将允许的表结构与禁止项作为系统约束，要求模型返回 GeneratedSql。
        var generated = llm.structured(database.schema() + "将自然语言转为sql；不允许子查询、CTE、写操作或其他表，不确定时返回空sql。", question, GeneratedSql.class);
        // 模型输出仍需交给独立校验器检查，不能直接信任并执行。
        return execute(generated.sql(), export);
    }

    // 执行一个通过白名单校验的 SELECT，并最多返回 200 行。
    public Result execute(String input, boolean export) {
        // 解析 SQL AST，检查语句、表与函数白名单，得到规范化 SQL。
        String sql = ReadOnlySqlValidator.validate(input);
        // 使用受限数据库账号；查询结束后自动关闭 Statement 和 Connection。
        try (var c = database.readConnection(); var statement = c.createStatement()) {
            // 向 JDBC 驱动声明只读意图，配合数据库账号 SELECT 权限限制写入。
            c.setReadOnly(true);
            // 设置 3 秒查询超时，防止耗时 SQL 长时间占用连接。
            statement.setQueryTimeout(3);
            // 多读取一行，用第 201 行判断返回的 200 行是否被截断。
            statement.setMaxRows(201);
            // 按数据库返回顺序记录列名或列别名。
            List<String> columns = new ArrayList<>();
            // 保存已读取的二维行数据，供 JSON 响应和 Excel 导出复用。
            List<List<Object>> rows = new ArrayList<>();
            // 初始假设结果完整，超过 200 行时改为 true。
            boolean truncated = false;
            // 执行 SELECT，并在离开作用域时关闭 ResultSet。
            try (var result = statement.executeQuery(sql)) {
                // 获取列元数据，列下标遵循 JDBC 的 1 起始规则。
                var metadata = result.getMetaData();
                // 遍历结果中的每一列，支持 SELECT 别名和聚合字段。
                for (int i = 1; i <= metadata.getColumnCount(); i++)
                    // 使用列标签而非原始字段名，让 AS 别名出现在响应中。
                    columns.add(metadata.getColumnLabel(i));
                // 每次 next 将游标推进到下一条结果行。
                while (result.next()) {
                    // 当前已有 200 行且还能读到下一行，说明必须截断。
                    if (rows.size() >= 200) {
                        // 向调用方标记结果并非完整数据集。
                        truncated = true;
                        // 停止读取多余行，控制内存和响应大小。
                        break;
                    }
                    // 为当前数据库行创建按列顺序排列的值列表。
                    var row = new ArrayList<Object>();
                    // 从 JDBC 的第 1 列读取到最后一列。
                    for (int i = 1; i <= columns.size(); i++) {
                        // 让驱动返回列值对应的 Java 类型。
                        Object value = result.getObject(i);
                        // 保留 null、数值和布尔值，日期等其他对象转换成可序列化文本。
                        row.add(value == null || value instanceof Number || value instanceof Boolean ? value : value.toString());
                    }
                    // 一条数据库行读取完整后再加入返回结果。
                    rows.add(row);
                }
            }
            // 标明模拟业务来源；仅当 export 为 true 时执行文件导出。
            return new Result(sql, columns, rows, truncated, true, "business://products-sales", export ? excel.export(columns, rows) : null);
        // 将数据库错误包装为业务可读错误，保留 cause 供服务端诊断。
        } catch (SQLException e) {
            // 提示检查字段、筛选条件和数据库连接，而不泄露连接认证信息。
            throw new IllegalStateException("只读业务查询失败，请核对字段、条件或数据库连接", e);
        }
    }
}
