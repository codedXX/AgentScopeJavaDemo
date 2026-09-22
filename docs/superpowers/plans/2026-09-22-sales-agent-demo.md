# 销售问答 Agent Demo 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个有中文注释、可以本地运行、便于理解完整流程的 Java 销售问答 Demo，覆盖知识入库、混合检索、重排序、多轮问答、工具调用和 MCP。

**Architecture:** Spring Boot 提供 HTTP 接口；AgentScope Java 负责模型驱动的多步骤执行与会话管理。知识库使用现成递归切分器、Milvus 向量检索和 Lucene BM25 检索，两路结果按分块 ID 合并去重后直接交给百炼 Reranker；证据不足时通过 MCP 工具补充仓库或业务信息。

**Tech Stack:** Java 21、Maven、Spring Boot、AgentScope Java、阿里云百炼、Milvus、Apache Lucene、LangChain4j 递归切分组件、MCP Java SDK/AgentScope MCP 集成。

**文档状态：** 本文是实施前确认的计划；实施产物与实际验证状态请查看 `docs/implementation-progress.md`、`docs/PROJECT.md`、`docs/VERIFICATION.md`。实施前目录为空，环境为 Java 21 和 Maven 3.9.14，未初始化 Git。

## 1. 全局约束与范围

- 不引入 Spring AI Alibaba，也不引入 Spring AI 的 Agent、模型或会话体系。
- 唯一 Agent 运行框架使用 AgentScope Java；LangChain4j 仅复用文档切分组件。
- 模型名称固定为 `qwen3.7-flash`、`qwen3.7-text-embedding`、`qwen3.7-text-rerank`，通过配置读取，不静默更换模型。
- Milvus 保存分块文本、元数据和稠密向量；Lucene 提供 BM25 关键词召回。
- 使用递归切分，目标块长 500 字符、重叠 80 字符，作为可配置初值。
- 两路各召回 Top10，按 `chunkId` 去重后最多 20 条，Rerank 后选 Top5；不使用 RRF，不直接比较 BM25 与向量原始分数。
- GitHub 仓库固定为 `https://github.com/codedXX/redis-cache-demo`。
- 示例知识覆盖产品、健康科普、销售业务；全部标注为演示资料，不冒充真实企业政策或产品数据。
- 提供一个本地模拟业务 HTTP 接口，演示库存/价格查询，并明确标记模拟数据。
- 核心流程、配置参数、框架调用边界和异常处理添加中文注释；优先复用 SDK，避免自写基础算法或 MCP 协议。
- 不实现前端、登录系统、多租户、分布式会话、高可用和复杂文档解析。首版知识文件为 UTF-8 Markdown/TXT。
- 85% 一次答准率和平均检索延迟小于 2 秒是待评测目标；只有测量后才能报告结果。

## 2. 架构与数据流

### 2.1 入库流程

```text
本地 Markdown/TXT
  → 清洗换行、空白，保留标题和来源
  → 现成递归切分器（500 / 80）
  → 生成稳定 chunkId
  → qwen3.7-text-embedding 批量向量化
  ├─ Milvus：chunkId、文本、来源、向量
  └─ Lucene：相同 chunkId、文本、来源
  → 两路入库成功后允许问答检索
```

复用 `DocumentSplitters.recursive(...)` 或所锁定版本的等价现成 Java API。实施时检查该 API 的长度单位与中文切分行为，不把 token 数当作字符数，不自行实现一个同名 `RecursiveCharacterTextSplitter`。

为保持简单，首版只提供全量重建：暂停检索，清空两路旧索引，分批建立新索引；任何一步失败则保持知识库未就绪，允许重新执行重建。它会造成短暂不可用，属于 Demo 的明确取舍。

### 2.2 问答流程

