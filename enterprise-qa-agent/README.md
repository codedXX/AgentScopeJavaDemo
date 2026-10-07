# 企业智能问答 Agent

在当前销售问答 Demo 的基础上扩展，独立使用 Spring Boot、AgentScope Java、百炼、Milvus、Lucene BM25 和 MCP。新增能力均有实际实现；模型质量与延迟需要真实数据评测，85% 答准率和平均检索 2 秒是验收目标。

## 1. 与截图对应的能力

| 能力 | 实现 |
|---|---|
| 意图识别、Query Rewrite | AgentScope 结构化输出，结合近期历史及早期摘要改写追问 |
| 语义分块、父子文档 | 按标题分节、递归获得语句组，通过相邻 Embedding 相似度确定父块边界；子块召回后展开父块 |
| HyDE | 模型生成假设文档，仅用于向量召回，不能作为生成证据 |
| 混合检索 | 原问题及最多 2 个改写的 BM25/向量召回，HyDE 向量召回，RRF 融合，再 Rerank TopK |
| 向量后端 | 默认 Milvus；可切 PGVector；FAISS 由本机 Python 服务提供真实 IndexFlatIP 与持久化 |
| ReAct / Function Calling | AgentScope 工具循环，读取仓库、查询价格库存、Text2SQL |
| Plan-and-Execute | 复合任务先生成并验证最多 5 步的依赖计划，再执行各步，失败依赖跳过，最后汇总 |
| 长程交互 | 最近约 10 轮 + 早期摘要，原子文件持久化，重启恢复；浏览器保留会话 ID |
| 图片提问 | 选择、粘贴或拖入 PNG/JPEG；最多 4 张，每张 5 MB、1600 万像素以内；支持只发图片、历史预览与带图追问 |
| MCP | Streamable HTTP 暴露仓库、业务、生成模型、Embedding、RAG、数据查询 |
| Text2SQL | 自然语言生成只读 SQL，筛选、JOIN、GROUP BY 统计，真实执行，支持 XLSX 下载 |
| 兜底 / 敏感问题 | 规则拦截、知识不足工具补充、来源校验、工具失败兜底、SQL AST 与数据库只读权限 |
| 评测 | source/chunk 级 Recall@K、MRR、RAGAS Faithfulness、人工一次答准率、Badcase 文件 |

### 图片提问接口

页面输入框旁的“＋ 图片”可选择图片，也可粘贴或拖入图片。发送失败时保留问题和附件，重试复用已经上传的附件 ID。

1. `POST /api/images`：`multipart/form-data`，字段为 `sessionId` 和 `file`。返回 `{ "id": "图片UUID", "url": "/api/sessions/会话ID/images/图片UUID", "contentType": "image/png", "size": 1234 }`。
2. `POST /api/chat`：JSON 示例为 `{ "sessionId": "同一会话ID", "message": "描述这张图", "imageIds": ["图片UUID"] }`。允许 `message` 为空，但文字和图片至少提供一种；原来的纯文字请求仍兼容。
3. `GET /api/sessions/{sessionId}/images/{imageId}`：读取该会话图片。会话历史的用户消息包含 `imageIds`，用于刷新后的图片展示。

附件保存在 `enterprise.image-dir`（默认 `./data/images`），按会话隔离。请与 `./data/sessions` 一起保留以支持重启恢复；目前附件持久保留，不自动过期。此演示沿用会话 ID 访问方式，未增加用户登录鉴权。

图片以 AgentScope `ImageBlock` 和 Base64 图片数据进入意图分类、回答及图片计划步骤。纯图片分析走 `IMAGE` 路由，无需知识库就绪或 MCP；实时价格、库存和数据库统计继续要求相应工具证据。近期约 10 轮保留原图引用，较早图片在历史压缩时提取文字概要。图片附件不会自动进入共享知识库。

## 2. 启动（PowerShell）

需要 JDK 21、Maven 3.9+、Docker Desktop、本机 MySQL 8.0+ 和有模型权限的百炼 API Key。进入本文件所在目录运行。

```powershell
mvn clean verify
docker compose pull
docker compose up -d --wait
```

本项目与原 Demo 的端口、collection、Docker 项目和数据目录相互独立。

问答服务默认端口为 8085，MCP 服务为 8086，服务之间的调用地址已同步配置。本机此前因 Windows 动态端口范围设置导致端口被保留，已恢复动态范围到 49152–65535 并重启；8085、8086 的绑定检查已通过。

默认 Milvus 采用官方单容器部署方式：内置 etcd 保存元数据，本地存储保存向量与索引，只需拉取 `milvusdb/milvus:v2.6.6`。配置文件位于 `docker/milvus`，数据持久化到 `enterprise-qa-agent_milvus-embedded-data` 命名卷。`docker compose ps` 显示 `standalone` 为 `healthy` 后即可启动问答服务。这适用于本机学习环境；不需要单独下载 MinIO。此卷不复用旧版外部 etcd / MinIO 部署的数据。

