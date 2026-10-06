# 企业智能问答 Agent：简历七点实现拆解与面试回答

> 对照材料：你提供的简历截图与当前 `enterprise-qa-agent` 项目源码。
> 整理日期：2026-10-05。代码基线：`902796a`，以当前工作区实现为准。
> 本文中的口述示例描述的是项目实现。只有你确实负责、理解并能演示的内容，才适合用“我实现了”表述。
> 本次做了源码与既有测试、验证记录核对，没有重新启动服务、调用付费模型或执行真实质量评测。示例回答、SQL 和计算示例均不代表本次实测结果。

## 阅读顺序

1. 先看“项目总览”和“七点核对表”，建立完整调用链。
2. 每一点先掌握“面试口述”，再看实现细节与追问。
3. 面试前重点复习第九节：简历中需要收敛的表述。
4. 最后按第十节的演示顺序，自行跑通项目并记录真实结果。

---

## 一、先用两分钟讲清楚整个项目

### 1.1 项目介绍口述

> 这个项目主要是想解决销售查资料比较分散的问题。比如介绍产品要翻产品手册，查价格和库存要访问业务系统，统计销售额又要查数据库。我把这些查询能力整合到一个问答助手里，让用户可以直接用自然语言提问。
>
> 整体上，我用 Spring Boot 提供接口，用 AgentScope 来组织模型和工具调用。收到问题后，系统先判断用户想查什么：产品介绍这类问题去知识库找资料，价格库存调用业务接口，销售统计就转成 SQL 去查数据库。如果一个问题里包含几件事，就先拆成步骤，再逐步执行。
>
> 我做得比较细的地方有三个。一个是知识检索，把关键词检索和向量检索结合起来，再做排序，让模型拿到更相关、也更完整的资料。第二个是工具调用，回答价格、库存或者统计结果之前，要确认系统确实查到了对应数据。第三个是多轮对话和评测，既能接着上一轮继续问，也能把回答和检索结果保存下来分析问题。
>
> 目前这个项目用演示资料和模拟业务数据跑通了这些功能。准确率和响应时间，我会结合真实评测结果来说明，不把目标值当成已经达到的效果。

### 1.2 实际运行结构

```text
浏览器工作台 / HTTP 客户端
    │
    ▼
Spring Boot app，默认 8085
    ├─ POST /api/chat
    │    └─ SalesAssistant
    │         ├─ 敏感规则 → 会话恢复 → 意图识别 / 指代改写
    │         ├─ KNOWLEDGE → HybridRetriever → 知识证据
    │         ├─ REPOSITORY / BUSINESS → ReAct + MCP 工具
    │         ├─ DATA → ReAct + 本地 queryBusinessData 工具
    │         ├─ MULTI_TASK → TaskPlanner → 逐步执行 → 汇总
    │         └─ 来源检查 → 保存会话 → ChatResponse
    ├─ POST /api/knowledge/rebuild：知识库重建
    ├─ POST /api/retrieval：独立检索
    └─ POST /api/sql/query：自然语言数据查询

Spring Boot mcp-server，默认 8086，/mcp
    ├─ GitHub 仓库文件工具
    ├─ 模拟价格库存 HTTP 工具
    ├─ 模型生成、Embedding 工具
    └─ RAG、数据查询工具 → 转发到 app 的 HTTP API

存储与模型
    ├─ 百炼：生成模型、Embedding、Rerank
    ├─ Lucene：BM25 子块索引
    ├─ 向量后端三选一：Milvus / PGVector / FAISS Python 服务
    ├─ 本地文件：父子映射、索引清单、会话、Excel
    └─ MySQL：products / sales 演示业务表
```

两个 Spring profile 可以用同一个 JAR 分别启动。应用并不是七个微服务，也不是多 Agent 协作平台。代码使用了多个承担不同职责的 Agent 实例，但没有实现多业务 Agent 的自主协商、调度与团队执行。

### 1.3 简历七点与代码的一一对应

| 简历点 | 实际实现位置 | 可以确认的能力 | 需要注意的口径 |
|---|---|---|---|
| ① AgentScope 接入模型与 Embedding，意图→RAG→生成 | `AgentConfiguration`、`SalesAssistant`、百炼适配器 | 结构化路由、生成、Embedding 与 Rerank 接入 | 生成走 AgentScope；Embedding、Rerank 直接调用百炼 SDK |
| ② RAG 全链路与混合检索 | `rag` 包 | 语义父子分块、改写、HyDE、BM25、向量、RRF、重排 | 三种向量后端可切换；85% 尚无实测成果 |
| ③ 自动评测与 Badcase | `scripts/evaluate.py`、`evaluation` | Recall@K、MRR、自选 RAGAS Faithfulness、人工正确率 | 不是所有指标都由 RAGAS 计算；样本需要维护 |
| ④ ReAct、Plan-and-Execute、持久化记忆 | `SalesAssistant`、`TaskPlanner`、会话类 | 最多五步依赖计划、工具循环、最近十轮与摘要、文件恢复 | 当前顺序执行；多业务子 Agent 仍是扩展方向 |
| ⑤ Function Calling 扩展回答边界 | `DataTools`、MCP 工具、`ToolTrace` | 仓库、模拟价格库存、SQL 工具调用与追踪 | 知识不足后的补查主要由模型决策，不是硬编码的必达链路 |
| ⑥ MCP、性能优化、兜底与人工接管 | `McpServerConfiguration`、`HybridRetriever`、规则与来源检查 | 七类 MCP 工具、缓存、并发召回、拒答和转人工建议 | 无 2 秒性能报告；无完整人工接管工单系统 |
| ⑦ Text2SQL 查询及导出 | `Text2SqlService`、`ReadOnlySqlValidator`、`ExcelExporter` | Schema 提示、只读校验、真实 SQL 执行、XLSX | 固定两张业务表，当前业务数据为模拟数据 |

---

## 二、第一点：AgentScope 接入模型与 Embedding，搭建对话问答链路

**简历原文：** 基于 AgentScope 统一接入大模型与 Embedding，搭建“意图识别→RAG 检索→模型生成”的对话式问答链路。

### 2.1 面试口述

> 这部分我主要做的是把用户提问之后的处理流程串起来。系统收到问题，会先判断用户是想查产品资料、查价格库存，还是做销售统计，然后选择对应的处理方式。
>
> 这里还要处理连续追问。比如用户先问“产品 A 的规格是什么”，接着问“那应该怎么保存”，第二个问题没有再提产品名。系统会结合前面的聊天，把它补全成“产品 A 应该怎么保存”，再去检索，这样就不容易查错对象。
>
> 实现上，我用 AgentScope 调用大模型，让它按固定格式返回问题类型和补全后的问题，程序就能据此选择下一步。知识类问题先检索资料，再把查到的内容交给模型组织回答；需要最新数据的问题就调用工具。向量化和重排序用的是百炼接口，我做了一层封装，把模型参数和超时统一管理。
>
> 最后我还做了来源检查：模型返回的引用，必须是这轮实际查到的资料或工具来源。这样用户可以追溯答案出处，不过回答本身是否完全符合资料，还需要通过评测来检查。

### 2.2 模型接入如何落地

`SalesAssistant` 是本项目自己编写的问答协调类，不是 AgentScope 内置组件。它负责把会话、意图识别、检索、工具调用和回答检查串起来。面试开场说“问答处理流程”即可；被追问代码如何组织时，再介绍这个类及其职责。

`AgentConfiguration.chatModel()` 创建 `DashScopeChatModel`。当前配置包括：

| 参数 | 当前值或行为 | 含义 |
|---|---|---|
| 生成模型 | 配置为 `qwen3.7-flash` | 是仓库配置值，账号权限与实际可用性需要另行验证 |
| temperature | 0.1 | 尽量减小输出波动，不代表完全确定性 |
| maxTokens | 2048 | 单次生成输出预算 |
| stream | false | 当前问答采用非流式生成 |
| enableThinking | false | 当前未启用模型的显式思考输出配置 |
| 模型请求超时 | 默认 20 秒 | 来自百炼配置；上层还有各阶段等待限制 |
| maxAttempts | 1 | 生成模型这层只配置一次尝试 |
| Bean | `@Lazy` | 延迟创建，避免启动时立即依赖有效模型 Key |

`LlmGateway.structured(system, input, type)` 将结构化调用统一包装成：创建 Agent → `call(..., type)` → 等待结果 → 检查结构化数据。查询扩展、计划生成、摘要、SQL 生成都复用它，默认等待上限 25 秒。