```text
POST /api/chat { sessionId, message }
  → 根据历史将追问改写成独立问题，并识别意图
  → 知识问题：固定先执行 RAG
      ├─ Lucene BM25 Top10
      └─ query Embedding → Milvus COSINE Top10
      → 按 chunkId 合并去重
      → qwen3.7-text-rerank
      → Top5 证据
  → AgentScope 根据证据与意图执行有界工具循环
      ├─ 代码问题/知识不足：GitHub MCP 工具
      └─ 价格库存等实时问题：业务 MCP 工具
  → 生成答案、引用来源、执行步骤与耗时
```

闲聊可以跳过检索；明确的仓库问题可直接调用仓库工具；价格库存问题必须获取业务工具结果，不能用静态知识代替实时结果。知识问题先检索的规则由应用流程保证，避免仅靠提示词导致模型漏检索。

“证据不足”包含：没有候选、重排序分数低于可配置门槛、候选内容无法支持回答。分数门槛需通过样例标注校准，不将其解释为正确率。首版门槛默认关闭，仅以空结果和模型对证据充分性的判断触发补充，仍可由配置启用分数门槛。

### 2.3 MCP 边界

使用同一 Maven 项目、两个启动配置，避免微服务拆分：

- `mcp-server`：监听 8081，通过正式 MCP SDK 暴露仓库和业务工具，同时提供模拟业务 HTTP 接口。
- `app`：监听 8080，提供问答和知识库管理接口；AgentScope 作为 MCP Client 连接 8081，并将发现的工具注册到 Toolkit。

模型调用仍走百炼 SDK/API；MCP 标准化的是工具访问。问答 REST API 与 MCP 传输端点是不同接口，不能把普通 REST 调用称为 MCP。使用双方版本均支持的 HTTP MCP 传输，优先 Streamable HTTP，并在第一项任务中完成兼容性验证。

## 3. 工程结构与职责

基础包名 `com.example.salesagent`；以下 Java 文件均位于 `src/main/java/com/example/salesagent/`。

| 文件/目录 | 职责 |
|---|---|
| `pom.xml` | 锁定依赖和测试插件版本 |
| `compose.yaml` | 按 Milvus 官方 standalone 模板启动所需服务，固定镜像版本 |
| `.env.example`、`.gitignore` | 环境变量示例；忽略密钥、本地索引和构建文件 |
| `SalesAgentApplication.java` | Spring Boot 入口 |
| `config/DemoProperties.java` | 模型、检索、索引、超时和工具参数 |
| `config/AgentConfiguration.java` | AgentScope 模型、Toolkit、MCP Client 配置 |
| `model/KnowledgeChunk.java`、`SearchHit.java` | 分块和召回结果 |
| `model/RagResult.java`、`ChatRequest.java`、`ChatResponse.java` | RAG、请求和响应契约 |
| `bailian/BailianEmbeddingClient.java` | 复用 SDK 完成向量化，检查维度与批次响应 |
| `bailian/BailianRerankClient.java` | 重排序，按返回 index 映射原始分块 |
| `rag/DocumentChunker.java` | 清洗文档并调用现成递归切分器 |
| `rag/MilvusChunkStore.java` | 建表、写入、向量检索、清空 |
| `rag/LuceneKeywordIndex.java` | 中文分析、BM25 索引与检索 |
| `rag/KnowledgeIngestionService.java` | 两路重建与就绪状态 |
| `rag/HybridRetriever.java` | 双路召回、按 ID 去重、调用 Reranker |
| `agent/SalesAssistant.java` | 意图、追问改写、检索和 Agent 执行流程 |
| `agent/SessionRegistry.java` | 每会话独立 Agent/Memory、同会话串行执行和过期清理 |
| `tool/GitHubRepositoryTools.java` | 仓库文件树、文件读取，保留来源与 commit SHA |
| `tool/BusinessTools.java` | 调用本地业务 HTTP 接口 |
| `mcp/McpServerConfiguration.java` | 使用 SDK 发布工具 |
| `api/ChatController.java`、`KnowledgeController.java` | 问答、全量重建与状态查询 |
| `api/DemoBusinessController.java`、`ApiExceptionHandler.java` | 模拟业务接口和统一错误响应 |
| `src/main/resources/application*.yml` | 公共配置和两个启动配置 |
| `src/main/resources/prompts/` | 意图识别、证据回答、工具使用提示词 |
| `knowledge/` | 产品、健康和业务 Markdown 示例 |
| `evaluation/cases.jsonl` | 问题、期望证据、参考答案和工具期望 |
| `scripts/evaluate.ps1`、`requests.http`、`README.md` | 评测、调用示例与中文学习文档 |

