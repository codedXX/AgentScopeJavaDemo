# 截图要求映射

| 截图要求 | 已接入的实现与验证方式 |
|---|---|
| AgentScope统一接入生成/Embedding | LlmGateway、AgentConfiguration、BailianEmbeddingClient；MCP生成和Embedding接口 |
| 意图识别→RAG→生成 | SalesAssistant；AgentScope真实HTTP请求回归测试 |
| 语义分块与父子文档 | Markdown章节 + 语句组Embedding相似度边界 + 父子清单持久化；EnterpriseRetrievalTest |
| Query Rewrite与HyDE | 多改写及假设文档向量召回；测试确认假设文本不作为证据 |
| Milvus/FAISS/PGVector | 三种WritableVectorChunkStore实现、配置切换及Docker部署；外部验证状态见VERIFICATION |
| BM25混合召回与RRF/Rerank | 排名融合、重复分块控制和原始分数不混用测试 |
| RAGAS、Recall@K、MRR、Faithfulness | evaluate.py、RAGAS collections API、人工gold/正确性标签、Badcase JSONL |
| ReAct及Plan-and-Execute | 模型生成依赖计划，逐步ReAct执行；PlanExecutionTest用真实H2验证依赖查询和来源 |
| 持久化记忆与长程交互 | 近期历史、早期摘要、原子文件落盘及重启恢复；EnterpriseAgentTest |
| Function Calling查仓库/业务信息 | MCP仓库按SHA读取、HTTP业务查询、数据库工具；McpIntegrationTest |
| MCP封装模型、工具、数据源 | 七种工具发现、参数及敏感请求校验、Streamable HTTP |
| 敏感问题与兜底 | 规则、只读SQL AST、只读账号、来源检查、失败依赖跳过 |
| Text2SQL、筛选、统计、Excel | 真实数据库执行与XLSX结构验证；Text2SqlServiceTest |
| 85%答准率、平均检索≤2秒 | 验收目标；评测完成前没有达标声明 |

当前使用教学样例，不能据此声称真实企业上线或达到业务指标。语义分块默认开启，使用相邻语句组相似度与长度阈值，不使用全局聚类。设置 SEMANTIC_CHUNKING_ENABLED=false 可切回标题/段落递归切分，需重新入库并通过同一评测集对比。
