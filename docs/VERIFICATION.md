# 验证记录

验证环境：Windows、Java 21.0.2、Maven 3.9.14，日期 2026-09-22。

## 已完成

| 检查 | 结果 |
|---|---|
| 完整 Maven 构建 | `BUILD SUCCESS`，已生成可运行 JAR |
| 默认自动化测试 | 22 项，0 失败、0 错误、0 跳过 |
| 递归切分、中文 BM25、候选去重与重排映射 | 通过 |
| 入库发布、失败未就绪、启动清单一致性 | 通过（受控存储测试） |
| 会话隔离、追问历史、跨会话初始化不阻塞 | 通过 |
| AgentScope HTTP 与结构化输出 | 通过本地模型替身验证 |
| Qwen3.7 多模态端点与 /api/v1 拼接 | 已核对官方文档，HTTP 请求路径回归通过 |
| 真实 MCP Streamable HTTP | 工具发现、错误调用、业务 HTTP 查询通过 |
| 来源校验与 MCP 故障时的知识回答 | 通过 |
| 可运行 JAR 启动 | app 和 mcp-server 分别在临时本机端口成功启动 |
| REST 状态与输入校验 | 知识库未就绪状态 200、空问题 400、未知 SKU 404（回归测试） |
| 指定 GitHub 仓库实际读取 | 已通过打包后的 MCP 服务读取 36 个文件路径和 README |
| 依赖检查 | AgentScope 1.0.12、MCP 0.17.0；没有 Spring AI / Spring AI Alibaba |
| Docker Compose 静态配置 | 通过 |
| Milvus 2.6.6 真实容器测试 | 写入、强一致性向量检索、重建清空通过；随机测试 collection 已清理 |
| PowerShell 评测脚本 | 语法解析通过，21 条案例文件可解析 |
| 独立代码审查 | 已处理关键发现，复查未发现剩余重要问题 |

GitHub 本次读取固定提交：`ac6c04243afb25ace62aaeb74042831c007a8a64`。

README 实际来源：[指定仓库 README](https://github.com/codedXX/redis-cache-demo/blob/ac6c04243afb25ace62aaeb74042831c007a8a64/README.md)，本次读取 2118 字符。

## 外部服务验证

- 百炼：环境没有 `DASHSCOPE_API_KEY`，`BailianLiveIT` 明确跳过。未声称三个模型已在该账号上调用成功；需要用户配置 Key 后运行 `mvn -Plive-it verify`。
- Milvus：真实 2.6.6 容器集成测试通过，`MilvusLiveIT` 1 项成功。测试使用独立随机 collection，未改动既有项目的数据。`sales-agent-demo` 的 etcd、MinIO、Milvus 栈保持运行，便于后续配置 Key 并入库。
- 正确率与性能：尚未使用真实模型跑标注评测，因此没有 85% 答准率或 2 秒平均检索的实测结论。

## 已复现并修复的问题

1. AgentScope 与百炼 SDK 对 base URL 前缀的约定不同，导致重复 `/api/v1`。已通过失败回归测试确认，再修复。
2. Qwen3.7-Flash 需显式使用多模态端点。已更新配置和 HTTP 路径回归。
3. 新会话工厂在全局锁内进行初始化，会阻塞已有会话。已通过并发回归测试修复。
4. MCP 服务不可用会阻断已有 RAG 证据的回答。改为条件降级并通过完整 AgentScope 结构化输出测试验证。
5. 通用异常处理将未知 SKU 的 404 变成 502。已从打包服务复现并加入回归修复。
6. 原 Milvus 模板中的 `minio/minio` Docker Hub 镜像已不可拉取。改用同版本官方 Quay 镜像，镜像下载已验证成功。
7. 本机已有其他项目的 Milvus 容器名和 MinIO 9000/9001 端口占用。Compose 改用项目隔离名称，MinIO 仅内部访问，不修改既有容器。

## 如何重跑

最终结果（2026-09-22 15:33 +08:00）：`BUILD SUCCESS`；22 项默认测试 + 1 项真实 Milvus 测试通过；1 项真实百炼测试因无 Key 跳过。没有其他跳过项或失败项。

```powershell
mvn test
$env:MILVUS_URI = "http://localhost:19530"
mvn -Plive-it verify
```

真实 Milvus 测试需要显式设置 `MILVUS_URI=http://localhost:19530`；真实百炼测试需要设置 `DASHSCOPE_API_KEY`。缺少变量时对应 live-it 测试标记 skipped，不会用假实现冒充外部服务通过。

本次沙箱为了避开本机全局 Maven 镜像配置和 Windows 临时目录权限，使用 `.work/maven-settings.xml` 与工作区 `.work/test-tmp` 执行构建；这些是本地验证辅助文件，不是运行应用的必要配置。测试报告位于 `target/surefire-reports` 和 `target/failsafe-reports`。

本次用于 JAR 冒烟测试的 18080/18081 Java 进程已停止。Milvus 通过 `docker compose stop` 可暂停，使用 `docker compose up -d` 恢复；不要使用 `down -v`，除非明确希望删除本 Demo 的索引存储卷。
