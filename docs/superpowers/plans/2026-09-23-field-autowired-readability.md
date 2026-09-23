# Field Injection Readability Refactor

> **For agentic workers:** Execute inline in this task. Preserve existing user changes and behavior.

**Goal:** Reduce constructor boilerplate by using field `@Autowired` on Spring-owned application components where all dependencies are container-managed.

**Architecture:** Convert the direct Spring-managed controllers, services, and Bailian clients. Keep constructor parameters in `@Bean` factory methods and ordinary helper classes that receive scalar configuration or are manually created; field injection would require changing how those objects are built.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, Spring Test.

## Global Constraints

- Keep API behavior, request handling, persistence, timeout, and error behavior unchanged.
- Use field injection only for Spring-managed classes whose dependencies are Spring beans.
- Keep constructor-based creation for manually constructed helpers and classes that receive scalar configuration.
- Preserve all existing working-tree changes unrelated to dependency injection.
- Do not run tests unless the user asks for testing.

---

### Task 1: Convert Spring component dependencies to fields

**Files:**
- Modify: `src/main/java/com/example/salesagent/agent/SalesAssistant.java`
- Modify: `src/main/java/com/example/salesagent/api/ChatController.java`
- Modify: `src/main/java/com/example/salesagent/api/KnowledgeController.java`
- Modify: `src/main/java/com/example/salesagent/history/PgChatHistoryStore.java`
- Modify: `src/main/java/com/example/salesagent/bailian/BailianEmbeddingClient.java`
- Modify: `src/main/java/com/example/salesagent/bailian/BailianRerankClient.java`

- [x] Add `@Autowired` fields for each Spring-managed dependency and remove redundant constructors.
- [x] Keep `SalesAssistant`'s internal session registry and worker pool behavior unchanged.
- [x] Leave `@Bean` method parameter injection and scalar-configured helper constructors unchanged.

### Task 2: Keep unit-test setup compatible

**Files:**
- Modify: `src/test/java/com/example/salesagent/agent/SalesAssistantTest.java`
- Modify: `src/test/java/com/example/salesagent/api/ChatControllerTest.java`
- Modify: `src/test/java/com/example/salesagent/api/KnowledgeControllerTest.java`

- [x] Replace direct constructor calls to converted Spring components with no-argument construction and Spring Test field injection helpers.
- [x] Keep assertions and test scenarios unchanged.

### Task 3: Review scope and compile

**Files:** all paths listed above.

- [x] Search for stale constructors and verify every `@Autowired` target is a Spring bean.
- [x] Run `git diff --check`.
- [x] Run `mvn -DskipTests clean compile`; Javac reported `Fatal Error: Cannot close compiler resources`, without a source location.
