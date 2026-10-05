// 将自然语言转 SQL、只读限制和 Excel 导出测试归入 SQL 测试包。
package com.example.salesagent.sql;

// 导入模型网关接口，使用 Mockito 提供确定的 SQL 输出。
import com.example.salesagent.agent.LlmGateway;
// 导入 Path 等文件 API，读取临时导出文件。
import java.nio.file.*;
// 导入 UUID 和列表工具，为每次测试隔离数据库并构造导出数据。
import java.util.*;
// 导入 ZIP 读取器，直接验证 XLSX 包中的 XML 结构。
import java.util.zip.ZipFile;
// 导入 JUnit 测试注解。
import org.junit.jupiter.api.*;
// 导入临时目录注解，由 JUnit 自动管理测试导出文件。
import org.junit.jupiter.api.io.TempDir;
// 导入值、布尔、非空和异常断言。
import static org.junit.jupiter.api.Assertions.*;
// 导入模型替身与参数匹配辅助函数。
import static org.mockito.Mockito.*;

// 覆盖聚合、关联、筛选、数据库账号权限及公式注入防护。
class Text2SqlServiceTest {
    // 验证 MySQL 方言、反引号表名和时间汇总，并继续拒绝跨库查询与危险函数。
    @Test
    void supportsMysqlDialectAndQuotedBusinessTables() {
        var db = new BusinessDatabase("jdbc:mysql://localhost:3306/enterprise_qa_demo", "enterprise_qa_reader", "test-password");
        assertTrue(db.schema().contains("只读MySQL兼容SQL"));
        assertTrue(db.schema().contains("DATE_FORMAT"));
        assertFalse(db.schema().contains("PostgreSQL"));
        assertDoesNotThrow(() -> ReadOnlySqlValidator.validate("SELECT DATE_FORMAT(sold_at, '%Y-%m') AS month, SUM(amount) AS total FROM `sales` GROUP BY DATE_FORMAT(sold_at, '%Y-%m')"));
        assertDoesNotThrow(() -> ReadOnlySqlValidator.validate("SELECT YEAR(sold_at), MONTH(sold_at), DAY(sold_at) FROM `sales`"));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlValidator.validate("SELECT * FROM mysql.user"));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlValidator.validate("SELECT * FROM other_database.products"));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlValidator.validate("SELECT SLEEP(10) FROM `products`"));
    }
    // 为本测试创建独立导出目录。
    @TempDir
    // 保存 JUnit 分配的临时路径。
    Path temp;

    // 为每次调用创建独立 H2 演示业务库，避免测试之间互相修改状态。
    private BusinessDatabase database() {
        // 随机数据库名保证隔离；保留内存库并启用 PostgreSQL 模式及小写标识符。
        return new BusinessDatabase("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "reader", "test-password");
    }

    // 标记从模型生成到聚合、关联和 XLSX 导出的端到端本地测试。
    @Test
    // ZIP 文件读取及关闭可能抛出异常，由 JUnit 报告失败。
    void generatesExecutesJoinsAggregatesAndExportsWithoutFormulaInjection() throws Exception {
        // 不调用真实模型，而是模拟结构化 SQL 返回。
        var llm = mock(LlmGateway.class);
        // 问题“华东销售额”返回确定的分组求和 SQL，便于核对业务数据结果。
        when(llm.structured(anyString(), eq("华东销售额"), eq(Text2SqlService.GeneratedSql.class))).thenReturn(new Text2SqlService.GeneratedSql("SELECT region,SUM(amount) total FROM sales WHERE region='华东' GROUP BY region"));
        // 组合真实数据库、模型替身和临时目录导出器。
        var service = new Text2SqlService(database(), llm, new ExcelExporter(temp));
        // 执行自然语言查询，并要求生成 Excel。
        var result = service.query("华东销售额", true);
        // 验证华东两笔销售额 1990+1780 的精确 decimal 汇总结果。
        assertEquals("3770.00", result.rows().getFirst().get(1).toString());
        // 返回结果必须明确标记为演示数据。
        assertTrue(result.demo());
        // 此查询只有一行汇总，不应被标为超过 200 行的截断结果。
        assertFalse(result.truncated());
        // 从下载路由中提取 UUID，定位本次查询生成的 XLSX。
        String id = result.exportUrl().substring(result.exportUrl().lastIndexOf('/') + 1);
        // 打开 XLSX ZIP 包，完成检查后自动关闭文件。
        try (var zip = new ZipFile(temp.resolve(id + ".xlsx").toFile())) {
            // 验证工作簿描述 XML 存在，输出不能只是改了扩展名的普通文本。
            assertNotNull(zip.getEntry("xl/workbook.xml"));
            // 读取工作表 XML，使用 UTF-8 保留中文列名和数据。
            String xml = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            // 验证查询结果真实写入了工作表文件。
            assertTrue(xml.contains("3770.00"));
        }
        // 使用两张业务表进行 JOIN 和分组，覆盖允许的统计查询结构。
        var joined = service.execute("SELECT p.name,SUM(s.quantity) AS qty FROM products p JOIN sales s ON p.sku=s.sku GROUP BY p.name", false);
        // 三件演示商品都存在销售数据，因此应得到三个商品汇总行。
        assertEquals(3, joined.rows().size());
        // 导出以等号开头的危险字符串，验证它作为文本而非公式保存。
        String file = new ExcelExporter(temp).export(List.of("name"), List.of(List.of("=HYPERLINK(\"bad\")")));
        // 从下载地址提取文件 ID 并打开生成的 ZIP 文件。
        try (var zip = new ZipFile(temp.resolve(file.substring(file.lastIndexOf('/') + 1) + ".xlsx").toFile())) {
            // 读取单元格 XML，检查公式元素和单元格类型。
            String xml = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes());
            // 不得生成 f 元素，否则 Excel 可能执行数据中的公式。
            assertFalse(xml.contains("<f>"));
            // 必须使用 inlineStr，使公式形态的输入也保持普通字符串。
            assertTrue(xml.contains("inlineStr"));
        }
    }

    // 标记 SQL 校验与数据库账号权限的双层安全测试。
    @Test
    // 真实 JDBC 连接关闭可能抛出 SQLException。
    void rejectsUnsafeSqlAndDatabaseAccountCannotWrite() throws Exception {
        // 创建独立演示库及仅有 SELECT 权限的读取账号。
        var db = database();
        // 覆盖删除、多语句、系统表读取、文件读写等禁止行为。
        for (String sql : List.of("DELETE FROM products", "SELECT * FROM products; DROP TABLE sales", "SELECT * FROM information_schema.tables",
            // H2 的 FILE_READ 和 CSVWRITE 可访问文件，必须被函数白名单拒绝。
            "SELECT FILE_READ('/etc/passwd') FROM products", "SELECT CSVWRITE('out.csv','select * from products') FROM products",
            // 子查询、SELECT INTO 和 FOR UPDATE 也不属于当前只读普通 SELECT 范围。
            "SELECT * FROM products WHERE EXISTS(SELECT * FROM sales)", "SELECT * INTO copied FROM products", "SELECT * FROM products FOR UPDATE")) {
            // 每种危险输入都必须在 SQL 校验阶段失败，断言消息附带 SQL 便于定位。
            assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlValidator.validate(sql), sql);
        }
        // 直接使用数据库读取账号，绕过 SQL 校验器测试数据库自身权限。
        try (var c = db.readConnection(); var s = c.createStatement()) {
            // 即使应用层校验被绕过，读取账号也不能更新库存。
            assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE products SET stock=0"));
        }
    }

    // 标记允许的筛选、日期比较和排序行为测试。
    @Test
    // 直接 execute 不使用模型，因此可以注入 null 网关。
    void permitsFilteringDateAndOrdering() {
        // 使用真实只读数据库及导出器，聚焦 SQL 合法性和结果数量。
        var service = new Text2SqlService(database(), null, new ExcelExporter(temp));
        // 价格大于 100 的商品有两个，ORDER BY 应被只读校验允许。
        assertEquals(2, service.execute("SELECT sku,price FROM products WHERE price>100 ORDER BY price DESC", false).rows().size());
        // 四笔演示销售均在指定日期之后，验证日期筛选可正常执行。
        assertEquals(4, service.execute("SELECT * FROM sales WHERE sold_at >= '2026-09-01'", false).rows().size());
    }
}
