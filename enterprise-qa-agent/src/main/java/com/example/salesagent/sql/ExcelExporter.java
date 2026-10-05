// 将业务查询结果的 XLSX 文件生成归入 SQL 包。
package com.example.salesagent.sql;

// 导入路径和文件 API，用于创建导出目录与输出文件。
import java.nio.file.*;
// 导入明确的 UTF-8 编码，保证中文列名和内容正常写入 XML。
import java.nio.charset.StandardCharsets;
// 导入 UUID 和列表实现，构造唯一文件名与表格行集合。
import java.util.*;
// 导入 ZIP 流和条目，XLSX 本质上由多个 XML 文件打包而成。
import java.util.zip.*;

/** 生成标准 XLSX；字符串写 inlineStr，避免把数据当成公式执行。 */
// 无外部表格库地生成一个包含查询结果的最小 XLSX 工作簿。
public final class ExcelExporter {
    // 保存统一导出目录，下载控制器使用同一个目录定位文件。
    private final Path dir;

    // 接收导出目录，目录实际创建延迟到第一次导出。
    public ExcelExporter(Path dir) {
        // 保存配置传入的目录路径。
        this.dir = dir;
    }

    // 将列名和二维数据写入 XLSX，并返回 HTTP 下载地址。
    public String export(List<String> columns, List<List<Object>> rows) {
        // 为文件分配 UUID，避免多个查询导出互相覆盖。
        String id = UUID.randomUUID().toString();
        // 将文件创建和 XML 写入失败统一包装为导出服务错误。
        try {
            // 创建导出目录；已存在的目录不会被清空。
            Files.createDirectories(dir);
            // 新建 UUID 对应的 XLSX ZIP 包，离开作用域时自动结束并关闭输出流。
            try (var zip = new ZipOutputStream(Files.newOutputStream(dir.resolve(id + ".xlsx")))) {
                // 声明 ZIP 中 rels、xml、工作簿和工作表的 OpenXML 内容类型。
                entry(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
                // 根关系文件通过 rId1 把整个文档包关联到 xl/workbook.xml。
                entry(zip, "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
                // 工作簿定义一个名为“查询结果”的工作表，关系标识使用 rId1。
                entry(zip, "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"查询结果\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
                // 工作簿关系文件把 rId1 对应到 worksheets/sheet1.xml。
                entry(zip, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
                // 开始工作表 XML，并在 sheetData 元素中保存全部单元格。
                var xml = new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
                // 用通配符列表同时容纳字符串列头和任意类型的数据行。
                var all = new ArrayList<List<?>>();
                // 第一行写列名，让导出结果可直接阅读。
                all.add(columns);
                // 随后写入查询返回的各条业务数据。
                all.addAll(rows);
                // OpenXML 行号从 1 开始。
                int n = 1;
                // 按列表顺序生成列头和数据行。
                for (var row : all) {
                    // 为当前行写入 r 属性，并把行号推进到下一行。
                    xml.append("<row r=\"").append(n++).append("\">");
                    // 单元格按行内顺序写入，保持与查询列顺序一致。
                    for (Object value : row) {
                        // 数值直接用 v 元素写入，保留表格软件的数值计算能力。
                        if (value instanceof Number)
                            // 未设置 t 类型时，Excel 将此单元格作为数值解析。
                            xml.append("<c><v>").append(value).append("</v></c>");
                        // 其他类型统一写为内联字符串，包括以等号开头的潜在公式文本。
                        else
                            // inlineStr 不生成公式 f 元素；空值转为空文本，并转义 XML 特殊字符。
                            xml.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(value == null ? "" : value.toString())).append("</t></is></c>");
                    }
                    // 所有单元格写完后闭合当前行。
                    xml.append("</row>");
                }
                // 闭合数据区域和工作表根元素。
                xml.append("</sheetData></worksheet>");
                // 把完成的工作表 XML 写入约定的 ZIP 路径。
                entry(zip, "xl/worksheets/sheet1.xml", xml.toString());
            }
            // 返回文件 ID 路由，而不向调用方暴露磁盘位置。
            return "/api/sql/exports/" + id;
        // 捕获文件和 ZIP 写入过程中的失败。
        } catch (Exception e) {
            // 给 API 和工具层提供固定错误文本，并保留原始异常供诊断。
            throw new IllegalStateException("Excel导出失败", e);
        }
    }

    // 清理 XML 1.0 不允许的控制字符，并编码文本中的特殊字符。
    private static String escape(String text) {
        // 先移除非法控制字符，再按顺序转义 &、<、>、双引号，避免二次破坏实体。
        return text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // 向 XLSX ZIP 中写入一个 UTF-8 XML 文件；I/O 错误交给 export 统一处理。
    private static void entry(ZipOutputStream zip, String name, String data) throws java.io.IOException {
        // 为指定的 OpenXML 文件路径创建 ZIP 条目。
        zip.putNextEntry(new ZipEntry(name));
        // 使用 UTF-8 写入完整 XML 内容，保证中文数据一致。
        zip.write(data.getBytes(StandardCharsets.UTF_8));
        // 结束此条目，允许继续写下一个 XML 文件。
        zip.closeEntry();
    }
}