默认向量维度拟用 1024，须在真实模型探针中确认；若模型/SDK 不支持指定维度，则采用响应实际维度建表并同步配置。维度变化必须重建 collection，不允许混写。

## 4. 应用接口与内部契约

```java
record KnowledgeChunk(String chunkId, String text, String source, int ordinal) {}
record SearchHit(KnowledgeChunk chunk, double score, String channel) {}
record RagResult(List<SearchHit> evidence, boolean insufficient, long retrievalMs) {}
record ChatRequest(String sessionId, String message) {}
record ChatResponse(String sessionId, String answer, List<String> sources,
                    List<String> steps, long retrievalMs, long totalMs) {}

// 以下是本项目拟定义的封装接口，不声称是框架原生签名。
List<KnowledgeChunk> DocumentChunker.split(Path file);
List<float[]> BailianEmbeddingClient.embed(List<String> texts);
List<SearchHit> BailianRerankClient.rank(String query, List<KnowledgeChunk> chunks, int topK);
void MilvusChunkStore.reset(int dimension);
void MilvusChunkStore.upsert(List<KnowledgeChunk> chunks, List<float[]> vectors);
List<SearchHit> MilvusChunkStore.search(float[] queryVector, int topK);
void LuceneKeywordIndex.reset();
void LuceneKeywordIndex.upsert(List<KnowledgeChunk> chunks);
List<SearchHit> LuceneKeywordIndex.search(String query, int topK);
void KnowledgeIngestionService.rebuild();
boolean KnowledgeIngestionService.isReady();
RagResult HybridRetriever.retrieve(String query);
ChatResponse SalesAssistant.chat(ChatRequest request);
```

| HTTP 接口 | 行为 |
|---|---|
| `POST /api/knowledge/rebuild` | 同步重建小型示例知识库；成功返回分块数与耗时；并发重建返回 409 |
| `GET /api/knowledge/status` | 返回 ready、分块数、上次重建时间 |
| `POST /api/chat` | sessionId 可空；空时生成新会话；返回答案、来源与步骤 |
| `GET /demo/business/products/{sku}` | 8081 上返回带 `demo=true`、时间戳的模拟价格库存 |

运行轨迹仅包含“检索、调用了哪个工具、耗时”等可观察事件，不输出模型内部思维链。sources 必须从真实检索/工具结果中校验，不能接受模型编造的来源。

## 5. 实施任务

### Task 1：验证依赖、百炼模型与 MCP 最小链路

**文件：** `pom.xml`、`compose.yaml`、`.env.example`、`.gitignore`、入口、配置文件、`README.md`。

- [ ] 查阅 AgentScope Java、百炼 Java SDK、Milvus Java SDK、MCP SDK 官方文档，锁定一组支持 Java 21 的已发布版本，不使用浮动版本或 SNAPSHOT。
- [ ] 验证 AgentScope 原生模型适配器是否支持指定生成模型的工具调用，优先复用原生适配器；Embedding/Rerank 优先复用百炼 SDK，SDK 无对应能力时仅封装官方 HTTP API。
- [ ] 为三个指定模型分别建立最小请求探针，确认 endpoint、地域、认证、Embedding 维度、Rerank 返回 index 与分数；没有 API Key 时记录“未验证”，不伪造成功。
- [ ] 建立 MCP 的工具发现和单次调用探针，确认 HTTP 传输、启动配置隔离、超时和关闭资源行为。
- [ ] 复制并精简官方 Milvus standalone Compose，保留该版本必要的依赖服务、持久化卷和健康检查。
- [ ] 使用 `mvn -DskipTests package` 验证依赖与编译；使用 `docker compose config` 检查 Compose。

