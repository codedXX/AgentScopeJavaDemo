# Java 常规写法整理实施计划

> **执行方式：** 在当前会话中按任务顺序实施。每项按清单检查，保留工作区其他未提交改动。

**目标：** 将项目主代码和测试代码整理为传统 JavaBean、独立公开类型、显式局部变量类型和传统控制流写法。

**架构：** 业务模型和配置分区改为独立 JavaBean 文件，并更新调用点。DTO 保留原字段名、JSON 属性、校验约束和 record 的值比较行为；服务逻辑保持不变。

**技术栈：** Java 21、Spring Boot、Jackson、AgentScope、Maven。

## 全局约束

- 只整理 `src/main/java` 和 `src/test/java` 中的 Java 写法；保留 Java 21 运行要求。
- 所有 `record` 改成带私有字段、构造方法、getter 和 setter 的普通 JavaBean。
- 公开数据类型各自放在独立文件中；保留服务内部私有实现状态的封装。
- 把 `var`、模式匹配 `instanceof`、箭头形式 `switch` 和文本块改成传统写法。
- 保留 lambda、方法引用、Stream、Spring/AgentScope builder 和现有业务逻辑。
- 不改数据库结构、HTTP 路由、JSON 属性名、配置键或非 Java 文件。
- 更新既有测试代码以匹配新 API；不新增测试，也不执行测试套件。
- 最后用 `mvn -DskipTests test-compile` 编译主代码和测试代码，并扫描新式语法残留。

---

### 任务 1：拆分 Agent 路由模型和会话对象

**文件：**

- 新建 `src/main/java/com/example/salesagent/agent/Intent.java`
- 新建 `src/main/java/com/example/salesagent/agent/Route.java`
- 新建 `src/main/java/com/example/salesagent/agent/Answer.java`
- 新建 `src/main/java/com/example/salesagent/agent/Session.java`
- 修改 `src/main/java/com/example/salesagent/agent/SalesAssistant.java`
- 修改 `src/main/java/com/example/salesagent/agent/SessionRegistry.java`
- 修改 Agent 相关测试中的 record getter 调用

**接口：**

- `Intent` 提供 `KNOWLEDGE`、`REPOSITORY`、`BUSINESS`、`CHAT` 四个枚举值。
- `Route` 提供 `Intent intent`、`String query` 字段以及无参/全参构造、getter 和 setter。
- `Answer` 提供 `String answer`、`List<String> sources` 字段以及无参/全参构造、getter 和 setter。
- `Route` 和 `Answer` 保留对应 record 的 `equals`、`hashCode` 和 `toString` 值语义。
- 独立的 `Session<T>` 提供 `getValue()`；锁、触达时间和延迟初始化仍由会话注册表内部协作方法管理。
- `SessionRegistry.withSession` 接受 `Function<Session<T>, R>`，外部行为保持不变。

- [x] **步骤 1：** 从 `SalesAssistant` 移出 `Intent`、`Route`、`Answer`，为每个类型新建同名文件；`Route` 按以下结构提供字段和访问器，再为 `Answer` 提供对应字段、访问器和值方法：

```java
public class Route {
    private Intent intent;
    private String query;

    public Route() {
    }

    public Route(Intent intent, String query) {
        this.intent = intent;
        this.query = query;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
}
```

- [x] **步骤 2：** 把 `route.intent()`、`route.query()`、`answer.answer()`、`answer.sources()` 改为 JavaBean getter，并保留对应 record 值方法。
- [x] **步骤 3：** 将 `SessionRegistry.Session<T>` 移入 `Session.java`；为注册表所需的锁、触达时间和延迟初始化提供包级协作方法，保持 try-lock、TTL 清理和异常释放行为。
- [x] **步骤 4：** 搜索 `SessionRegistry.Session` 和旧 `value()` 调用，全部更新为顶层 `Session<T>` 与 `getValue()`。

### 任务 2：转换模型和知识库状态类型

**文件：**

- 修改 `model/ChatRequest.java`
- 修改 `model/ChatResponse.java`
- 修改 `model/KnowledgeChunk.java`
- 修改 `model/SearchHit.java`
- 修改 `model/RagResult.java`
- 修改 `rag/RebuildStatus.java`
- 更新使用这些类型的 `api`、`rag`、`bailian`、`agent` 主代码及测试

**接口：**

- `ChatRequest` 保留 `sessionId`、`message` 字段；`@Pattern`、`@NotBlank`、`@Size(max = 2000)` 仍应用于原字段。
- `ChatResponse` 保留 `sessionId`、`answer`、`sources`、`steps`、`retrievalMs`、`totalMs`。
- `KnowledgeChunk` 保留 `chunkId`、`text`、`source`、`ordinal`；`SearchHit` 保留 `chunk`、`score`、`channel`；`RagResult` 保留 `evidence`、`insufficient`、`retrievalMs`。
- `RebuildStatus` 保留 `ready`、`chunkCount`、`rebuiltAt`、`message`。
- 每个类提供无参/全参构造、getter/setter、`equals`、`hashCode` 和 `toString`；字段比较采用 `Objects.equals`，散列采用 `Objects.hash`。

- [x] **步骤 1：** 按接口将 6 个 record 文件改为 JavaBean；`ChatRequest` 的校验注解放到对应字段。
- [x] **步骤 2：** 把主代码和测试中属于这些类型的组件访问改为 getter，保留现有全参构造调用。
- [x] **步骤 3：** 为 6 个模型类实现 `equals`、`hashCode`、`toString`；`KnowledgeChunk` 的 `equals` 必须逐字段比较 `chunkId`、`text`、`source`、`ordinal`，使 Lucene 测试中的对象比较继续成立。

### 任务 3：转换聊天历史类型