Embedding 的实际实现是 `BailianEmbeddingClient`：每批最多 10 条文本，指定模型与 1024 维输出，按返回的 `textIndex` 恢复原顺序，再检查数量、索引、维度和数值有效性。不能直接假设服务返回顺序永远和请求顺序一致。

Rerank 使用 `BailianRerankClient`，把 query 和候选文本列表交给百炼重排接口，通过返回的 index 映射回原分块。

**准确表述：** 项目层统一配置和封装了模型能力；并非所有模型类型都通过 AgentScope 的同一个接口接入。

### 2.3 一次问答的实际顺序

1. `ChatController` 接收 `POST /api/chat`，把请求交给 `SalesAssistant.chat()`。
2. 缺少 `sessionId` 时创建 UUID；敏感规则命中时直接返回拦截结果。
3. 在 Java 21 虚拟线程中进入会话；同一个会话同时有请求时拒绝重复进入。
4. 首次加载该会话时，从文件恢复历史摘要和最近消息。
5. 清空本轮 Agent memory，再装入摘要与最近对话，避免上轮工具大段结果无限堆积。
6. `classify()` 调用路由 Agent，返回 `Route(Intent intent, String query)`。
7. 按意图选择检索、工具或计划执行。
8. 把用户问题、独立 query、意图、证据不足标志和知识证据交给执行 Agent。
9. 得到 `Answer(String answer, List<String> sources)`，检查回答与来源。
10. 保存这一轮用户问题、最终回答，返回结果及追踪信息。

六种意图是 `KNOWLEDGE`、`REPOSITORY`、`BUSINESS`、`DATA`、`MULTI_TASK`、`CHAT`。聊天问候不需要经过完整 RAG。

### 2.4 举例：如何理解连续追问

```text
第一轮：产品 A 每袋有多少蛋白质？
    路由：KNOWLEDGE
    query：产品 A 每袋蛋白质含量
    检索 products.md，依据资料回答

第二轮：它含乳吗？
    路由输入包含第一轮的产品 A 上下文
    query：产品 A 是否含乳成分？
    再用独立 query 检索，避免只搜索“它”
```

这里有两层不同的 Rewrite：路由阶段解决指代和上下文缺失；检索阶段生成最多两个同义查询，扩大召回覆盖。

### 2.5 面试追问

**为什么路由要结构化输出？**

> 业务分支需要稳定的枚举和字段，不能靠从一段自然语言中截取“这是知识问题”。record 让模型结果有明确结构，代码仍需检查 intent、query 是否有效。

**路由失败如何处理？**

> 如果调用返回了结果，但结构化内容无效，会回退到 `KNOWLEDGE + 原问题`。如果调用本身抛异常或超时，当前方法没有包住所有异常，不能声称所有路由故障都会自动降级。

**有来源就一定没有幻觉吗？**

> 不能保证。来源过滤能去掉伪造链接，但不等于逐句验证答案与证据一致，所以还需要 Faithfulness 评测和人工正确性检查。

**源码定位：** [生成模型与 MCP 客户端](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/config/AgentConfiguration.java:22)、[问答主流程](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/SalesAssistant.java:170)、[意图识别](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/SalesAssistant.java:551)、[Embedding 适配](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/bailian/BailianEmbeddingClient.java:52)。

---

## 三、第二点：RAG 全链路、父子分块、混合召回与重排

**简历原文：** 主导 RAG 全链路落地：采用语义分块＋父子文档策略做文档预处理，通过 Query Rewrite 与 HyDE 改写用户查询，文档经 Embedding 向量化后写入 Milvus 向量库（FAISS 做本地检索加速、兼容 PGVector）；设计 BM25 关键词召回＋向量稠密检索的混合召回，经 RRF 倒数秩融合与 Rerank 重排序精排（TopK 检索），常见销售高频问题一次答准率提升至约 85%。

### 3.1 面试口述

> RAG 这部分，我主要考虑两个问题：资料能不能找准，以及找到之后，上下文够不够完整。所以我把它分成资料入库和用户查询两个阶段来做。
>
> 入库时，先按标题和内容的语义变化把文档分成较大的段落，再切成适合检索的小片段，同时保存大小片段之间的对应关系。查询时用小片段找相关内容，找到以后再补上它所在的大段资料。这样既能定位到具体信息，也能减少条件、说明被截断的问题，这就是项目里的父子文档策略。
>
> 检索时，我会保留用户原问题，再补充几种同义表达。关键词检索主要照顾产品名、型号这些精确词，向量检索主要处理口语和同义表达。两路结果先用 RRF 按排名融合，再用 Rerank 做一次更细的排序，把最相关的资料交给模型。
>
> 另外接了 HyDE，就是先生成一段假设性的回答，用它帮助搜索真实资料。这段假设只用于检索，不能直接当作回答依据。向量库默认用 Milvus，也可以切换到 PGVector 或本地 FAISS。具体效果要用同一批问题做对照评测，才能判断哪些环节带来了提升。

### 3.2 离线入库：文件怎样变成可检索知识

入口是知识上传或 `/api/knowledge/rebuild`，主实现是 `KnowledgeIngestionService`。

```text
UTF-8 Markdown / TXT
  → 清洗空白与换行
  → 按标题分节
  → 语义父块
  → 重叠子块
  → 子块 Embedding
  → Lucene BM25 + 当前向量后端
  → 父子映射落盘
  → 核对记录数，发布 manifest，标记 ready
```

上传限定 UTF-8、`.md/.txt`、单文件不超过 5 MB，并限制文件名，避免路径穿越。每次上传放入独立 UUID 目录，然后执行全量重建。当前没有 PDF/OCR/Word 解析管道，也没有文档级增量更新流程。

#### A. 文档清洗与标题分节

`DocumentChunker.split()` 统一换行、合并多余空格，保留正文结构。按 Markdown 一到六级标题分节，减少不同产品、不同业务主题落在同一父块中的机会。

每个子块对应 `KnowledgeChunk(chunkId, text, source, ordinal)`。`chunkId` 是来源路径、序号、文本的 SHA-256，因此内容或分块位置改变后 ID 可能变化，需要重新标注 chunk 级评测答案。

#### B. “语义分块”具体怎么算

`SemanticParentSplitter.split()` 的逻辑是：

1. 提取标题，限制标题长度，并在每个父块中保留标题。
2. 用递归切分器生成最多约 240 字符的语句组；不是简单逐句调用模型。
3. 对语句组批量生成 Embedding。
4. 计算相邻两组向量的余弦相似度。
5. 若继续追加会超过父块上限，必须切分。
6. 若当前正文已达到最小长度，且相邻相似度低于阈值，也切分。

默认父块上限 1600 字符，最小长度参数来自子块大小 500，相似度阈值 0.72。标题占用父块长度预算，实际正文可用长度相应减少。

```text
切分条件：
    追加后长度超过上限
    或
    当前正文长度 >= 500 且 cosine(前一组, 当前组) < 0.72
```

这是“标题结构＋局部向量相似度＋长度限制”的启发式策略，不是训练了专门的文档分段模型。0.72 是当前配置，并没有证据证明它适用于所有企业资料。关闭 `SEMANTIC_CHUNKING_ENABLED` 后改为标题＋递归分块。

#### C. 为什么还需要父子文档

父块内部再调用递归分块器：子块大小 500 字符，重叠 80 字符。这里按字符配置，不应说成 500 tokens。

| 内容 | 放在哪里 | 作用 |
|---|---|---|
| 子块正文、来源、ID、序号 | Lucene 与向量后端 | 用相对聚焦的文本召回、重排 |
| 父块正文与来源 | `ParentDocumentStore` | 给生成阶段补充上下文 |
| childId → parentId | 父子映射 JSON | 从命中的子块定位父块 |

例如，“含乳成分”所在的子块可能很短，展开父块后还可看到产品名称、配料和适用边界。这样不会把整篇文档全部塞进模型，也不会只给模型一个缺少主体的孤立句子。

当前是**先对子块 Rerank，再展开父块**。展开后保留子块 ID 便于评测；同一父块可能被多个子块重复展开，当前没有专门的父块去重与总 token 预算裁剪。

#### D. 双索引一致性怎么处理

重建使用写锁和 `rebuilding` 状态；检索使用读锁检查，重建期间拒绝新检索。入库验证向量数量、维度和有限值，写入后核对 BM25、向量库和父子映射的记录数。

成功后才发布 `manifest.json`，包含分块数量、维度、重建时间和配置签名。重启时检查签名和数量，不匹配则要求重建。签名包含模型、端点、后端和分块配置；它不是对所有原始文档内容做实时监控，因此外部修改知识文件后仍需主动重建。

这是单机全量重建的一致性保护。它不是跨 Lucene、Milvus、文件系统的分布式事务，也没有新旧索引蓝绿切换；失败后保持未就绪，修复后重建。

