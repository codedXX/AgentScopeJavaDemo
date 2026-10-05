# 本次验证

验证日期：2026-10-04，时区 Asia/Shanghai。Windows，Java 21.0.5，Maven 3.9.16，Python 3.12.14。

## 2026-10-05 Milvus 镜像与真实服务验证

- 原 MinIO 镜像在 Quay 无法匿名访问，同版本 Docker Hub 清单查询也被拒绝；官方旧二进制下载地址返回 HTTP 410。
- `compose.yaml` 改用 Milvus 官方单容器脚本中的内置 etcd / 本地存储方式，镜像仍为 `milvusdb/milvus:v2.6.6`，本机端口仍为 19540 和 9092。
- `docker compose config --quiet`、`docker compose pull` 和 `docker compose up -d --wait --wait-timeout 240` 均成功；容器为 `healthy`，`http://127.0.0.1:9092/healthz` 返回 `OK`。
- 使用独立随机集合运行 `MilvusLiveIT`：真实建库、两条向量写入、计数、近邻检索、清空重建及测试集合清理通过。1 项测试，0 失败、0 错误、0 跳过。
- 测试命令：设置 `MILVUS_URI=http://127.0.0.1:19540` 后运行 `mvn -o -Dmaven.repo.local=.work/m2 -Dtest=MilvusLiveIT test`，日志位于本机 `.work/milvus-startup-test.log`。
- 真实百炼调用和知识库入库尚未验证，仍需配置用户自己的 API Key。

## 2026-10-05 注释补充验证

- 80 个 Java 文件及 20 个脚本、前端、配置和构建文件补充中文逐行说明。
- 对照修改前快照，Java 词法 token（包括字符串和文本块）、Python AST、前端内容、配置有效行及 POM XML 结构均保持一致；仅增加注释与调整排版空白。
- Maven `verify` 成功：50 项 Java 测试通过，0 失败、0 错误、0 跳过，并重新生成可运行 JAR。
- 3 项评测指标测试、2 项真实 FAISS 原生测试通过；前端 JavaScript 的 Node `--check` 通过。
- JSON/JSONL 数据保持合法格式，说明单独放在 [字段文档](../evaluation/FIELDS.md) 中。

## 已验证

| 项目 | 结果 |
|---|---|
| Maven `verify` | 成功，生成独立可运行 JAR |
| Java 默认测试 | 50 项通过，0 失败、0 错误、0 跳过 |
| Spring Boot app 完整启动 | 不配置模型 Key、不启动向量服务时也能启动，业务库可查询 |
| AgentScope ReAct/结构化输出 | 使用本地 HTTP 模型替身执行实际框架链路 |
| Plan-and-Execute | 知识检索后执行依赖 SQL，真实只读 H2 查询、来源过滤、Excel 导出、会话落盘通过 |
| MCP Streamable HTTP | 真实 HTTP 工具发现、七种工具注册、敏感生成请求拦截、业务查询、错误参数测试通过 |
| 语义/父子分块 | 相邻 Embedding 主题变化、父块长度限制、标题保留、子块到父块映射、文件恢复通过 |
| HyDE/RRF/Rerank | 假设文本只用于召回、排名融合、去重、不混用分数、缓存版本失效通过 |
| 索引清单 | 父子映射一致性、双索引计数、Embedding/分块配置变化时未就绪通过 |
| 持久化会话 | 原子保存、重启恢复、会话隔离和路径穿越防护通过 |
| Text2SQL | 真实筛选、JOIN、日期条件、聚合查询及只读账号权限测试通过 |
| XLSX | ZIP/OOXML 部件、实际数据、文本不作公式执行通过 |
| FAISS Java 适配 | 本地 HTTP 请求与排序响应映射通过，HTTP 服务为协议替身 |
| FAISS 原生实现 | 2 项 Python 测试通过，实际 FAISS 1.12.0 余弦检索、去重 upsert、索引持久化与重新载入、维度校验通过 |
| 评测指标函数 | 3 项 Python 测试通过，Recall@K/MRR、缺失标签不报准确率、失败样本纳入分母 |
| RAGAS 接口 | 安装 RAGAS 0.4.3，实际导入 collections Faithfulness、llm_factory 并成功实例化，确认 ascore 参数与返回结构兼容 |
| 前端 JavaScript | Node `--check` 通过 |
| 新项目凭证 | 配置只引用环境变量，没有复制原项目的硬编码模型 Key |

自动测试中的模型、Embedding 数值或评审模型替身不用于证明检索质量与业务正确率。RAGAS 的导入与 API 实例化验证也不等同于完成真实 Faithfulness 评测。

## 尚未验证

- 真实百炼模型与新项目全链路：没有使用用户的真实 Key 发起付费模型调用。
- 真实 PGVector 服务：提供了实际 JDBC/pgvector 实现和部署配置，本次没有启动 PostgreSQL 容器。
- 新项目真实 Milvus 服务已在 2026-10-05 验证，见上方记录；此前验证未启动服务。
- 真实业务问答质量：未对企业文档或全部教学样本进行人工正确性标注，没有 85% 答准率成果。
- 检索延迟与 RAGAS 模型评分：没有真实服务性能报告，也未声称平均检索达到 2 秒。

FAISS 与 RAGAS 验证依赖分别安装在本项目 `.work/python` 与 `.work/eval-python`，未修改系统 Python。Maven 使用本项目 `.work/m2` 避免写入系统仓库；`.work` 是本机验证缓存，已被忽略，不属于项目运行依赖。

## 复现

```powershell
# 标准开发环境
mvn clean verify
python -m unittest discover -s scripts -p 'test_*.py'
python -m pip install -r services/requirements.txt
python -m unittest discover -s services -p 'test_*.py'

# 本机已缓存的 Maven 依赖
./scripts/build.ps1 -WorkspaceCache
```

真实外部验证与评测按照 README 配置环境。模型 Key、数据集和执行配置需由实际运行者提供，不能从成功构建推断质量指标达标。
