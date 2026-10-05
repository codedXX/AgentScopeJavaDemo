// 企业扩展配置集中放在 config 包。
package com.example.salesagent.config;
// 使用 Path 表示父文档、会话和导出目录的位置。
import java.nio.file.Path;
// 导入配置属性绑定注解，供 Spring 自动构造不可变配置记录。
import org.springframework.boot.context.properties.ConfigurationProperties;
// 绑定 enterprise 配置组中的所有扩展功能参数。
@ConfigurationProperties("enterprise")
// 聚合向量后端、父子检索、会话、数据查询与任务编排配置。
public record EnterpriseProperties(
        // 选择 milvus、pgvector 或 faiss 后端。
        String vectorBackend,
        // PostgreSQL/PGVector 的 JDBC 地址。
        String pgUrl,
        // PostgreSQL 连接用户名。
        String pgUser,
        // PostgreSQL 连接密码。
        String pgPassword,
        // FAISS Python HTTP 服务的基础地址。
        String faissUrl,
        // 保存父文档映射的 JSON 文件路径。
        Path parentFile,
        // 父文档分块目标字符数，通常大于子块。
        int parentSize,
        // RRF 公式中的平滑常数 k，降低高排名的极端影响。
        int rrfK,
        // RRF 融合后送入重排的候选上限。
        int fusionTopK,
        // 是否生成 HyDE 假设文档来扩展向量查询。
        boolean hydeEnabled,
        // 检索缓存有效期秒数。
        int cacheSeconds,
        // 持久化会话记录所在目录。
        Path sessionDir,
        // 查询结果 Excel 文件的输出目录。
        Path exportDir,
        // 只读业务查询使用的 JDBC 地址。
        String businessJdbcUrl,
        // 业务数据库连接用户名。
        String businessUser,
        // 业务数据库连接密码。
        String businessPassword,
        // 是否对 MULTI_TASK 请求执行任务计划。
        boolean planningEnabled,
        // 是否按段落语义相似度划分父文档。
        boolean semanticChunkingEnabled,
        // 语义分块判定主题边界使用的相似度阈值。
        double semanticThreshold) {}