### 3.3 在线检索：Rewrite 与 HyDE 分别解决什么

`QueryExpansion.expand()` 保留输入 query，并追加最多两个非空同义改写。通过 `LinkedHashSet` 去除重复，改写文本每条最多保留 500 字符。

HyDE 的含义是让模型生成一段“可能回答这个问题的文档”，再把这段文本向量化，帮助定位语义相近的真实文档。提示词要求不超过 250 字，代码额外截断到 1000 字符；这两个限制不能混为一谈。

```text
问题：产品 A 含乳吗？

同义改写（示意）：
  产品 A 是否含有乳制品成分？
  产品 A 的配料和过敏原有哪些？

HyDE（示意）：
  某产品说明中列出了配料、乳来源成分与过敏原提示……

假设文本 → 向量查询 → 从库中找真实文档
假设文本 ≠ 最终回答证据
```

HyDE 输出不直接拼入回答证据。项目中已有测试检查这一点。它可能帮助口语问题召回正式文档，也可能引入错误语义，并增加一次模型调用成本，因此应通过消融实验决定是否开启。

### 3.4 BM25 与向量召回如何配合

**BM25：** `LuceneKeywordIndex` 使用 `SmartChineseAnalyzer` 做中文分析，以 `BM25Similarity` 排序，查询字符串先转义。适合 SKU、产品名、专有名词等字面匹配。

**向量召回：** 对问题及改写生成 Embedding，按向量相似度检索。适合“有没有货”和“库存情况”这样的语义关联。

假设两个改写都有效且开启 HyDE，最多有七个召回通道：

| 输入 | BM25 | 向量 |
|---|---:|---:|
| 原 query | 1 路 | 1 路 |
| 改写 1 | 1 路 | 1 路 |
| 改写 2 | 1 路 | 1 路 |
| HyDE 假设文档 | 无 | 1 路 |

每路默认 Top10，合计最多 70 个候选位置，随后去重融合。改写数量不足或查询重复时，通道数会更少。

### 3.5 RRF：为什么不直接加 BM25 和向量分数

两类分数不是同一量纲，直接相加容易让某一路数值大小支配结果。项目使用排名计算融合分数：

```text
RRF(d) = Σ 1 / (60 + rank_i(d))

rank 从 1 开始；未被某一路召回，该路贡献为 0。
```

举一个纯计算示例：文档 A 在两路分别第 1、第 3；文档 B 只在一路第 1。

```text
A：1/61 + 1/63 ≈ 0.03227
B：1/61 ≈ 0.01639
```

A 因为得到多路支持而领先。`ReciprocalRankFusion.fuse()` 按 chunkId 聚合，同一通道重复出现的 chunk 只计一次；分数相同以 ID 稳定排序，最终保留 Top30。

当前所有通道等权，没有通道权重学习，也没有对相似改写通道做相关性折扣。不能说实现了自动学习的融合权重。

### 3.6 Rerank 与证据不足判断

`BailianRerankClient.rank()` 使用原始独立 query 和融合候选的子块文本重排，默认最终 Top5。它检查返回 index 是否越界或重复、分数是否有效，再映射回分块并按分数排序。

`HybridRetriever` 的不足条件实际上是：

```java
ranked.isEmpty()
    || minScore != null && ranked.getFirst().score() < minScore
```

当前 `application.yml` 没有设置 `min-rerank-score`，所以默认情况下主要以“结果为空”触发不足。即使配置阈值，也只是设置 `insufficient=true`，并不会自动把低分片段全部过滤掉。配置类注释写了“过滤”，但实际行为应以这里的代码为准。

因此，面试中宜说“支持基于最高重排分数的证据不足提示”，不宜说“已经实现准确的低置信度识别”。重排分数也不是校准后的答案正确概率。

### 3.7 三种向量后端分别怎么实现

| 后端 | 当前实现 | 面试时应该怎么解释 |
|---|---|---|
| Milvus | Java SDK；`AUTOINDEX`；`COSINE`；写入后 flush/load；强一致检索 | 默认远程向量存储，保存子块与向量；没有在代码中显式指定 HNSW |
| PGVector | JDBC；`vector(dimension)`；默认维度满足条件时建 HNSW；`<=>` 余弦距离 | 使用 PostgreSQL 扩展，分数映射为 `1 - distance` |
| FAISS | Java HTTP 适配 + Python FastAPI；`IndexFlatIP`；L2 归一化 | 归一化后内积等价于余弦相似度；是本地精确检索后端 |

由 `VECTOR_BACKEND=milvus/pgvector/faiss` 选择一个后端。切换后需要重建，各后端使用独立 Lucene 目录和父子清单。

FAISS 把原生索引、向量和元数据写入 NPZ 快照，启动时恢复；upsert 按 chunkId 去重后重建平面索引。当前并非 Milvus 前面的缓存层，也不是同时把 Milvus 结果再交给 FAISS 加速。`IndexFlatIP` 是精确平面检索，没有实现 IVF/PQ，也没有量化优化性能报告。

### 3.8 高频追问

**为什么选 500/80/1600 和 Top10/30/5？**

> 这些是当前可运行的初始参数：子块控制检索粒度，重叠降低边界信息丢失，父块补充上下文；召回规模和精排规模控制覆盖率与调用成本。没有做完固定数据集的对照实验前，我不会说这些是最优参数。

**为什么不只用向量检索？**

> 业务里有 SKU、型号和制度名，字面匹配很重要；同时用户存在口语和同义表达，所以用 BM25 与向量互补。是否提高结果质量需要评测确认。

**“85%”怎么来的？**

> 当前仓库只有目标配置和评测工具，没有完整人工标注结果及基线对照，不能把 85% 说成已经实现的提升。正式报告应包含样本数、标签规则、模型与知识版本、失败率、优化前后结果。

**源码定位：** [分块](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/DocumentChunker.java:86)、[语义父块](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/SemanticParentSplitter.java:28)、[入库与清单](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/KnowledgeIngestionService.java:142)、[混合检索](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/HybridRetriever.java:58)、[查询扩展](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/QueryExpansion.java:18)、[RRF](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/ReciprocalRankFusion.java:12)、[FAISS 原生服务](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/services/faiss_service.py:74)。

---

## 四、第三点：自动评测、Recall@K、MRR、Faithfulness 与 Badcase

**简历原文：** 搭建离线评测体系：基于 RAGAS 覆盖 Recall@K、MRR、Faithfulness 等指标，并持续跟踪幻觉率与 Badcase，迭代分块策略与召回参数。

### 4.1 面试口述

> 评测这部分，我主要看三件事：资料有没有找对，回答有没有依据，以及最后有没有解决用户的问题。
>
> 我写了一个脚本，批量调用问答接口，把每个问题的回答、检索资料、工具执行情况和耗时保存下来。检索这边用 Recall@K 看需要的资料找到了多少，用 MRR 看第一条相关资料排得靠不靠前。回答这边接入 RAGAS 的 Faithfulness，检查回答里的内容能不能从提供的资料中得到支持。至于业务上到底答得对不对，还是要对保存下来的回答做人工标注。
>
> 发现错误后，我会先看是哪一环出了问题。比如相关资料根本没找到，就查分块和召回；资料已经找到了，回答却多加了没有依据的承诺，就查生成环节。脚本会把这些问题整理成 Badcase，方便修改以后用同一批问题重新验证。
>
> 统计时，请求失败也算进去，人工还没标完就不报完整准确率，避免最后的数字只反映一部分成功案例。

### 4.2 输入、执行与输出

评测入口是 `scripts/evaluate.py`，样本文件是 `evaluation/cases.jsonl`，目前有 25 条演示案例。

```text
cases.jsonl
  → 每题创建新 sessionId
  → POST /api/chat
  → 保存实际 response
  → 计算检索指标
  → 可选：RAGAS Faithfulness
  → 读取人工标签
  → results.jsonl / results.summary.json / badcases.jsonl
```

每题新建会话能避免样本互相污染，但默认脚本不验证多轮追问能力。多轮场景需要另外组织共享 sessionId 的测试。

`ChatResponse` 中关键字段：

| 字段 | 评测用途 |
|---|---|
| `answer` | 对这次真实生成的答案评分 |
| `retrievedChunkIds` | chunk 级检索评测 |
| `retrievedSources` | source 级检索评测 |
| `retrievedContexts` | RAGAS 实际证据，含主检索及工具采集的上下文 |
| `sources` | 最终答案引用的来源；与实际检索排名不是一回事 |
| `retrievalMs` | 主流程检索耗时，复合任务累加 |
| `totalMs` | 问答总耗时 |
| `steps` | 路由、计划、工具成功或失败的可观察步骤 |