**验收：** 依赖可解析；两个启动配置职责独立；三个模型和 MCP 的实际验证结果明确记录。API 不兼容时先调整最薄适配层，不加入第二套 Agent 框架。

### Task 2：文档递归切分与双索引入库

**文件：** `model/KnowledgeChunk.java`、`rag/DocumentChunker.java`、两个索引类、入库服务、Embedding Client、知识 API、`knowledge/*.md`。

- [ ] 创建产品、健康、业务三份小型示例资料，保留明确标题和来源。
- [ ] 编写 `DocumentChunkerTest`，覆盖长中文段落、空文件、稳定 ID 和来源保留；先运行并确认失败，再实现包装器。
- [ ] chunkId 使用相对来源路径、分块序号和分块内容的 SHA-256，复用 JDK `MessageDigest`。相同文本来自不同文件时保留各自出处。
- [ ] 建立 Milvus collection：字符串主键、文本、来源、序号、FLOAT_VECTOR；使用 COSINE 度量和 SDK 支持的索引配置。
- [ ] Lucene 使用现成中文分析器，例如 SmartChineseAnalyzer，索引与查询保持一致；复用 BM25Similarity。
- [ ] 实现全量重建：通过锁排斥检索和重复重建；开始后 ready=false；成功写入、flush/load/refresh 并验证可查询后 ready=true。
- [ ] 批量 Embedding，校验返回数量、维度、有限数值；失败不得发布半成品就绪状态。
- [ ] 增加真实 Milvus 集成测试：写入后可检索、重复重建不重复、重建失败时知识问答返回明确 503。

**验收：** 相同分块 ID 同时存在于两路索引；重建后新增内容可检索，已删除文档不会遗留。

### Task 3：混合检索与 Reranker

**文件：** `SearchHit.java`、`RagResult.java`、`HybridRetriever.java`、`BailianRerankClient.java`。

- [ ] 创建 `HybridRetrieverTest`，使用可控的索引与模型替身，验证 BM25 `[A,B,C]` 和向量 `[B,D,A]` 只提交四个唯一候选给 Reranker。
- [ ] 先运行 `mvn -Dtest=HybridRetrieverTest test` 确认测试失败，再实现检索服务。
- [ ] 执行两路 Top10 查询；首版采用清晰的顺序调用，先保证流程可读与可测，测得延迟不达标后才考虑并发。
- [ ] 使用 `LinkedHashMap<String, KnowledgeChunk>` 按 chunkId 合并，禁止拿两种召回分数直接排序。
- [ ] 调用 Reranker，将响应中的 index 映射回传入候选，而不是误当作 chunkId；按相关性取 Top5。
- [ ] 空候选直接返回 insufficient=true；Reranker 故障返回明确上游错误，不把未重排候选冒充重排结果。
- [ ] 追加乱序 index、非法 index、重复 index、少于 Top5、空候选测试；验证最终来源映射不丢失。

测试核心断言示例：

```java
assertEquals(4, submittedChunks.size());
assertEquals(4L, submittedChunks.stream().map(KnowledgeChunk::chunkId).distinct().count());
assertEquals("D", ranked.getFirst().chunk().chunkId()); // 模拟 rerank 将 D 排第一
```

**验收：** 数据路径为双路召回→按 ID 去重→模型重排序；无 RRF、自写 BM25、自写向量相似度算法。

### Task 4：GitHub、业务工具与真实 MCP 调用

**文件：** `tool/*.java`、`mcp/McpServerConfiguration.java`、`api/DemoBusinessController.java`。

