// 将 SQL 的安全边界校验归入 SQL 包。
package com.example.salesagent.sql;

// 导入 SQL 解析入口，将输入转为可检查的语法树。
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
// 导入 SELECT 语法树节点，用于限制查询结构。
import net.sf.jsqlparser.statement.select.*;
// 导入表名提取器，检查所有参与查询的表。
import net.sf.jsqlparser.util.TablesNamesFinder;
// 导入表达式、函数节点和访问器，递归检查函数调用。
import net.sf.jsqlparser.expression.*;
// 导入白名单集合和与区域无关的大小写转换工具。
import java.util.*;

/** AST 限制单条 SELECT、固定表和纯计算函数，数据库账号还只拥有 SELECT 权限。 */
// final 禁止通过继承绕开这个独立校验入口。
public final class ReadOnlySqlValidator {
    // 只允许访问商品表和销售表，禁止系统表或任意外部表。
    private static final Set<String> TABLES = Set.of("products", "sales");
    // 只允许聚合和纯计算函数，避免读文件、写文件或调用数据库扩展函数。
    private static final Set<String> FUNCTIONS = Set.of("count", "sum", "avg", "min", "max", "round", "abs", "coalesce", "lower", "upper", "length");
    // 私有构造器表明这是无实例状态的校验工具类。
    private ReadOnlySqlValidator() {}

    // 返回经过 AST 规范化的 SELECT，非法 SQL 抛出参数异常。
    public static String validate(String sql) {
        // 先限制长度，并拒绝分号与注释符，阻止多语句及注释混淆输入。
        if (sql == null || sql.length() > 8000 || sql.contains(";") || sql.contains("--") || sql.contains("/*")
            // SELECT 关键字只能出现一次，以排除当前实现不支持的嵌套查询。
            || java.util.regex.Pattern.compile("(?i)\\b(select)\\b").matcher(sql).results().count() != 1)
            // 在进入 AST 解析前明确拒绝不满足基础约束的输入。
            throw new IllegalArgumentException("仅支持单条不含注释或子查询的SELECT");
        // 将语法错误统一转换为调用方可识别的参数错误。
        try {
            // 用官方解析器构建 SQL AST，不靠字符串前缀判断语句类型。
            var statement = CCJSqlParserUtil.parse(sql);
            // 仅接受 SELECT 且查询主体必须是普通 PlainSelect。
            if (!(statement instanceof Select select) || !(select.getSelectBody() instanceof PlainSelect plain)
                // 拒绝 WITH 公用表表达式，避免隐藏额外查询结构。
                || select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()
                // 拒绝 SELECT INTO 和 FOR UPDATE 等写入或锁定语义。
                || plain.getIntoTables() != null && !plain.getIntoTables().isEmpty() || plain.getForMode() != null)
                // 不允许把其他 SELECT 变体当成只读普通查询。
                throw new IllegalArgumentException("仅允许只读普通SELECT");
            // 从整棵语法树提取涉及的表名。
            var tables = new TablesNamesFinder().getTableList(statement);
            // 要求至少有业务表，并且每个表名都在允许集合中。
            if (tables.isEmpty() || tables.stream().anyMatch(t -> !TABLES.contains(t.toLowerCase(Locale.ROOT))))
                // 阻止访问 information_schema 等非业务对象。
                throw new IllegalArgumentException("只能查询products、sales业务表");
            // 创建表达式访问器，检查 SELECT 各位置上的函数调用。
            var functions = new ExpressionVisitorAdapter() {
                // 覆盖函数节点的访问逻辑，并保留父类递归行为。
                @Override
                // 每遇到一个 SQL 函数，都验证其名称是否属于白名单。
                public void visit(Function function) {
                    // 转成与系统区域无关的小写形式后检查函数名称。
                    if (!FUNCTIONS.contains(function.getName().toLowerCase(Locale.ROOT)))
                        // 拒绝 FILE_READ、CSVWRITE 等有副作用或外部访问能力的函数。
                        throw new IllegalArgumentException("不允许该SQL函数");
                    // 继续检查函数参数中可能嵌套的其他函数表达式。
                    super.visit(function);
                }
            };
            // 遍历投影列，检查 SELECT 列表里的聚合及计算函数。
            for (var item : plain.getSelectItems())
                // 将每个列表达式交给同一个白名单访问器。
                item.getExpression().accept(functions);
            // WHERE 可能包含函数调用，存在时也必须检查。
            if (plain.getWhere() != null)
                // 遍历筛选表达式的函数节点。
                plain.getWhere().accept(functions);
            // HAVING 用于聚合后筛选，其函数限制与其他位置一致。
            if (plain.getHaving() != null)
                // 检查聚合筛选表达式。
                plain.getHaving().accept(functions);
            // ORDER BY 可能使用计算表达式，不能仅检查投影列。
            if (plain.getOrderByElements() != null)
                // 检查每个排序表达式中的函数。
                plain.getOrderByElements().forEach(e -> e.getExpression().accept(functions));
            // GROUP BY 对象和表达式列表都存在时才进行访问。
            if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressionList() != null)
                // 将分组项转换为 Expression，并递归检查函数。
                plain.getGroupBy().getGroupByExpressionList().forEach(e -> ((Expression) e).accept(functions));
            // JOIN 的 ON 条件同样可能调用函数，需要覆盖该入口。
            if (plain.getJoins() != null)
                // 逐个检查连接条件，未提供 ON 条件的连接直接跳过。
                plain.getJoins().forEach(j -> {
                    // 仅在 ON 表达式集合存在时遍历。
                    if (j.getOnExpressions() != null)
                        // 检查每个 ON 条件中的函数调用。
                        j.getOnExpressions().forEach(e -> e.accept(functions));
                });
            // 排除会改变事务或写文件的扩展语法，AST 解析后再作防御检查。
            // 不区分大小写、允许跨行匹配，检查可能产生副作用的关键字。
            if (sql.matches("(?is).*\\b(INTO|OUTFILE|CALL|SET|SCRIPT|RUNSCRIPT|FOR\\s+UPDATE|NEXT\\s+VALUE)\\b.*"))
                // 拒绝写入、脚本执行、事务锁定和序列取值等扩展操作。
                throw new IllegalArgumentException("SQL包含非只读操作");
            // 使用 AST 重新生成 SQL，确保执行的是被检查过的普通 SELECT。
            return plain.toString();
        // 已给出明确原因的校验异常保持原样。
        } catch (IllegalArgumentException e) {
            // 保留白名单或结构限制的错误说明。
            throw e;
        // 解析器等其他异常统一归为 SQL 解析失败。
        } catch (Exception e) {
            // 保留 cause，但向调用方提供固定的可读错误。
            throw new IllegalArgumentException("SQL解析失败", e);
        }
    }
}
