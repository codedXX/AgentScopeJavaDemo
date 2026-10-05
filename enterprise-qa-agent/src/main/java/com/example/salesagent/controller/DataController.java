// 将业务数据查询和导出下载入口归入 API 包。
package com.example.salesagent.controller;

// 导入企业配置，读取 Excel 导出文件所在目录。
import com.example.salesagent.config.EnterpriseProperties;
// 导入自然语言转 SQL、只读执行和导出服务。
import com.example.salesagent.sql.Text2SqlService;
// 导入敏感问题守卫，在调用模型前拒绝敏感请求。
import com.example.salesagent.agent.SensitiveQuestionGuard;
// 导入按环境启用控制器的注解。
import org.springframework.context.annotation.Profile;
// 导入文件资源包装，供 Spring 输出磁盘文件。
import org.springframework.core.io.FileSystemResource;
// 导入响应构造器、内容类型和下载头常量。
import org.springframework.http.*;
// 导入 HTTP 路由、JSON 正文和路径变量绑定注解。
import org.springframework.web.bind.annotation.*;
// 导入 UUID，规范化导出 ID 并限制文件定位输入。
import java.util.UUID;

// 将查询结果输出为 JSON，并允许下载方法返回二进制文件。
@RestController
// 仅在主问答应用环境注册查询和下载入口。
@Profile("app")
// 提供 Text2SQL 查询与 XLSX 下载接口。
public class DataController {
    // question 是业务自然语言问题；export 控制是否同时生成 Excel。
    public record Request(String question, boolean export) {}
    // 保存查询服务，控制器不直接执行 JDBC。
    private final Text2SqlService sql;
    // 保存导出目录等企业配置。
    private final EnterpriseProperties config;

    // 通过构造器注入查询服务和文件目录配置。
    public DataController(Text2SqlService sql, EnterpriseProperties config) {
        // 保存 Text2SQL 服务实例。
        this.sql = sql;
        // 保存配置实例，用于下载路径解析。
        this.config = config;
    }

    // POST 请求的 JSON 正文包含问题和导出选项。
    @PostMapping("/api/sql/query")
    // 返回 SQL、列名、行数据、截断标志、来源和可选导出地址。
    public Text2SqlService.Result query(@RequestBody Request request) {
        // 在调用敏感问题守卫前处理缺失问题。
        if (request.question() == null)
            // 参数错误会由异常处理器转换为 HTTP 400。
            throw new IllegalArgumentException("缺少问题");
        // 获取敏感意图检查的拒绝原因；正常问题返回 null。
        String rejection = new SensitiveQuestionGuard().rejection(request.question());
        // 只要存在拒绝原因，就不继续模型生成和数据库查询。
        if (rejection != null)
            // 将拒绝原因交给统一异常处理器。
            throw new IllegalArgumentException(rejection);
        // 让服务生成并校验 SQL，再执行查询及按需导出。
        return sql.query(request.question(), request.export());
    }

    // GET 根据导出 ID 下载已生成的 XLSX 文件。
    @GetMapping("/api/sql/exports/{id}")
    // 用 ResponseEntity 显式控制下载状态、媒体类型和响应头。
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        // 解析并重新输出 UUID，避免原始路径片段参与文件路径拼接。
        String canonical = UUID.fromString(id).toString();
        // 在固定导出目录中定位此 UUID 对应的 Excel 文件。
        var path = config.exportDir().resolve(canonical + ".xlsx");
        // 只有普通文件才允许下载，缺失文件返回 404。
        if (!java.nio.file.Files.isRegularFile(path))
            // 不向客户端暴露服务端磁盘路径。
            return ResponseEntity.notFound().build();
        // 使用 XLSX 的标准 MIME 类型，便于浏览器和表格软件识别。
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            // 指定附件下载文件名，再交给 Spring 输出文件内容。
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=business-" + canonical + ".xlsx").body(new FileSystemResource(path));
    }
}
