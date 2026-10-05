package com.example.salesagent.live;

import com.example.salesagent.agent.LlmGateway;
import com.example.salesagent.sql.BusinessDatabase;
import com.example.salesagent.sql.ExcelExporter;
import com.example.salesagent.sql.Text2SqlService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** 验证 mysql-demo.sql 导入后的真实数据库，不调用付费模型或修改业务数据。 */
class MySqlLiveIT {
    @TempDir Path exports;

    @Test void queriesJoinsMonthsExportsAndEnforcesReadOnlyPermissions() throws Exception {
        String url = System.getenv("MYSQL_IT_URL");
        assumeTrue(url != null && !url.isBlank(), "未设置 MYSQL_IT_URL，跳过真实 MySQL 测试");
        var database = new BusinessDatabase(url,
            System.getenv().getOrDefault("MYSQL_IT_USER", "enterprise_qa_reader"),
            System.getenv().getOrDefault("MYSQL_IT_PASSWORD", "local-reader-password"));
        var llm = mock(LlmGateway.class);
        when(llm.structured(anyString(), eq("总销售额"), eq(Text2SqlService.GeneratedSql.class)))
            .thenReturn(new Text2SqlService.GeneratedSql("SELECT SUM(amount) AS total FROM sales"));
        var service = new Text2SqlService(database, llm, new ExcelExporter(exports));

        // 精确总额可发现字符集、DECIMAL、连接或导入缺失问题。
        var total = service.query("总销售额", true);
        assertEquals(new BigDecimal("396382.00"), total.rows().getFirst().getFirst());
        assertFalse(total.truncated());
        String exportId = total.exportUrl().substring(total.exportUrl().lastIndexOf('/') + 1);
        try (var zip = new ZipFile(exports.resolve(exportId + ".xlsx").toFile())) {
            assertNotNull(zip.getEntry("xl/workbook.xml"));
        }

        // MySQL 日期函数、反引号、中文 JOIN 数据和结果上限均走实际执行路径。
        var monthly = service.execute("SELECT DATE_FORMAT(sold_at,'%Y-%m') AS sales_month, SUM(amount) AS total FROM `sales` GROUP BY DATE_FORMAT(sold_at,'%Y-%m') ORDER BY sales_month", false);
        assertEquals(6, monthly.rows().size());
        assertEquals("2026-04", monthly.rows().getFirst().getFirst());
        assertEquals("2026-09", monthly.rows().getLast().getFirst());
        var joined = service.execute("SELECT p.name, SUM(s.quantity) AS quantity FROM products p JOIN sales s ON p.sku=s.sku GROUP BY p.name ORDER BY p.name", false);
        assertEquals(12, joined.rows().size());
        assertTrue(joined.rows().stream().anyMatch(row -> row.getFirst().equals("演示蛋白营养粉")));
        var detail = service.execute("SELECT * FROM sales ORDER BY id", false);
        assertEquals(200, detail.rows().size());
        assertTrue(detail.truncated());
        var soldOut = service.execute("SELECT name FROM products WHERE stock=0", false);
        assertEquals("无线鼠标", soldOut.rows().getFirst().getFirst());

        // 零行 UPDATE 不修改数据，仍需被数据库授权拒绝。
        try (var connection = database.readConnection(); var statement = connection.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                () -> statement.executeUpdate("UPDATE products SET stock=stock WHERE 1=0"));
            assertEquals(1142, denied.getErrorCode());
        }
    }
}