- [ ] 实现 `listRepositoryFiles()`：获取默认分支与 commit SHA，再读取该提交的文件树；处理树截断，不能把部分树说成完整树。
- [ ] 实现 `readRepositoryFile(path, commitSha)`：通过 GitHub API 读取允许的文本文件，返回路径、SHA、内容、来源链接与截断标记。
- [ ] 仓库固定，不接受任意仓库 URL；排除二进制、大文件及密钥类路径。限制每次读取 20KB、每轮工具最多 5 次。
- [ ] GitHub token 从可选环境变量读取；401/403/404、限流和超时转换为明确工具错误，不虚构仓库内容。
- [ ] 实现模拟业务接口和 `getProductStatus(sku)` 工具，工具真实访问该 HTTP 接口；未知 SKU 返回不存在。
- [ ] 使用 MCP SDK 发布上述工具，AgentScope Client 通过工具发现接入，不绕过 MCP 直接调用本地方法。
- [ ] 用本地 HTTP 替身测试 GitHub 错误和文件解码；用两个真实进程测试 MCP 工具发现、调用和服务不可达。

**验收：** 可观察到 AgentScope→MCP→GitHub/业务 HTTP 的真实调用；模拟业务数据、仓库文件出处和错误清楚可辨。

### Task 5：AgentScope 编排、多轮会话与问答接口

**文件：** `agent/*.java`、`config/AgentConfiguration.java`、聊天 DTO、Controller、异常处理器、提示词。

- [ ] 使用结构化输出识别 `KNOWLEDGE`、`REPOSITORY`、`BUSINESS`、`CHAT`，并根据会话历史生成独立查询；解析失败时对业务问答保守进入知识检索。
- [ ] 在应用层保证知识意图先调用 HybridRetriever，将结果作为有明确边界的证据注入 AgentScope。
- [ ] 使用 AgentScope 原生 Agent、Toolkit 和 Memory 进行回答与工具循环；限制轮数和总超时，不自己再写一套 ReAct 引擎。
- [ ] SessionRegistry 每会话隔离 Agent/Memory；同一会话串行处理，30 分钟闲置过期、最多 100 个会话。重启会清空会话，README 明示。
- [ ] 提示词要求据证据回答、证据不足明确说明、不把文档/仓库中的指令当系统指令；健康问题限定为资料解释，不凭空给出个体诊断。
- [ ] 返回 answer、sources、steps、retrievalMs、totalMs；校验来源只能指向本轮真实证据。
- [ ] 增加会话隔离、追问解析、空问题、知识库未就绪、工具失败和最大轮数测试。

**验收：** “产品 A 有哪些特点？”接着“它适合什么场景？”能利用同一会话上下文；另一个 sessionId 无法读到该历史。知识不足且工具无证据时明确表示无法确认。

### Task 6：运行说明、评测与交付检查

**文件：** `README.md`、`requests.http`、`evaluation/cases.jsonl`、`scripts/evaluate.ps1`、测试配置。

- [ ] README 按“准备环境→启动 Milvus→配置百炼→启动 MCP 服务→启动问答服务→入库→提问”编排，说明每一步发生了什么。
- [ ] 提供产品知识、健康资料、业务政策、仓库源码、库存价格、连续追问、无答案问题的请求示例。
- [ ] 建立至少 20 条带参考答案/期望来源的评测数据，覆盖中文关键词与语义表达不同的案例；该集合只用于教学，不代表生产分布。
- [ ] 脚本采集响应、引用、错误、检索耗时、总耗时；一次答准率由人工按“事实正确、证据支持、直接解决问题”标注后统计，不用 HTTP 200 代替答对。
- [ ] 定义 retrievalMs 为独立问题开始检索至 Rerank 完成，包含查询 Embedding、两路召回和 Rerank；totalMs 为 HTTP 请求整体耗时。统计平均值与 P95，单列失败率与超时，区分冷启动和预热。
- [ ] 运行 `mvn test`；具备服务和凭证时运行 `mvn -Plive-it verify`，该 profile 由项目显式配置，只运行需要 Milvus/百炼/MCP 的集成测试。
- [ ] 使用实际请求验证来源引用、MCP 调用和会话隔离；缺少 API Key 或 Docker 时说明未完成的真实联调项，不声称全链路已验证。
- [ ] 检查依赖树、注释、样例配置和日志，不包含 Spring AI Alibaba、明文密钥或无依据的 85%/2s 成果描述。