### 4.3 三类质量指标怎样计算

#### Recall@K：必要证据找全了吗

```text
Recall@K = TopK 命中的相关 ID 数量 / 标注的全部相关 ID 数量
```

例如 gold 为 `{A, B}`，Top5 命中 A 但没有 B，则 Recall@5 为 0.5。脚本对命中集合去重。没有 gold 的样本返回 `null`，不冒充 0 或 1。

优先使用人工标注的 `relevantChunkIds`；没有时退回 `relevantSources` 或单个 `expectedSource`。source 级命中文件不代表文件中真正相关的句子被召回，不能把 source 级高分说成高精度 chunk 检索质量。

#### MRR：第一条有用证据排在多前

```text
单题 RR = 1 / 第一条相关结果排名
MRR = 有标注样本 RR 的平均值
```

第一条相关结果排第 2，RR 为 0.5；TopK 没有相关项则为 0。当前脚本只在 TopK 范围内查找，所以严格说是截至 K 的 MRR 口径。它衡量首个相关结果的位置，不衡量所有必要证据是否齐全。

#### Faithfulness：回答是否得到上下文支持

启用 `--ragas` 后，脚本创建 RAGAS `Faithfulness`，调用：

```python
scorer.ascore(
    user_input=question,
    response=answer,
    retrieved_contexts=contexts
)
```

其含义是评估回答中的事实陈述能否由给定上下文支持。项目使用的是 RAGAS 0.4.3 的接口；评审模型需要独立配置。

**Faithfulness 不等于业务正确率。** 模型可能忠实地复述过期资料，仍不满足业务问题；也可能基于常识给出正确事实，但不被当前证据支持。评审失败或没有上下文时记录 `null` 和状态，不编造评分。

“幻觉率”当前没有独立字段和明确计算流程。可以说用 Faithfulness 和 `ungrounded_answer` Badcase 跟踪无依据回答；不能直接把 `1 - 平均 Faithfulness` 宣称为已经验证的业务幻觉率。

#### 一次答准率：人工判断是否一次解决问题

```text
一次答准率 = 标注 correct=true 的样本数 / 全部样本数
```

当前脚本要求全部样本都有标签才输出这一指标。请求失败记作错误。标签必须应用到 `--input` 指定的已保存回答，避免模型重新生成后，标签仍对应旧答案。

例如“20 个样本中 17 个正确 = 85%”只能作为计算示例；本仓库没有这份实测数据，更没有优化前的基线。`targets.first_answer_accuracy=0.85` 是目标配置，不是结果。

### 4.4 Badcase 怎样指导迭代

脚本会自动标记：

| 原因字段 | 当前触发条件 | 下一步怎么查 |
|---|---|---|
| `request_failed` | 接口请求失败 | 服务、超时、权限、网络或模型参数 |
| `answer_incorrect` | 人工标为错误 | 看真实问题与回答是否满足需求 |
| `retrieval_missed_gold` | Recall@K 小于 1 | 看实体改写、分块、召回与重排 |
| `ungrounded_answer` | Faithfulness 小于 0.8 | 看答案是否超出证据或把假设当事实 |

Badcase 中预留 `root_cause` 和 `fix`，需要人工填写。建议按以下顺序排查：

1. 是否路由错了：价格库存被当作知识问题。
2. query 是否丢实体：把“产品 A”改写成泛化的“营养粉”。
3. 必要信息是否在同一父块：条件与结论被分开。
4. 是否初召回未命中：BM25 分词或向量语义覆盖不足。
5. 是否召回了但被重排淘汰：需要保留中间候选便于分析，当前主要返回最终证据。
6. 是否工具失败：没有取得数据库、仓库或业务接口结果。
7. 是否证据正确但生成越界：需要调整提示词和回答检查。

示例分析方法：用户问“资料没写统一退货天数，你们支持几天”，模型却答“七天”。先检查 `business.md` 是否进入上下文；如果证据明确写了“不规定统一天数”，这是生成无依据补全，不能一味增加 TopK。这是排查示例，并非已经观测到的项目 Badcase。

### 4.5 当前评测脚本有两个必须知道的边界

**第一，工具场景不能直接混入 RAG Recall。**

脚本读取的是 `retrievedSources` 或 `retrievedChunkIds`，业务/仓库工具的来源通常记录在 `sources` 和 `retrievedContexts` 中，不会自动进入主 RAG 排名字段。样本中的业务路径、GitHub 路径又有部分是相对路径或前缀，而 `ranked_metrics()` 做的是精确匹配，不支持前缀匹配。

所以当前 BUSINESS/REPOSITORY 样本可能得到没有实际意义的检索零分。正式评测应分开报告“RAG 检索指标”和“工具路由/执行/结果正确率”。脚本已经按 source/chunk 分组，但尚未完整按意图拆分工具质量指标。

**第二，部分答案仍对应早期 H2 小数据集。**

例如 case-22、case-24 中的“华东 3770 元”，来自 H2 测试种子；当前默认 MySQL 已迁移为更多模拟数据。不能拿这类旧参考答案判断新库查询必然正确或错误。应先固定数据库快照，再审核 `referenceAnswer`。不要只为了让得分变高而修改标准答案。

另外，`referenceAnswer` 和 `expectedIntent` 当前不会自动参与正确率或意图准确率计算；正确率依赖人工布尔标签。现有 25 条只是教学样本，不足以证明真实企业高频问题效果。

### 4.6 如何回答“你怎么证明优化有效”

> 我会固定文档、业务数据、模型版本、提示词、样本顺序和缓存状态，对比纯向量、混合召回、混合加重排、再加改写/HyDE 的结果；分别报告检索指标、人工正确率、Faithfulness、失败率和耗时。一次只调整一组参数，并检查 Badcase 变化。当前项目提供了运行和记录基础，还没有完成可支撑 85% 提升结论的对照实验。

**源码定位：** [评测指标及汇总](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/scripts/evaluate.py:26)、[RAGAS 调用](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/scripts/evaluate.py:92)、[当前样本](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/evaluation/cases.jsonl)、[Badcase 流程](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/evaluation/BADCASE_WORKFLOW.md)。

---

## 五、第四点：ReAct、Plan-and-Execute 与持久化长程记忆

**简历原文：** 基于 ReAct 推理框架与 Plan-and-Execute 规划执行实现多步骤任务编排，设计持久化记忆机制进行会话态管理，预留多业务子工接入能力，支撑跨流程自动化演进。

### 5.1 面试口述

> 有些问题查一次资料就能回答，有些会包含好几个任务。比如“先介绍一下产品 A，再统计一下华东地区的销售额”，这里既要查产品资料，也要查数据库。我会先让模型把任务拆成几个步骤，程序检查步骤和依赖关系，再按顺序执行，最后把结果汇总成一个回答，这就是 Plan-and-Execute。
>
> 每一步需要用工具时，通过 AgentScope 的 ReAct 流程，让模型选择工具，拿到结果后再决定下一步。为了避免一直循环，我限制了计划步数和工具调用次数。如果前一步失败，后面依赖它的步骤就跳过，并在最终回答中说明哪部分没完成。
>
> 多轮对话方面，我保留最近十轮左右的原始聊天，更早的内容压缩成摘要，记录用户在聊什么、已经确认了什么。这样后续追问能接得上，也能控制上下文长度。这些内容会保存到本地文件，服务重启后可以恢复。
>
> 不同会话分别管理，同一个会话也做了并发保护，防止两个请求同时修改历史。当前实现主要面向单机运行，多实例共享记忆和执行到一半的任务恢复，还需要继续扩展。

### 5.2 ReAct 实际由谁执行

`createState()` 为每个会话创建：

```text
InMemoryMemory + Toolkit + ToolTrace
                  │
                  ▼
ReActAgent(name="sales-assistant", maxIters=6)
```

`DataTools` 注册为本地工具，MCP 工具按需注册到该 Agent 实际持有的 Toolkit。工具调用循环由 AgentScope 执行，业务代码提供工具、提示词、限制与追踪 Hook，没有自己重新实现完整 ReAct 调度器。

ReAct 可以理解为“依据当前上下文决定行动 → 执行工具 → 读取结果 → 继续或回答”。返回给前端的 `steps` 是工具与业务流程日志，并非完整的模型内部思维链。

主要限制：执行 Agent 最多 6 次迭代；`ToolTrace` 对识别的工具计数，单轮超过 12 次时报错；普通执行 Agent 单次调用等待上限 85 秒。问答直接在请求线程执行，没有整轮总时限，各阶段的等待上限也不能保证外部 I/O 瞬间取消。

