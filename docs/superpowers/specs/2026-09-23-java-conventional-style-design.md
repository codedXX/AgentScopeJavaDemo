# Java 常规写法整理方案

## 目标

把 Java 代码整理成更容易被熟悉传统 Java 和 Spring 项目的开发者阅读的形式。本次只调整源码组织和语法写法，不改变应用行为、HTTP JSON 结构、持久化数据或 Java 21 运行要求。

## 范围

- 整理 `src/main/java` 和 `src/test/java` 下的 Java 文件。
- 将所有 `record` 改为普通 JavaBean，使用私有字段、构造方法、getter 和 setter。
- 每个公开数据类型单独放在自己的 Java 文件中。把 `Intent`、`Route`、`Answer` 从 `SalesAssistant` 中拆出；把 `DemoProperties` 的配置分区类型拆出；把公开的 `SessionRegistry.Session` 拆出；把知识库清单类型从 `KnowledgeIngestionService` 中移出。
- 将 record 组件访问方式统一改为 JavaBean getter，并更新主代码和测试中的调用位置。
- 将局部变量 `var` 改为明确类型，包括增强 `for` 循环和 try-with-resources 中的变量。
- 将模式匹配 `instanceof`、箭头形式的 `switch` 和文本块改为传统 Java 写法。
- 保留 lambda、方法引用、Stream 操作、Java 21，以及 Spring/AgentScope 现有的 builder API。

## 兼容要求

- 请求、响应、AgentScope 结构化数据和历史记录对象的字段名及 JSON 属性名保持不变。
- `ChatRequest` 的校验约束继续作用于相同字段。
- Spring 配置绑定和 Jackson 反序列化所需的数据绑定能力保持可用；需要绑定的 JavaBean 提供无参构造方法和 setter。
- 保留原 record 提供的值相等和哈希行为；`toString` 保留实用信息，同时不输出配置密钥。
- 尽量保留现有全参数构造调用；类型拆分后，更新所有对应调用位置。
- 不改服务行为、数据库结构、API 路由、配置键和非 Java 文件。

## 检查方式

- 搜索主代码和测试代码，确认不再有 `record`、`var`、模式匹配 `instanceof`、箭头形式的 `switch` 和文本块。
- 编译主代码和测试代码，不执行测试套件。
- 检查最终差异，确保工作区里其他未提交的改动没有被覆盖或清理。
