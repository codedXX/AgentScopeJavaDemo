# 销售问答 Agent Demo

这是一个面向学习的 Java Demo：**Spring Boot + AgentScope + Milvus + Lucene BM25 + 百炼**。
只使用一套 Agent 框架，不引入 Spring AI Alibaba。详细架构、代码导读与接口说明见 [项目文档](docs/PROJECT.md)。

## 1. 你能学到什么

```text
文档 → 递归切分 → Embedding → Milvus + Lucene

问题 → 意图识别/追问改写
     → BM25 Top10 + Milvus Top10
     → 按 chunkId 合并去重（最多20条）
     → Reranker Top5
     → AgentScope 回答 / MCP 工具补充证据
     → 答案 + 来源 + 执行步骤 + 耗时
```

三个模型固定为 `qwen3.7-flash`、`qwen3.7-text-embedding`、`qwen3.7-text-rerank`。
Reranker 前只合并去重，不使用 RRF。两路原始检索分数不能直接比较。

## 2. 启动条件

- JDK 21、Maven 3.9+。
- Docker Desktop（Linux containers，Windows 通常使用 WSL2），预留足够内存运行 Milvus standalone 及其依赖。
- 阿里云百炼 API Key，所在地域有上述三个模型的调用权限。
- 能访问 Maven Central、百炼和 GitHub。公开仓库读取可不配置 GitHub Token，但受匿名限流。

示例知识和业务数据均为虚构；健康资料仅用于演示知识检索。

## 3. 启动步骤（PowerShell）

在项目根目录打开终端，先构建：

```powershell
mvn clean verify
docker compose up -d
docker compose ps
```

等待 Milvus 的 `standalone` 服务健康后继续。Compose 仅将端口暴露到本机，使用命名卷保存数据。
Windows 下重新打包前先退出正在运行该 JAR 的 Java 进程，否则文件锁可能导致 `repackage` 失败。

终端一启动 MCP 工具服务（不需要百炼 Key）：

```powershell
# 可选：提高公开 GitHub API 的限流额度
$env:GITHUB_TOKEN = "你的 GitHub Token"
java -jar target/sales-agent-demo-1.0.0.jar --spring.profiles.active=mcp-server
```

终端二启动问答服务：

```powershell
$env:DASHSCOPE_API_KEY = "你的百炼 API Key"
$env:DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/api/v1"
$env:MILVUS_URI = "http://localhost:19530"
java -jar target/sales-agent-demo-1.0.0.jar --spring.profiles.active=app
```

`.env.example` 是变量说明，Spring Boot 不会自动读取 `.env`。不要把真实 Key 写进 YAML 或提交仓库。
也可以使用 `mvn spring-boot:run "-Dspring-boot.run.profiles=app"` 和 `mcp-server` 两个 profile 启动。

终端三导入知识：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/knowledge/status
Invoke-RestMethod http://127.0.0.1:8080/api/knowledge/rebuild -Method Post
```

全量重建会替换 `sales_knowledge` collection 和 `data/lucene` 索引，只在本地演示数据上使用。重建期间拒绝知识检索；失败后保持未就绪，修复连接后重新执行。

## 4. 开始提问

启动问答服务后，在浏览器打开 **http://127.0.0.1:8080/** 即可使用中文工作台，无需安装前端依赖。

- 左侧选择或拖入 UTF-8 编码的 `.txt` / `.md` 文件（单文件最大 5 MB），点击“上传并入库”。
- 上传通过 `POST /api/knowledge/upload`（multipart 字段 `file`）保存到 `knowledge/uploads/<随机ID>/`，自动切分、调用 Embedding，并全量重建 Milvus 和 Lucene 索引；保留已有资料，同名文件独立保存。
- 重建期间暂停知识检索。失败后文件会保留，修复连接后点击“重建知识库 / 失败后重试”，无需重复上传。
- 右侧输入问题并发送，可连续追问、查看引用来源和处理步骤；“新对话”会开始新的会话。刷新页面会清空页面对话。
- 本版不解析 PDF、Word 或图片，请先转为 UTF-8 文本。大知识库的全量重建可能较慢，并会产生模型调用费用。

也可继续使用接口：

```powershell
$body = @{ sessionId = 'demo-001'; message = '演示蛋白营养粉 A 每袋有多少蛋白质？' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/chat -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

保持相同 sessionId 再问“它含乳成分吗？”可体验多轮对话。省略 sessionId 时返回新 ID。

更多可复制请求见 [requests.http](requests.http)，包括：

- “DEMO-A 现在多少钱，还有库存吗？”——MCP 调业务接口，回答应明确数据为模拟。
- “读取 redis-cache-demo 的 README 并介绍用途。”——MCP 读 GitHub 文件树和源码，来源固定到 commit SHA。
- “产品 B 的临床试验结果是什么？”——资料不足时说明无法确认。

## 5. 测试与评测

```powershell
# 不需要真实模型或 Milvus；包括临时端口上的真实 MCP HTTP 测试
mvn test

# 需要实际服务和凭证的集成测试
$env:MILVUS_URI = "http://localhost:19530"
mvn -Plive-it verify

# 服务已启动且知识库已重建后运行；会调用真实模型并产生费用
./scripts/evaluate.ps1
```

评测结果写入 `evaluation/results.jsonl`。脚本记录来源、错误和检索耗时；`correct` 需要人工按参考答案标注。
**85% 一次答准率、平均检索小于 2 秒只是目标，不是该 Demo 已测得的成果。** 真实验证状态见 [项目文档](docs/PROJECT.md)。

## 6. 建议阅读顺序

1. `config/DemoProperties.java`、`application.yml`：参数在哪里配置。
2. `rag/DocumentChunker.java`、`KnowledgeIngestionService.java`：文档如何变成两路索引。
3. `rag/HybridRetriever.java`、`bailian/BailianRerankClient.java`：召回如何合并、重排序结果如何映射。
4. `agent/SalesAssistant.java`：问题如何进入 Agent、检索和工具链路。
5. `mcp/McpServerConfiguration.java`、`tool/`：工具怎样通过 MCP 暴露与调用。

所有 Java 源码位于 `src/main/java/com/example/salesagent/`。
# AgentScopeJavaDemo