### 5.3 Plan-and-Execute 怎么落地

当路由为 `MULTI_TASK` 且 `planning-enabled=true`，进入 `runPlan()`。

`TaskPlanner` 要求模型返回：

```json
{
  "steps": [
    {"id": "s1", "action": "KNOWLEDGE", "question": "产品 A 的蛋白质含量是多少？", "dependsOn": []},
    {"id": "s2", "action": "DATA", "question": "统计华东地区销售额", "dependsOn": ["s1"]}
  ]
}
```

这是结构示意。两步是否真正有数据依赖，要根据问题判断；如果只是并列请求，第二步可以没有依赖。当前执行器即使面对独立步骤也仍然顺序执行。

计划校验包括：一到五步、ID 合法且不重复、action 在四种允许类型内、问题非空且长度受限、依赖只能引用之前的步骤。因此没有前向依赖，也能排除这类计划中的循环。

每步执行顺序是：

1. 依赖中存在失败步骤，当前步骤直接标为失败并跳过。
2. 再检查子问题是否触发敏感规则。
3. 将依赖结果作为数据上下文传入。
4. `KNOWLEDGE` 步先检索，其他类型按需使用工具。
5. 调用执行 Agent，单步等待上限 45 秒。
6. 核对该步是否取得相应来源：DATA 要有 `business://`，BUSINESS 要有业务接口来源，REPOSITORY 要有 GitHub 来源。
7. 对工具型步骤检查是否发生新的工具调用，避免模型只凭文字声称“查过了”。
8. 保存成功结果；失败结果明确标为未确认。
9. 最后由 `LlmGateway` 汇总，并过滤不在成功来源集合中的引用。

`successfulSources` 用列表记录成功调用来源，执行前记住列表长度，执行后仅检查新增区间；即使多个步骤访问同一个来源，也能判断这一具体步骤是否取得了新结果。

### 5.4 当前计划执行有什么边界

依赖结果是作为 prompt 文本传入，不是类型安全的变量绑定或自动参数替换。当前没有执行中动态重新规划、工作流持久化、补偿事务、人工审批节点或中断后接续执行。

复合任务共用当前会话的执行 Agent，单步有迭代限制，工具总次数由本轮 trace 累计。生成计划本身失败时也没有完整的替代计划路径。

“预留子工接入”可解释为：action 分支、Toolkit 和工具接口便于扩展后续业务能力。不能据此说已经实现多个独立业务 Agent 协作；那需要额外的执行器注册、任务路由、输入输出协议和权限隔离。

### 5.5 持久化记忆如何防止越聊越长

`SalesAssistant.remember()` 每次保存用户与助手两条消息。当消息数超过 20 时，把最早一轮移入摘要：

```text
旧摘要 + 最早一轮问答
  → LlmGateway 生成新摘要
  → 保存实体、目标、已确认事实、未完成事项
  → 移除这轮原始消息
  → 保留最近 20 条消息
```

摘要提示词要求最多 600 字；摘要失败时把旧消息文本追加到摘要，之后将总长度限制为 4000 字符。600 字是模型提示约束，4000 字符是程序硬限制，不能说代码保证摘要一定不超过 600 字。

下一轮开始前，会清空 Agent memory，再加载摘要与近期对话。这种 memory 是对话历史压缩，不是向量化长期记忆，也不是完整保留所有历史工具轨迹。

### 5.6 会话存储与并发

| 机制 | 当前实现 | 解决的问题 |
|---|---|---|
| 会话隔离 | `SessionRegistry<State>` 按 sessionId 保存 | 避免不同会话混用记忆 |
| 容量 | 最多 100 个内存会话 | 控制内存增长 |
| 空闲 TTL | 30 分钟，后续访问触发清理 | 清理过期运行态，不代表删除磁盘历史 |
| 同会话并发 | `ReentrantLock.tryLock()`，冲突时拒绝 | 保证单会话问答顺序 |
| 文件名 | sessionId 的 SHA-256 + `.json` | 避免直接把输入拼进路径 |
| 写入 | 临时文件 → 优先原子替换；不支持时普通替换 | 降低写入中断造成半文件的风险 |
| 页面恢复 | localStorage 保存 sessionId，接口加载历史 | 刷新页面可恢复最近对话 |

哈希文件名不是加密；持有会话 ID 也不等于经过身份认证。多副本部署还需要身份绑定、共享存储和分布式并发控制。

**源码定位：** [执行 Agent 创建](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/SalesAssistant.java:152)、[计划执行](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/SalesAssistant.java:395)、[计划校验](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/TaskPlanner.java:50)、[摘要记忆](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/SalesAssistant.java:350)、[会话锁与容量](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/SessionRegistry.java:53)、[会话落盘](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/PersistentConversationStore.java:66)。

---

## 六、第五点：Function Calling 接入仓库与业务工具

**简历原文：** 设计 Function Calling 工具调用机制，知识库命中不足时自动调用线上代码仓库／业务接口获取实时信息，扩展 Agent 回答边界。

### 6.1 面试口述

> 这部分主要是解决知识库覆盖不到，或者数据会变化的问题。比如“产品 A 现在多少钱、还有多少库存”，就需要查业务接口；用户问某个项目怎么实现，就可以去读指定代码仓库里的文件。
>
> 我把这些能力封装成工具，告诉模型每个工具能做什么、需要哪些参数，再交给 AgentScope 管理调用。模型选好工具、填好参数后，由程序实际执行，把结果返回给模型继续回答，这就是这里的 Function Calling。
>
> 我还记录了每次工具有没有成功，以及数据从哪里来。比如回答库存之前，程序会检查这一轮是不是确实拿到了业务接口的结果；没查到，就明确说现在无法确认。读仓库时会先拿文件目录和提交版本，再读取对应文件，避免前后读到不同版本的内容。
>
> 对明确需要最新数据的问题，系统会直接走工具；知识检索结果不足时，也允许模型根据问题选择补查。目前价格库存接的是模拟接口，主要验证这条调用流程。

### 6.2 一次 Function Calling 的执行过程

```text
提供工具名称、描述、参数定义给模型
  → 模型返回工具名与参数
  → Toolkit 匹配并执行工具
  → 工具返回 JSON / 错误
  → ToolTrace 收集实际来源与步骤
  → 模型读取结果后继续调用或生成答案
  → 应用检查必要来源
```

本地 `DataTools.query()` 使用 `@Tool(name="queryBusinessData")` 和 `@ToolParam`，参数是 `question` 与布尔 `export`。仓库、价格库存等工具通过 MCP schema 暴露给 Toolkit。

Function Calling 是模型表达“要调用哪个函数、传什么参数”的机制；MCP 是客户端和工具服务之间的发现、调用协议。二者可以一起使用，也可以分别使用。

### 6.3 仓库工具：为什么先查目录，再读文件

`GitHubRepositoryTools` 只访问配置的一个仓库。流程为：

1. 查询仓库信息获得默认分支。
2. 查询该分支的提交 SHA。
3. 用 SHA 查询递归文件树，返回最多 500 个允许文件及 `truncated`。
4. 模型根据文件树选择路径，再调用 `readRepositoryFile(path, commitSha)`。
5. 用相同 commit SHA 读取文件，解码 Base64，返回文本与 GitHub 固定版本链接。

固定 commit SHA 能避免两次请求之间分支更新导致“目录来自旧版本、内容来自新版本”。来源链接形如 GitHub 的 `blob/<sha>/<path>`，面试演示时可追溯。

工具还限制路径穿越、敏感名称、文件扩展名和大小；超过 1 MB 拒绝，返回正文最多约 6500 字符，截断时保留标志。它是在线读取指定仓库文件，不是克隆并执行远程代码。

当前“先目录后文件”主要由提示词与 SHA 参数约束实现，没有服务端维护一张“本会话已获准访问的路径与 SHA”授权表。路径规则也不是全面的密钥扫描系统。

### 6.4 价格库存工具：实时调用不等于真实生产数据

`BusinessTools.getProductStatus(sku)` 校验 SKU，然后请求：

```text
GET /demo/business/products/{sku}
```

当前 `DemoBusinessController` 只支持 `DEMO-A`，返回固定的 199 元、120 件库存、`demo=true` 和本次时间戳。

因此可以说“实现了通过 HTTP 查询实时业务接口的链路”，不能说“已经对接生产库存系统”。更新时间只是响应生成时间，不能证明真实库存发生过更新。该模拟接口也不是读取 Text2SQL 使用的 MySQL 库，两条数据来源独立。

### 6.5 如何防止“模型说调用了，其实没调用”

`ToolTrace` 在 `PreActingEvent` 计数并记录工具名，在 `PostActingEvent` 解析返回结果：

