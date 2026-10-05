import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** 用本地管理员配置导入可重复执行的演示 SQL，不在输出中显示密码。 */
public class MySqlDemoSetup {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("需要管理配置文件和 SQL 文件路径");
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        String url = config.getProperty("jdbc.url");
        String user = config.getProperty("username");
        if (url == null || !url.startsWith("jdbc:mysql:") || user == null || user.isBlank()) {
            throw new IllegalArgumentException("请在 mysql-admin.properties 填写 MySQL JDBC 地址和管理账号");
        }
        // 只执行随项目提供的固定 SQL；文件不含存储过程或字符串内的分号。
        String sql = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)
            .replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
        try (var connection = DriverManager.getConnection(url, user, config.getProperty("password", ""));
             var statement = connection.createStatement()) {
            System.out.println("MySQL 版本：" + connection.getMetaData().getDatabaseProductVersion());
            for (String command : sql.split(";")) {
                if (!command.isBlank()) statement.execute(command.trim());
            }
        }
        // 使用应用的只读账号验证连接和数据，同时检查数据库层的写权限限制。
        String readerUrl = url.replaceFirst("/\\?", "/enterprise_qa_demo?");
        if (readerUrl.equals(url)) throw new IllegalArgumentException("管理 JDBC 地址应以 /? 开始连接选项，不指定数据库");
        try (var connection = DriverManager.getConnection(readerUrl, "enterprise_qa_reader", "local-reader-password");
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM products) products, COUNT(*) sales, SUM(amount) total FROM sales")) {
                result.next();
                System.out.printf("导入完成：商品 %d 件，销售记录 %d 条，总销售额 %s 元%n",
                    result.getInt("products"), result.getInt("sales"), result.getBigDecimal("total").toPlainString());
            }
            try {
                // 条件永远不成立；即使权限配置错误也不会更改任何数据。
                statement.executeUpdate("UPDATE products SET stock=stock WHERE 1=0");
                throw new IllegalStateException("业务账号拥有写入权限，请检查该账号现有授权");
            } catch (SQLException exception) {
                if (exception.getErrorCode() != 1142) throw exception;
                System.out.println("只读账号检查通过：数据库拒绝 UPDATE。");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("只读账号验证失败；若该账号之前已存在，请检查它的密码和授权。", exception);
        }
    }
}
