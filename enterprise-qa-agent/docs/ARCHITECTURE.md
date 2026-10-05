# 架构与接口

## 数据流

```text
TXT / Markdown
  → 清洗、标题分节、语句组Embedding相似度语义切分
  → 父块(1600字符) → 子块(500字符，重叠80)
  → 子块Embedding → Milvus / PGVector / FAISS
  → 子块中文BM25；父子关联原子文件保存

用户问题 + 最近10轮 + 早期摘要
  → 敏感问题拦截 → 意图识别与追问独立改写
  → KNOWLEDGE：Query Rewrite + HyDE
      → 多路BM25/向量召回 → RRF → Rerank → 父块展开
  → REPOSITORY / BUSINESS：MCP
  → DATA：Text2SQL Function Calling
  → MULTI_TASK：模型计划 → 验证依赖 → 逐步ReAct执行 → 汇总
  → 有证据回答、来源校验、耗时与执行步骤
  → 近期对话与摘要原子落盘
```

HyDE 使用假设文档的向量检索真实知识块，不把假设文档交给回答模型。RRF 用各列表中的排名计算 `sum(1/(60+rank))`，单列表重复 ID 只计一次，跨列表累加；最多 30 个候选交给 Reranker，默认保留 5 条并展开父块。原始 BM25/向量分数没有直接比较。

HybridRetriever 并发发起关键词召回，并批量生成查询向量；向量召回通过虚拟线程执行。缓存有 60 秒 TTL、最多 500 条，key 包含知识清单版本，重建后失效。知识读写锁防止新旧索引混用。父子关系缺失或计数不一致时，启动保持未就绪。

Plan-and-Execute 限制 1–5 步，步骤只有 KNOWLEDGE/REPOSITORY/BUSINESS/DATA，不允许写操作；依赖只引用前面步骤。每步验证对应数据源证据，失败依赖跳过，最终汇总说明未确认部分。没有自治写入或自主修改计划的循环。ReAct 工具上限 12 次，单请求总上限 180 秒。

## 组件位置

| 组件 | 文件 |
|---|---|
| Agent、计划执行、记忆摘要 | `agent/SalesAssistant.java` |
| 计划结构和拓扑校验 | `agent/TaskPlanner.java` |
| 会话文件存储 | `agent/PersistentConversationStore.java` |
| 模型结构化调用 | `agent/LlmGateway.java` |
| 分块与父块清单 | `rag/DocumentChunker.java`、`rag/ParentDocumentStore.java` |
| Rewrite、HyDE、RRF、缓存 | `rag/QueryExpansion.java`、`rag/ReciprocalRankFusion.java`、`rag/HybridRetriever.java` |
| 向量适配 | `rag/MilvusChunkStore.java`、`PgVectorChunkStore.java`、`FaissChunkStore.java` |
| FAISS 原生实现 | `services/faiss_service.py` |
| MCP 工具注册 | `mcp/McpServerConfiguration.java` |
| SQL 校验、执行、导出 | `sql/` |
| 检索与回答评测 | `scripts/evaluate.py` |

Java 包名沿用 `com.example.salesagent`，方便与原项目逐文件比较；Maven artifact、配置与运行目录独立。

## REST API

| 方法 | 路径 | 输入 / 输出 |
|---|---|---|
| POST | `/api/chat` | `{sessionId,message}` → answer、sources、steps、retrievalMs、totalMs、retrievedChunkIds、retrievedContexts、retrievedSources |
| GET | `/api/sessions/{id}` | 近期 turns、早期 summary |
| GET | `/api/knowledge/status` | ready、chunkCount、重建状态 |
| POST | `/api/knowledge/rebuild` | 全量重建当前向量后端及关键词索引 |
| POST | `/api/knowledge/upload` | multipart file，UTF-8 TXT/MD，最大5MB |
| POST | `/api/retrieval` | `{query,bypassCache}` → 真实排序证据、insufficient、retrievalMs |
| POST | `/api/sql/query` | `{question,export}` → sql、columns、rows、truncated、demo、source、exportUrl |
| GET | `/api/sql/exports/{uuid}` | XLSX 下载 |

## MCP 工具

MCP 服务在 8086 的 `/mcp` 使用 Streamable HTTP，工具有 `listRepositoryFiles`、`readRepositoryFile`、`getProductStatus`、`generateText`、`embedText`、`searchKnowledge`、`queryDataSource`。模型与Embedding直接调用统一封装；RAG与SQL通过 app REST API调用，避免两个进程同时写 Lucene 和业务库。不要同时在 app profile 和 mcp-server profile 下重复启动同一数据目录的写入者。

## 数据与配置

默认 Milvus collection `enterprise_knowledge`；Lucene与父文档位于 `data/milvus`，PGVector与FAISS在对应后端目录。FAISS 用归一化向量和 `IndexFlatIP` 实现余弦搜索，单个 NPZ 快照保存原生索引、向量与元数据，禁止 pickle。PGVector 使用参数绑定、余弦距离和 HNSW；维度超过2000时只使用平面检索。

Text2SQL 默认使用本机 MySQL 的 enterprise_qa_demo 演示库。管理员凭证保存在不提交的 mysql-admin.properties 中，仅由 scripts/init-mysql-demo.ps1 建库、造数和授权；应用使用 enterprise_qa_reader，只有 products/sales 的 SELECT 权限。scripts/mysql-demo.sql 提供 12 件商品和 364 条固定销售记录，重复导入不覆盖已有主键。SQL 提示词按 JDBC 地址提供 MySQL 方言，支持 DATE_FORMAT/YEAR/MONTH/DAY/DATE 与反引号表名，仍拒绝跨库查询和非只读函数。H2 仅作为自动测试或显式配置的替代库保留。Excel 由标准 OOXML ZIP 生成，文本写 inlineStr，避免把单元格内容作为公式执行。

参考实现接口：[FAISS 索引说明](https://github.com/facebookresearch/faiss/wiki/Faiss-indexes)、[FAISS 索引持久化](https://github.com/facebookresearch/faiss/wiki/Index-IO%2C-cloning-and-hyper-parameter-tuning)、[PGVector 官方文档](https://github.com/pgvector/pgvector)。