建议启动命令（实施后须逐条验证）：

```powershell
docker compose up -d
$env:DASHSCOPE_API_KEY = "你的百炼 API Key"
# 终端一；根据实际所用地域在 application.yml 中配置 endpoint
mvn spring-boot:run "-Dspring-boot.run.profiles=mcp-server"
# 终端二；同样配置所需环境变量
mvn spring-boot:run "-Dspring-boot.run.profiles=app"
```

**验收：** 新读者可按 README 跑通；有真实联调记录；能够解释“递归切分→Embedding→Milvus/Lucene→去重→Rerank→Agent/工具→回答”。

## 6. 异常与运行边界

| 场景 | 首版行为 |
|---|---|
| 空问题、问题超过 2000 字符 | HTTP 400，提示修正输入 |
| 知识库未完成入库或正在重建 | 知识问答返回 HTTP 503，不返回旧新混合证据 |
| Milvus 或 Lucene 不可用 | 明确失败，不悄悄把单路召回称作混合检索 |
| Embedding/Rerank API 失败 | 返回明确上游错误；限流/短暂服务错误最多重试 2 次并退避，遵循总请求时限 |
| 工具服务故障、GitHub 限流 | 返回工具错误；有充分已有证据时说明限制后回答，无证据时说明无法确认 |
| 生成超时或工具循环达到上限 | 终止执行并返回可理解的失败信息，不无限循环 |
| 重启、索引版本不一致 | 检查两路记录数与本地入库清单，不满足一致性要求则 ready=false 并提示重建 |

配置初值：单次外部调用超时 20 秒、整轮问答超时 90 秒；重试与工具循环不得突破总时限。管理接口及模拟业务只面向本地演示，Docker 端口绑定 localhost；公网部署的鉴权与权限控制不在首版范围。

## 7. 验收清单与实现顺序

- [x] 仅使用 AgentScope 作为 Agent 框架。
- [ ] 三个模型名与用户指定一致，真实调用结果有记录。
- [x] 文档递归切分复用库，Milvus 持久化向量。
- [x] 中文 BM25 与语义向量两路均真实执行（Lucene 与 Milvus 单独实测；真实 Embedding 仍需 Key）。
- [x] 按 chunkId 去重后直接 Rerank，无 RRF（程序链路测试通过；真实模型仍需 Key）。
- [x] 实际读取指定 GitHub 仓库，工具来源可追踪。
- [x] MCP Client/Server 有真实工具发现和调用。
- [x] 会话隔离、追问、失败处理通过验证。
- [x] 中文注释、样例数据、启动指南和评测说明齐全。
- [x] 正确率与延迟报告清楚区分目标和实测值。

顺序为 Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6。先验证框架和外部接口，再逐段接通链路；每项完成后运行该项针对性测试。本次交付仅为本计划，后续实施按任务勾选推进。

## 8. 实施时核对的官方资料

- AgentScope Java：https://github.com/agentscope-ai/agentscope-java
- 百炼生成模型：https://help.aliyun.com/zh/model-studio/qwen3-7-flash
- 百炼 Embedding/Rerank：https://help.aliyun.com/zh/model-studio/embedding-rerank-model
- LangChain4j 文档切分：https://docs.langchain4j.dev/tutorials/rag/
- Milvus 文档与部署：https://milvus.io/docs
- Lucene 文档：https://lucene.apache.org/core/
- MCP Java SDK：https://github.com/modelcontextprotocol/java-sdk

依赖版本和底层 SDK 方法签名属于 Task 1 的验证产物；本计划中的项目接口是设计契约，不把未经编译验证的外部 API 写成已可用实现。
