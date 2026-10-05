// 将演示业务数据库初始化和只读连接归入 SQL 包。
package com.example.salesagent.sql;

// 导入 JDBC 连接、驱动管理器、语句和数据库异常类型。
import java.sql.*;

// 统一维护连接参数、H2 演示数据和供模型使用的业务表结构。
public class BusinessDatabase {
    // 保存 JDBC 地址、只读用户名和密码，不在日志中输出认证内容。
    private final String url, user, password;

    // 接收已配置的数据库连接信息，并按需初始化 H2 演示库。
    public BusinessDatabase(String url, String user, String password) {
        // 保留 JDBC URL，用于判断是否需要初始化 H2。
        this.url = url;
        // 保留业务读取账号。
        this.user = user;
        // 保留读取账号密码，仅用于建立数据库连接。
        this.password = password;
        // 用户名将用于初始化 DDL，因此只接受有限长度的安全标识符。
        if (!user.matches("[a-zA-Z][a-zA-Z0-9_]{0,40}"))
            // 拒绝可能影响 DDL 结构的用户名。
            throw new IllegalArgumentException("业务数据库用户名格式错误");
        // 外部数据库由使用者准备；仅为 H2 自动创建演示结构和数据。
        if (url.startsWith("jdbc:h2:"))
            // 初始化演示表，同时授予业务账号 SELECT 权限。
            seed();
    }

    // 用 H2 管理员连接执行建表、种子数据和只读账号配置。
    private void seed() {
        // try-with-resources 确保初始化结束时关闭 JDBC 语句和连接。
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            // 创建商品表，sku 是主键，金额使用定点 decimal，避免浮点金额误差。
            s.execute("CREATE TABLE IF NOT EXISTS products(sku varchar(40) PRIMARY KEY,name varchar(100),category varchar(40),price decimal(12,2),stock int)");
            // 创建销售表，记录地区、销量、金额和销售日期，供 JOIN 与统计查询使用。
            s.execute("CREATE TABLE IF NOT EXISTS sales(id int PRIMARY KEY,sku varchar(40),region varchar(40),quantity int,amount decimal(12,2),sold_at date)");
            // MERGE 按 sku 写入三件演示商品，重复启动不会产生重复主键行。
            s.execute("MERGE INTO products KEY(sku) VALUES('DEMO-A','演示蛋白营养粉','营养',199,120),('DEMO-B','演示维生素片','营养',89,80),('DEMO-C','演示办公礼盒','办公',299,15)");
            // 按销售 id 写入固定日期和地区的数据，让统计结果能够稳定复现。
            s.execute("MERGE INTO sales KEY(id) VALUES(1,'DEMO-A','华东',10,1990,'2026-09-01'),(2,'DEMO-A','华南',5,995,'2026-09-05'),(3,'DEMO-B','华东',20,1780,'2026-09-07'),(4,'DEMO-C','华北',3,897,'2026-09-08')");
            // 创建读取账号；密码中的单引号按 SQL 字符串规则转义。
            s.execute("CREATE USER IF NOT EXISTS " + user + " PASSWORD '" + password.replace("'", "''") + "'");
            // 每次初始化同步账号密码，使配置修改后连接仍可使用。
            s.execute("ALTER USER " + user + " SET PASSWORD '" + password.replace("'", "''") + "'");
            // 仅授权读取两张业务表，数据库权限与 SQL 校验形成两层限制。
            s.execute("GRANT SELECT ON products,sales TO " + user);
        // 将 JDBC 初始化失败包装成服务状态错误，同时保留原始异常链。
        } catch (SQLException e) {
            // 不在错误文案中暴露数据库认证参数。
            throw new IllegalStateException("初始化演示业务库失败", e);
        }
    }

    // 建立业务读取账号连接，连接关闭职责属于调用方。
    public Connection readConnection() throws SQLException {
        // H2 的 MODE/DB_CLOSE_DELAY 等初始化参数需要管理员权限，只在 seed 时设置。
        // 先使用完整 URL，外部数据库可以直接复用。
        String readerUrl = url;
        // H2 读取账号不能执行管理员初始化选项，需要清理连接参数。
        if (url.startsWith("jdbc:h2:")) {
            // 用分号拆开数据库地址和连接选项。
            var parts = url.split(";");
            // 保留数据库自身地址，确保读写账号连接到同一个库。
            var builder = new StringBuilder(parts[0]);
            // 从第二段开始检查每一个 H2 选项。
            for (int i = 1; i < parts.length; i++)
                // 保留影响标识符大小写的选项，避免表名解析与管理员连接不同。
                if (parts[i].matches("(?i)(DATABASE_TO_LOWER|DATABASE_TO_UPPER|CASE_INSENSITIVE_IDENTIFIERS)=.*"))
                    // 重新拼接允许的参数，舍弃需要管理员权限的初始化选项。
                    builder.append(';').append(parts[i]);
            // 得到适合业务读取账号使用的 H2 URL。
            readerUrl = builder.toString();
        }
        // 使用受限账号创建连接，调用方再设置只读与超时控制。
        return DriverManager.getConnection(readerUrl, user, password);
    }

    // 为 Text2SQL 模型提供业务字段、关联关系和查询边界。
    public String schema() {
        // 根据实际 JDBC URL 提供方言，避免 MySQL 收到 PostgreSQL 专用语法。
        String dialect = url.startsWith("jdbc:mysql:") ? "只读MySQL兼容SQL。" : "只读H2/PostgreSQL兼容SQL。";
        // 列出允许查询的两张表及字段，避免模型猜测其他数据库对象。
        return dialect + "products(sku,name,category,price,stock)；sales(id,sku,region,quantity,amount,sold_at)。"
            // 明确 JOIN 关系、模拟数据属性、币种和 200 行结果上限。
            + "products.sku=sales.sku。price为单价，stock为当前库存，quantity为销量，amount为实际销售额，sold_at为销售日期。"
            + "演示数据，金额人民币；仅允许SELECT，可筛选、JOIN、GROUP BY统计，最多200行。"
            + "月份筛选使用日期范围，例如2026年9月使用sold_at >= '2026-09-01' AND sold_at < '2026-10-01'。"
            + (url.startsWith("jdbc:mysql:") ? "按月汇总可使用DATE_FORMAT(sold_at,'%Y-%m')；也允许YEAR、MONTH、DAY、DATE。" : "");
    }
}