**文件：**

- 修改 `history/ChatSession.java`
- 修改 `history/ChatTurn.java`
- 更新 `history/PgChatHistoryStore.java`、API 和历史相关测试

**接口：**

- `ChatSession` 保留 `id`、`title`、`createdAt`、`updatedAt`。
- `ChatTurn` 保留 `id`、`sessionId`、`question`、`answer`、`sources`、`steps`、`retrievalMs`、`totalMs`、`createdAt`。
- 输出 JSON 字段名和历史查询/写入顺序保持不变。
- 两个类型保留 `equals`、`hashCode` 和 `toString`。

- [x] **步骤 1：** 将两个 history record 改为各自文件中的 JavaBean，保留无参/全参构造和访问器。
- [x] **步骤 2：** 将 PgChatHistoryStore、API 和测试中的组件访问方式改为 getter。

### 任务 4：拆分并转换配置属性

**文件：**

- 修改 `config/DemoProperties.java`
- 新建 `config/BailianProperties.java`
- 新建 `config/RagProperties.java`
- 新建 `config/MilvusProperties.java`
- 新建 `config/McpProperties.java`
- 新建 `config/GithubProperties.java`
- 更新 `config/AgentConfiguration.java`、`config/RagConfiguration.java`、`mcp/McpServerConfiguration.java`、Bailian 客户端和配置相关测试

**接口：**

- `DemoProperties` 继续使用 `@ConfigurationProperties("demo")`，提供 `getBailian()`、`getRag()`、`getMilvus()`、`getMcp()`、`getGithub()`。
- `BailianProperties` 保留 `apiKey`、`baseUrl`、`chatModel`、`embeddingModel`、`rerankModel`、`dimension`、`timeoutSeconds`。
- `RagProperties` 保留 `knowledgeDir`、`indexDir`、`chunkSize`、`overlap`、`recallTopK`、`finalTopK`、`minRerankScore`。
- `MilvusProperties` 保留 `uri`、`token`、`collection`；`McpProperties` 保留 `url`、`businessUrl`；`GithubProperties` 保留 `apiUrl`、`token`。
- Spring Binder 可通过无参构造和 setter 绑定原有 `demo.*` 配置键。
- 配置类型保留 `equals` 和 `hashCode`；`toString` 省略 `apiKey` 和 `token`。

- [x] **步骤 1：** 将 `DemoProperties` 改成 JavaBean，并创建 5 个独立配置分区 JavaBean。
- [x] **步骤 2：** 更新生产代码的配置 getter 链，例如 `p.getBailian().getApiKey()`，保持配置键字符串不变。
- [x] **步骤 3：** 更新 `DemoProperties.Bailian` 等嵌套类型引用和测试构造方式。

### 任务 5：移出索引清单类型

**文件：**

- 新建 `src/main/java/com/example/salesagent/rag/IndexManifest.java`
- 修改 `src/main/java/com/example/salesagent/rag/KnowledgeIngestionService.java`

**接口：**

- `IndexManifest` 保留 `chunkCount`、`rebuiltAt`、`dimension`，作为 Jackson 可绑定的 JavaBean。
- 清单 JSON 字段名、启动恢复校验和原子文件替换逻辑保持不变。
- 保留 `equals`、`hashCode` 和 `toString`。

- [x] **步骤 1：** 创建带无参/全参构造、getter/setter 的 `IndexManifest`，从服务中删除私有 record。
- [x] **步骤 2：** 将读取/写入清单的类型引用改为 `IndexManifest`，JSON 属性保持不变。

### 任务 6：整理生产代码的局部变量和控制流

**文件：**

- 所有 `src/main/java/**/*.java` 中含 `var` 或目标新式语法的文件，重点包括 `SalesAssistant.java`、`ToolTrace.java`、`McpServerConfiguration.java`、`MilvusChunkStore.java`、`KnowledgeIngestionService.java`

- [x] **步骤 1：** 根据右侧表达式的声明类型，把生产代码所有局部 `var` 改为显式声明；增强 `for` 和 try-with-resources 也写出元素/资源类型。
- [x] **步骤 2：** 将 `instanceof Type value` 改为传统 `instanceof Type` 检查和显式类型转换；保持分支条件及异常行为。
- [x] **步骤 3：** 将 `ToolTrace` 的箭头式 `switch` 改成 `case`/`break` 形式，保持每个状态码的返回消息。
- [x] **步骤 4：** 将 `SalesAssistant.SYSTEM` 文本块改为普通字符串拼接，逐字保留提示内容和换行。

### 任务 7：整理测试代码写法

**文件：**

- `src/test/java` 下所有含 record 风格 getter、`var`、模式匹配 `instanceof` 或其他目标新式语法的 Java 文件

- [x] **步骤 1：** 将测试中的 record 组件访问更新为 JavaBean getter，配置构造器改用独立配置分区类型。
- [x] **步骤 2：** 将测试中的局部 `var` 改为显式类型，包括循环变量和资源变量。
- [x] **步骤 3：** 将测试中的模式匹配 `instanceof` 改为传统检查和显式转换；保留断言和测试逻辑。

### 任务 8：全量语法检查和编译

**文件：**

- 检查 `src/main/java`、`src/test/java` 全部 Java 文件和本计划涉及的新文件。

- [x] **步骤 1：** 搜索 `record` 声明、`var` 局部变量、绑定变量的 `instanceof`、`case ... ->` 和文本块分隔符 `"""`，逐项清零。
- [x] **步骤 2：** 执行 `mvn -DskipTests test-compile`；它应编译主代码和测试代码，不执行测试。
- [x] **步骤 3：** 检查最终差异和工作区状态，确认没有覆盖此前已有的业务改动或修改非 Java 文件。