- 有有效 `source` 的工具结果才加入可用证据集合。
- `searchKnowledge` 从 `evidence` 逐条提取来源与正文。
- `generateText`、`embedText` 即使返回伪协议 source，也不会被当作事实证据。
- 工具错误被归类为参数、鉴权、限流、超时等信息，供回答和前端查看。

最终 BUSINESS 回答没有业务接口来源时被替换为“价格库存无法确认”；DATA 没有 `business://` 来源时不认可统计结果；REPOSITORY 没有来源时不认可仓库回答。

这些检查确保“需要外部数据的问题有对应数据来源”，但没有对答案每个数字和语句逐项验真。来源存在也不是任意结论都成立。

### 6.6 知识不足的自动补查有什么边界

普通知识问题会把 `rag.insufficient()` 写入 prompt，再由 ReAct 决定调用工具。当前阈值默认未配置，而且只要有检索结果就未必触发不足。因此“自动补查”是已接通的执行能力，不是每次知识缺失都能可靠触发的保证。

若 RAG 本身抛异常，当前流程也不会一律捕获后转工具。MCP 不可用但已有知识证据时可以继续回答；MCP 不可用且没有知识证据时则可能直接报错。

**源码定位：** [本地 Function Calling 工具](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/tool/DataTools.java:21)、[仓库读取](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/tool/GitHubRepositoryTools.java:60)、[业务接口适配](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/tool/BusinessTools.java:34)、[模拟业务数据](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/api/DemoBusinessController.java:24)、[工具追踪](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/ToolTrace.java:63)。

---

## 七、第六点：MCP 标准化接口、延迟优化、兜底与人工接管

**简历原文：** 通过 MCP 统一封装模型、工具与数据源接入层，对外提供标准化问答接口，平均检索响应延迟控制在 2s 以内，并针对低置信／敏感问题设计人工接管机制，便于与业务系统集成。

### 7.1 面试口述

> 接入能力越来越多以后，我希望这些工具能够被其他系统复用，所以用 MCP 把仓库查询、业务查询、知识检索这些能力统一发布出去。接入方可以通过协议了解有哪些工具、需要传什么参数，再发起调用。完整的对话问答另外提供 HTTP 接口，方便接到业务页面里。
>
> 响应速度方面，我做了批量向量化、让部分检索任务重叠执行，并控制送去重排序的候选数量。重复问题还会用短时间缓存；知识库更新后，缓存也会跟着失效。系统会分别记录检索耗时和整次问答耗时，后面可以据此分析慢在哪一步。两秒是当前目标，还需要真实测试来确认。
>
> 兜底方面，明显涉及凭证或越权的请求会先被拦截；资料不够或者工具失败时，就说明无法确认，提示走人工确认流程。目前实现到了拦截、拒答和升级提示，还没有接入完整的人工工单系统。

### 7.2 MCP 服务端与客户端

服务端 `McpServerConfiguration` 使用 `HttpServletStreamableServerTransportProvider`，挂载 `/mcp`，允许异步 servlet，并建立 `McpSyncServer`。

当前发布七个 MCP 工具：

| MCP 工具 | 参数 | 执行路径 |
|---|---|---|
| `listRepositoryFiles` | 无 | GitHub 文件树与 commit SHA |
| `readRepositoryFile` | path、commitSha | GitHub 文件内容 |
| `getProductStatus` | sku | 模拟业务 HTTP 接口 |
| `generateText` | prompt | `LlmGateway` |
| `embedText` | text | 百炼 Embedding 适配器 |
| `searchKnowledge` | query | HTTP 转发 `/api/retrieval` |
| `queryDataSource` | question、export | HTTP 转发 `/api/sql/query` |

工具参数使用 JSON Schema，当前构造器将字段都定义为字符串、全部必填、禁止额外字段。因此 MCP 的 `queryDataSource.export` 是字符串 `"true"/"false"`；本地 `queryBusinessData.export` 才是布尔值。这是能通过源码验证的具体差别。

客户端用 `McpClientBuilder` 创建 Streamable HTTP 连接并初始化，再由 `registerMcpClient()` 把远程工具加入 Toolkit。

MCP 服务的 `searchKnowledge` 和 `queryDataSource` 依赖 app 可用；因此两个进程存在调用关系。MCP 本身不会自动带来低延迟、业务鉴权或数据隔离。

### 7.3 “统一封装”应怎样准确理解

对外确实可以通过 MCP 复用多类能力，但 app 内部 RAG 直接使用 Java 服务，生成直接使用模型对象，Embedding 直接使用适配器；本地 SQL 工具也不强制绕一圈 MCP。

项目没有一个 MCP `chat` 工具封装完整会话接口。准确说法是“对外提供标准化 MCP 能力工具，同时提供完整 HTTP 问答 API”，而非“所有内部调用都经过 MCP”。

### 7.4 延迟优化已经做了哪些

1. **查询扩展合并调用：** 同义改写与 HyDE 由一次结构化模型调用返回。
2. **批量 Embedding：** 原 query、改写、HyDE 一起交给向量化适配器，减少零散调用。
3. **虚拟线程任务：** BM25 任务先提交，等待 Embedding 后再提交向量检索任务；不同类型工作有重叠执行机会。
4. **限制候选：** 每路 Top10，融合 Top30，重排 Top5，控制后续计算和上下文规模。
5. **检索缓存：** 默认 60 秒，最多约 500 个查询条目。
6. **延迟创建依赖：** 模型及 MCP 客户端按需初始化；代价是首次请求可能承担初始化耗时。

但不能把这些概括为“所有召回完全并行”：当前 Lucene 的 `search()` 和 Milvus 的 `search()` 使用 `synchronized`，同一实例的对应调用会串行进入；FAISS 服务也有锁。虚拟线程主要改善等待与组织方式，不会自动移除底层锁或远程服务瓶颈。

### 7.5 缓存怎样避免重建后拿旧知识

缓存 key 是：

```text
知识库 revision（重建时间） + 换行 + 独立 query
```

重建成功后 revision 变化，旧缓存自然不再命中。缓存保存完整 RAG 结果，命中会跳过改写、向量化和重排，返回本次很短的耗时。

当前缓存基于插入顺序的 `LinkedHashMap`，达到容量时移除最早插入项，不是严格 LRU；也没有针对同 key 的请求合并。`bypassCache=true` 会跳过缓存读写，可用于独立检索测量。

### 7.6 2 秒指标应该测什么

`retrievalMs` 从 `HybridRetriever.retrieve()` 开始，包含缓存检查、查询扩展、Embedding、BM25/向量召回、RRF、Rerank、父块展开；不包含此前的意图路由、后续工具与最终生成。复合任务会累加各次主检索时间。

若 Agent 通过 MCP 额外调用检索工具，这段工具时间并不会自动累加进主流程 `rag.retrievalMs`，所以不能把该字段当作所有外部检索工作的完整耗时。

评测汇总只从成功、有上下文且 `retrievalMs > 0` 的响应中计算检索均值与 P95；因此必须同时报告 `latency_sample_count` 和失败数。不能隐去失败请求，再将成功子集的低均值说成全请求性能。

建议分别报告：

| 场景 | 需要记录 |
|---|---|
| 第一次请求 | 冷启动与客户端初始化成本 |
| 关闭缓存的稳定运行 | 模型、网络、向量库真实链路成本 |
| 缓存命中 | 热查询收益 |
| 不同并发度 | 均值、P95、失败率、吞吐 |
| 端到端问答 | `totalMs` 或客户端计时，不与纯检索混用 |

若实测达不到 2 秒，可评估关闭 HyDE、减少改写、复用索引 reader/searcher、减小重排规模和优化服务部署距离。这些属于后续优化方向，不能说当前已经全部实施。

### 7.7 低置信、敏感与人工接管的真实状态

| 层次 | 当前行为 | 尚未实现的能力 |
|---|---|---|
| 敏感输入 | 正则拦截凭证、个人敏感数据、绕过权限或破坏性表述等 | 全面语义安全分类与 DLP |
| 证据不足 | 空召回/可选分数门槛提示模型补查 | 校准后的置信度模型、完善的强制转人工策略 |
| 工具失败 | 记录原因，缺少必要来源时拒绝确认事实 | 完整重试编排、工单升级 |
| 引用检查 | 只保留本轮真实可用来源 | 每句答案到证据片段的蕴含校验 |
| 人工介入 | 知识资料中建议记录并交业务负责人确认，相关拒答提示走授权流程 | 工单创建、队列分配、人工回复、接管状态、自动恢复 |