在项目根目录创建 `application-local.yml`，直接填写百炼 Key。两个服务启动时都会自动读取此文件；此文件已加入 `.gitignore`。修改 Key 后重启服务即可，无需重新打包。也可以继续使用 `DASHSCOPE_API_KEY` 环境变量，但本地配置中的值优先。

```yaml
demo:
  bailian:
    api-key: "你的Key"
```

### 初始化 MySQL 业务库

默认连接本机 `127.0.0.1:3306`，数据库为 `enterprise_qa_demo`。首次运行先复制管理员配置，填写 MySQL 管理员密码，再执行初始化。管理员密码只供本地脚本使用，不交给问答服务；实际文件已加入 `.gitignore`。已有配置文件时直接编辑即可。

```powershell
Copy-Item mysql-admin.properties.example mysql-admin.properties
notepad mysql-admin.properties
# 填写 password= 后的密码，不加引号，保存后执行：
./scripts/init-mysql-demo.ps1
```

脚本复用打包 JAR 中的 MySQL JDBC 驱动，不需要安装 MySQL 命令行客户端。建库和造数 SQL 位于 `scripts/mysql-demo.sql`：12 件商品、364 条模拟销售记录，覆盖 2026 年 4—9 月、6 个地区和营养/办公/数码/日用四类商品，初始销售总额为 396,382.00 元。重复导入会跳过已有主键，不清空或覆盖现有记录。

应用使用 `enterprise_qa_reader` 只读账号，默认演示密码为 `local-reader-password`，只获两张业务表的 SELECT 权限。连接设置在 `src/main/resources/application.yml` 的 `enterprise.business-jdbc-url`、`business-user` 和 `business-password` 中；可以在项目根目录 `application-local.yml` 覆盖，直接写密码，无需设置环境变量：

```yaml
enterprise:
  business-jdbc-url: "jdbc:mysql://127.0.0.1:3306/enterprise_qa_demo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"
  business-user: enterprise_qa_reader
  business-password: "local-reader-password"
```

使用 IDEA 时，修改 POM 后先 Reload All Maven Projects，再启动或重启问答服务和 MCP 服务。原 `data/business.mv.db` H2 文件保留；H2 仍用于不依赖外部服务的自动测试。

终端一启动 MCP 服务。只读公开仓库和模拟业务查询不需要模型 Key；MCP 生成/向量工具需要 Key。

```powershell
# 可选：访问自己的公开或授权私有仓库
$env:GITHUB_REPOSITORY = 'codedXX/redis-cache-demo'
$env:GITHUB_TOKEN = '你的Token'
java -jar target/enterprise-qa-agent-1.0.0.jar --spring.profiles.active=mcp-server
```

终端二启动问答服务。

```powershell
java -jar target/enterprise-qa-agent-1.0.0.jar --spring.profiles.active=app
```

终端三导入示例文档。

```powershell
Invoke-RestMethod http://127.0.0.1:8085/api/knowledge/rebuild -Method Post
```

浏览器打开 **http://127.0.0.1:8085/**。可以上传 PDF / UTF-8 Markdown（最大 5 MB）、连续问答、查看来源和步骤，也可以使用左侧业务查询生成 Excel。PDF 正文通过 [Apache PDFBox](https://pdfbox.apache.org/) 提取后分块入库；扫描版或图片 PDF 须先进行 OCR，加密 PDF 须先解除密码保护。不再支持 TXT 上传，重建时只读取 PDF 和 Markdown 文件。`.env.example` 只说明变量，Spring Boot 不会自动读取它。

可直接提问：

- 产品 A 每袋有多少蛋白质？接着问“它含乳吗？”
- DEMO-A 现在多少钱，还有多少库存？
- 按地区统计 2026 年 9 月销售额，并导出 Excel。
- 按月统计 2026 年 4 月到 9 月的销售额。
- 列出库存为 0 的商品。
- 按商品类别统计销售数量和销售额。
- 先介绍产品 A，再统计华东销售额。
- 读取已配置代码仓库的 README，并介绍用途。

默认模型沿用原项目：`qwen3.7-flash`、`qwen3.7-text-embedding`、`qwen3.7-text-rerank`。模型名称、维度、端点与权限需在实际账号验证；可通过 Spring Boot 配置覆盖。更换 Embedding 或分块参数后重新入库。语义分块额外调用 Embedding；设置 `SEMANTIC_CHUNKING_ENABLED=false` 可回退标题/段落递归切分，需重建知识库。

## 3. FAISS / PGVector