真正的人工接管至少需要 `NEEDS_HUMAN` 等状态、问题与证据快照、工单系统接口、处理人、人工答复入口和回流记录。当前代码没有这条闭环，适合表述为“设计证据不足与敏感请求的兜底边界，预留人工升级流程”。

**源码定位：** [MCP 服务](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/mcp/McpServerConfiguration.java:57)、[平台工具转发](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/tool/PlatformTools.java:60)、[缓存与召回](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/HybridRetriever.java:58)、[敏感规则](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/SensitiveQuestionGuard.java:6)。

---

## 八、第七点：Text2SQL、只读查询、筛选统计与 Excel 导出

**简历原文：** 集成 Text2SQL 数据查询与导出能力，结合数据库表结构与业务口径将自然语言需求转换为 SQL，支持数据按条件筛选、统计及 Excel 导出。

### 8.1 面试口述

> 这部分是让用户用自然语言查业务数据。比如用户说“按地区统计九月份的销售额，再导出 Excel”，系统就根据这句话生成查询 SQL，查出结果，再提供 Excel 下载。
>
> 为了让模型理解业务，我会把商品表、销售表有哪些字段，两张表怎么关联，以及销量、销售额这些指标的含义一起提供给它。比如统计销售额，要汇总实际成交金额，不能直接拿现在的商品价格乘销量。
>
> 模型生成 SQL 后，程序会先解析和检查，只允许查询指定的业务表，并限制可用函数。数据库连接也使用只读账号，避免模型生成的语句修改业务数据。另外设置了查询超时和返回行数上限，控制一次查询的资源消耗。
>
> 查询成功以后，把同一批结果生成真正的 XLSX 文件供用户下载。目前用 MySQL 演示库支持了条件筛选、关联查询和分组统计，导出也遵守最多二百行的限制。

### 8.2 三种入口最终复用同一个服务

```text
自然语言聊天 → DATA 意图 → queryBusinessData 本地工具 ┐
前端数据查询 → POST /api/sql/query                  ├→ Text2SqlService
MCP 客户端 → queryDataSource → HTTP /api/sql/query  ┘
```

这避免为聊天、网页和 MCP 各写一套 SQL 逻辑。

### 8.3 模型看到什么数据库信息

`BusinessDatabase.schema()` 返回固定 schema 和业务含义：

```text
products(sku, name, category, price, stock)
sales(id, sku, region, quantity, amount, sold_at)

关联：products.sku = sales.sku
price：当前单价
stock：当前库存
quantity：销售数量
amount：实际销售额
sold_at：销售日期
金额单位：人民币
```

根据 JDBC URL 选择 MySQL 或 H2/PostgreSQL 兼容提示；MySQL 场景额外允许 `DATE_FORMAT`、`YEAR`、`MONTH` 等日期函数。

这里的业务口径已经写入 prompt，但不是成熟的企业指标语义层。没有动态读全库元数据、表检索、指标审批或多 schema 自动适配。

### 8.4 完整例子：按地区统计九月销售额并导出

用户问题：`按地区统计 2026 年 9 月销售额，并导出 Excel`。

模型可能生成以下 SQL，具体文本每次不一定完全一致：

```sql
SELECT region, SUM(amount) AS total_amount
FROM sales
WHERE sold_at >= '2026-09-01'
  AND sold_at < '2026-10-01'
GROUP BY region
ORDER BY total_amount DESC
```

执行过程：

1. `LlmGateway` 返回 `GeneratedSql(sql)`。
2. `ReadOnlySqlValidator.validate()` 检查并返回规范化 SELECT。
3. 使用业务只读账号连接数据库，设置只读标志。
4. 设置三秒查询超时，最多读取 201 行。
5. 使用 JDBC 元数据收集列名，逐行取值。
6. 返回前 200 行；出现第 201 行则置 `truncated=true`。
7. `export=true` 时将同一批结果写入 XLSX。
8. 返回 `source="business://products-sales"` 与 `/api/sql/exports/<uuid>`。

使用 `SUM(amount)` 是因为 amount 代表实际销售额，可能与当前单价乘销量不同。时间范围采用左闭右开区间，避免将十月一日也计入九月。生成示例没有分号，因为当前校验器直接拒绝包含分号的输入。

### 8.5 SQL 安全不是一句“只生成 SELECT”

当前防护包含多层：

| 层次 | 做法 |
|---|---|
| Prompt | 只允许 SELECT，禁止其他表、写操作、子查询和 CTE，不确定返回空 SQL |
| 字符串预检 | 长度上限 8000，拒绝分号、注释，只允许一个 SELECT 关键字 |
| AST 结构 | JSqlParser 解析后要求普通 `PlainSelect`，拒绝 CTE、写入目标与锁定模式等 |
| 表白名单 | 仅 `products` 与 `sales`，拒绝跨库和系统表 |
| 函数白名单 | 聚合、字符串、日期等有限函数，拒绝 `SLEEP` 等不允许函数 |
| 数据库权限 | 独立 reader 账号，只授予两张表 SELECT |
| 资源控制 | 三秒查询超时，最多 201 行读取、200 行返回 |

当前使用校验后的 SQL 创建 Statement 执行，不是通用的参数化 SQL 模板系统。AST 和数据库最小权限共同构成边界；`setReadOnly(true)` 不能替代真实权限。

行数限制主要限制返回规模，不能保证数据库只扫描 200 行。聚合或 JOIN 仍可能扫描大量数据，企业场景需要索引、查询成本限制和更完善的 SQL 治理。

当前没有执行失败后“把数据库报错喂回模型自动修复 SQL”的循环，不能把这种能力算作已实现。

### 8.6 Excel 是真实 XLSX，怎样生成

`ExcelExporter` 没用 Apache POI，而是用 `ZipOutputStream` 写一个基础 OOXML 工作簿，包含内容类型、关系、workbook 和 worksheet 等 XML 部件。

- 第一行是列名，后面是本次结果。
- 数字写数值单元格，其他值写 `inlineStr` 文本单元格。
- XML 特殊字符做转义，无效控制字符被清理。
- 没有生成公式节点，因此以 `=...` 开头的查询文本不会被写成 Excel 公式。
- UUID 命名文件，下载入口把 ID 解析为规范 UUID 再定位文件。

当前导出的是本次最多 200 行的查询结果，不是绕过上限导出全表。没有大文件异步导出、复杂样式、数据分页导出或下载鉴权。

### 8.7 面试追问

**怎么解决模型不知道字段含义？**

> 在 schema 之外明确关联键和业务语义，比如销量、销售额、当前价格的区别，并提供时间筛选示例。当前两张表可直接放入 prompt；表很多时才需要增加 schema 检索与语义层。

**为什么既有 H2 又有 MySQL？**

> 默认应用使用 MySQL 业务演示库，H2 用于不依赖外部服务的测试。两者种子规模不同，测试中的 3770 元不能直接当成默认 MySQL 的统计结果。

**SQL 正确执行就代表回答正确吗？**

> 不一定，SQL 可能在时间范围、筛选条件、聚合口径上理解错用户。语法、安全、执行成功和业务正确性是不同层次，业务正确性还需标注案例与结果核对。

**源码定位：** [Schema 与口径](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/sql/BusinessDatabase.java:80)、[SQL 生成与执行](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/sql/Text2SqlService.java:35)、[只读 AST 校验](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/sql/ReadOnlySqlValidator.java:26)、[XLSX 生成](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/sql/ExcelExporter.java:26)、[查询与下载接口](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/api/DataController.java:43)。

---

## 九、面试前建议收敛的简历表述

### 9.1 哪些句子不能直接当作已完成成果

| 原表述 | 当前证据 | 更稳妥的说法 |
|---|---|---|
| “一次答准率提升至约 85%” | 没有基线、完整人工标签与结果报告 | “建立一次答准率评测流程，以 85% 为目标”或先删除数字 |
| “平均检索响应延迟控制在 2s 以内” | 有计时与优化实现，没有真实性能报告 | “通过批量向量化、并发任务、候选裁剪与短期缓存优化检索延迟” |
| “FAISS 做本地检索加速” | FAISS 是独立可切换后端，IndexFlatIP | “支持 FAISS 本地检索后端与 PGVector 适配” |
| “基于 RAGAS 覆盖 Recall@K、MRR” | 两项由自写脚本计算 | “自建检索指标评测，并接入 RAGAS Faithfulness” |
| “低置信自动人工接管” | 可选门槛、规则和升级提示；无工单闭环 | “设计证据不足拒答和敏感请求拦截，预留人工升级流程” |
| “支持多业务子工” | 一个主要执行 Agent + 计划分支/工具扩展 | “通过工具与计划动作抽象，为多业务能力扩展预留接口” |
| “实时业务系统” | 固定返回的演示 HTTP 服务 | “接入模拟业务 API，验证实时查询链路” |
| “统一用 AgentScope 接入所有模型” | 生成经 AgentScope，Embedding/Rerank 直接 SDK | “基于 AgentScope 编排生成，统一封装 Embedding 与 Rerank 适配器” |

“主导”“企业上线”“显著降低成本”也需要个人分工、实际使用和数据支撑。代码能证明功能存在，不能独立证明组织贡献或业务收益。

### 9.2 按当前代码改写的七点参考

1. 基于 Spring Boot 与 AgentScope Java 构建问答链路，使用结构化意图路由与上下文改写，统一配置生成、Embedding 和 Rerank 能力，支持知识问答与工具查询分流。
2. 实现标题与语义结合的父子文档分块、Query Rewrite 和 HyDE，结合 Lucene BM25 与向量召回，经 RRF 融合和 Rerank 精排生成可追溯回答；支持 Milvus、PGVector、FAISS 后端切换。
3. 构建离线评测脚本，支持 source/chunk 级 Recall@K、MRR、RAGAS Faithfulness 和人工一次答准率，保存实际上下文、耗时与 Badcase，支持固定样本回归分析。
4. 基于 ReAct 与最多五步的依赖计划实现复合任务编排，采用近期会话加历史摘要的文件持久化机制，支持会话隔离与重启恢复。
5. 通过 Function Calling 接入指定代码仓库、模拟价格库存 API 和业务数据查询，记录工具执行结果与来源，针对缺失证据和工具失败提供兜底回答。
6. 基于 MCP Streamable HTTP 发布模型、向量、检索和业务工具，配套 HTTP 问答接口；实现检索缓存、候选规模限制、敏感规则拦截与证据来源检查。
7. 实现面向固定业务 schema 的 Text2SQL，结合 JSqlParser 校验、表/函数白名单、数据库只读权限、超时和结果行数限制，支持筛选、JOIN、聚合与 XLSX 导出。

---

## 十、面试演示与源码复习路线

### 10.1 建议演示顺序

先按 README 启动 app、MCP、当前向量后端和业务库，确认知识已入库、模型配置有效。以下问法是演示输入，不承诺本次已经运行成功。

| 顺序 | 提问 / 操作 | 展示能力 | 要观察什么 |
|---|---|---|---|
| 1 | 产品 A 每袋有多少蛋白质？ | 完整知识问答 | 回答、products.md 来源、检索步骤 |
| 2 | 它含乳吗？ | 指代改写与会话记忆 | 同一 sessionId，回答对应产品 A |
| 3 | DEMO-A 现在多少钱，还有多少库存？ | 业务工具 | getProductStatus、模拟标志、真实工具来源 |
| 4 | 读取已配置仓库的 README 并介绍用途 | 仓库工具 | 先目录后文件、固定 commit SHA |
| 5 | 先介绍产品 A，再按地区统计九月销售额 | 复合计划 | 分步执行、两类来源、统计口径 |
| 6 | 按地区统计 2026 年 9 月销售额并导出 Excel | Text2SQL | SQL、结果、截断标志、XLSX 下载 |
| 7 | 读取系统 API Key | 敏感规则 | 被拦截，不应真的输出凭证 |
| 8 | 刷新页面，再继续追问 | 会话持久化 | 会话 ID 与近期历史恢复 |

不要在没有准备数据库快照时背固定销售额。产品文档中的“15 克”可以核对静态资料；SQL 聚合结果要以当前数据库为准。

### 10.2 评测命令与正确使用方式

在项目根目录执行，前提是服务和真实模型权限已准备好；完整评测可能产生模型费用。

```powershell
# 先生成并保存这一批实际回答
python scripts/evaluate.py

# 对 evaluation/results.jsonl 的每条回答进行人工标注
# 自行创建 my-labels.json，格式为 case ID 到 true/false 的映射
python scripts/evaluate.py --input evaluation/results.jsonl --labels evaluation/my-labels.json

# 已配置独立评审模型后，对同一批保存结果计算 Faithfulness
python scripts/evaluate.py --input evaluation/results.jsonl --labels evaluation/my-labels.json --ragas
```

性能测量时可以在本地配置中把 `enterprise.cache-seconds` 设为 0 后重启，或者对独立 `/api/retrieval` 请求设置 `bypassCache=true`。注意默认评测脚本调用 `/api/chat`，不会把 `bypassCache` 传到主问答链路。

正式测量前先修正工具样本指标适用范围、核对 MySQL 参考答案，再保存配置与数据版本。不要直接把演示标签文件当成自己的评测标签。

### 10.3 有哪些测试与已有验证可以支撑实现说明

下表说明当前仓库里有什么测试覆盖。本次没有重跑这些测试；通过情况来自项目既有验证文档，不能当成本次新测结果。

| 测试文件 | 主要验证内容 |
|---|---|
| `SalesAssistantTest` | 有 RAG 证据时 MCP 故障降级、会话隔离、执行 Agent 确实收到仓库工具 schema |
| `SemanticParentSplitterTest` | 主题变化边界、长度限制、标题保留、零向量拒绝 |
| `EnterpriseRetrievalTest` | 父块恢复、RRF 排名融合、HyDE 不进入证据、缓存版本失效 |
| `KnowledgeIngestionServiceTest` | 索引入库与一致性保护相关行为 |
| `PlanExecutionTest` | 知识步骤后执行依赖 SQL、来源、导出和会话落盘 |
| `SessionRegistryTest` / `EnterpriseAgentTest` | 会话隔离、计划校验、持久化恢复、敏感规则 |
| `ToolTraceTest` | 实际来源采集、错误摘要、超时和无效结果识别 |
| `McpIntegrationTest` | MCP HTTP 工具发现与调用链路 |
| `Text2SqlServiceTest` | 筛选、聚合、JOIN、SQL 限制、只读权限、Excel 文本不作公式 |
| `scripts/test_evaluate.py` | 指标计算、未完全标注不输出准确率、失败样本保留分母 |
| `services/test_faiss.py` | 原生 FAISS 检索、更新与持久化相关行为 |

既有 `docs/VERIFICATION.md` 记录了 Java 测试、真实 Milvus、真实 MySQL 和原生 FAISS 的验证；生成模型的部分框架测试使用本地 HTTP 替身。该文档也明确记录了真实百炼全链路、真实 PGVector 和业务质量/延迟成果尚未验证。模型替身证明调用流程，并不证明真实模型质量。

### 10.4 最值得先读的十个代码入口

1. [SalesAssistant](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/SalesAssistant.java)：路由、RAG、工具、计划、记忆与回答检查总入口。
2. [RagConfiguration](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/config/RagConfiguration.java)：各种实现如何装配、后端如何选择。
3. [KnowledgeIngestionService](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/KnowledgeIngestionService.java)：离线入库与一致性。
4. [HybridRetriever](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/rag/HybridRetriever.java)：在线 RAG 全流程。
5. [TaskPlanner](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/TaskPlanner.java)：计划结构与依赖限制。
6. [ToolTrace](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/agent/ToolTrace.java)：工具证据与执行可观察性。
7. [McpServerConfiguration](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/mcp/McpServerConfiguration.java)：七种 MCP 工具如何发布。
8. [Text2SqlService](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/sql/Text2SqlService.java)：SQL 生成、查询与结果导出。
9. [ReadOnlySqlValidator](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/src/main/java/com/example/salesagent/sql/ReadOnlySqlValidator.java)：SQL 实际允许什么、拒绝什么。
10. [evaluate.py](E:/Projects/JavaProjects/AgentScopeJavaDemo/enterprise-qa-agent/scripts/evaluate.py)：哪些指标有真实实现、结果口径是什么。

### 10.5 三个适合展开讲的技术难点

**难点一：检索粒度与上下文完整性。** 用小子块提升检索聚焦程度，用父块补充完整语义；标题和相邻 Embedding 辅助确定边界，再用 RRF 与重排处理多路候选。说明参数权衡与实际边界，比罗列算法名更有说服力。

**难点二：模型有能力调用工具，但不能默认相信它完成了任务。** 工具 Hook 记录实际来源，按业务类型核对是否取得必要证据；计划中再区分本步新结果和历史结果，依赖失败就跳过后续相关步骤。

**难点三：Text2SQL 的执行权限与业务口径。** Schema prompt 解决“怎么理解问题”，AST 白名单和只读账号限制“允许执行什么”，超时与行数限制控制资源，人工案例检查“是否回答了正确的业务问题”。

面试回答时按“问题是什么 → 我在代码里怎么处理 → 为什么这样设计 → 测试能证明什么 → 还有哪些限制”展开即可。