### PGVector

```powershell
docker compose -f compose.backends.yaml up -d pgvector
$env:VECTOR_BACKEND = 'pgvector'
java -jar target/enterprise-qa-agent-1.0.0.jar --spring.profiles.active=app
```

### FAISS

```powershell
docker compose -f compose.backends.yaml up -d --build faiss
$env:VECTOR_BACKEND = 'faiss'
java -jar target/enterprise-qa-agent-1.0.0.jar --spring.profiles.active=app
```

后端切换后重新执行 `/api/knowledge/rebuild`。各后端使用 `data/<backend>/lucene` 和独立父文档清单；FAISS 服务通过 Docker 命名卷保存索引。Milvus 端口 19540，PGVector 55432，FAISS 8182。无需同时启动三个后端。

FAISS 可直接在 Python 3.11/3.12 环境中启动：

```powershell
python -m venv .venv
./.venv/Scripts/python -m pip install -r services/requirements.txt
./.venv/Scripts/python -m uvicorn services.faiss_service:app --host 127.0.0.1 --port 8182
```

## 4. 评测与指标

```powershell
python scripts/evaluate.py
# 将实际结果人工标注为 JSON：case ID -> true/false；示例文件不能当作真实标签
python scripts/evaluate.py --input evaluation/results.jsonl --labels evaluation/my-labels.json

# RAGAS 需要单独的评审模型权限，调用会产生费用
python -m pip install -r evaluation/requirements.txt
$env:EVAL_API_KEY = '你的评审模型Key'
$env:EVAL_MODEL = 'qwen-plus'
python scripts/evaluate.py --input evaluation/results.jsonl --ragas --labels evaluation/my-labels.json
```

默认已有案例按来源文件评估 Recall@K/MRR。若提供人工标注的 `relevantChunkIds`，脚本使用 chunk 级指标。RAGAS 只使用该次实际问答返回的上下文；HyDE 不进入证据。全部样本完成正确性标注前，一次答准率为 `null`。失败样本保留在分母中。

输出 `evaluation/results.jsonl`、`results.summary.json` 和 `badcases.jsonl`。具体迭代流程见 [Badcase 工作流](evaluation/BADCASE_WORKFLOW.md)。检索计时包含 Rewrite/HyDE 到父块展开的整个流程；模型路由、工具和最终回答计入总耗时。默认 60 秒缓存可能影响延迟，性能报告需区分冷启动、缓存命中和无缓存结果。`HYDE_ENABLED=false` 可关闭假设文档生成，保留查询改写。

## 5. 工程边界

这是可学习、可扩展的单机项目。知识文档、价格库存及 SQL 数据均为演示样本；没有真实企业上线、用户量或准确率成果声明。

服务只绑定本机地址。没有企业账号认证、租户隔离或分布式会话锁；共享部署前需补齐这些能力。敏感问题检测是可扩展规则，不是完整 DLP。会话、导出和知识数据保存在本地，需按实际需求增加保留期限与访问控制。MCP 只访问配置仓库，文件固定到 commit SHA。Text2SQL 查询固定 products/sales schema，AST 拒绝其他表、CTE、子查询和写操作，查询超时 3 秒，最多返回 200 行；实际企业库接入需替换 schema 与业务词汇说明并使用只读账号。

知识重建会替换本项目当前后端的索引，期间拒绝检索；失败后保留文件、保持未就绪，修复连接后重建。不要使用 `docker compose down -v`，除非确实希望清空本项目的存储卷。

## 6. 阅读与验证

源码及测试中的有效代码行已补充中文注释，说明字段、参数、执行流程、边界条件和断言目的；Python、PowerShell、前端、配置和构建文件也有对应说明。提示词、SQL、XML 等字符串的说明写在字面量外，保持实际内容不变。评测 JSON/JSONL 的字段说明见 [评测数据字段](evaluation/FIELDS.md)。

- [架构与接口](docs/ARCHITECTURE.md)
- [截图要求映射](docs/REQUIREMENTS.md)
- [本次验证结果](docs/VERIFICATION.md)
- [请求示例](requests.http)

```powershell
mvn test
python -m unittest discover -s scripts -p 'test_*.py'
# 安装FAISS依赖后执行真实原生索引测试
python -m unittest discover -s services -p 'test_*.py'
```

真实模型、Milvus 与 MySQL 的探针使用 `mvn -Plive-it verify`，需要显式配置相应环境变量；未配置时跳过。MySQL 探针使用 `MYSQL_IT_URL`，可选 `MYSQL_IT_USER` 和 `MYSQL_IT_PASSWORD`，默认使用上述演示只读账号；仅用于测试脚本，应用仍可直接在 YAML 中配置。不会把本地 HTTP 模型替身当作模型质量验证。
