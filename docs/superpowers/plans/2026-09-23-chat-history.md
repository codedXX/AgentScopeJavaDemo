# Chat History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist complete chat turns in PostgreSQL and let users list, switch, and continue conversations after restart.

**Architecture:** A MyBatis-Plus backed history store owns session and turn SQL. `SalesAssistant` reads the last ten stored turns before each answer and appends the completed turn before returning. REST endpoints expose listing and ordered turns; the existing browser UI renders and restores them.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis-Plus Boot 3 starter, PostgreSQL, Flyway, JUnit, plain JavaScript.

## Global Constraints

- Single-user localhost demo; no authentication or multi-instance coordination.
- Keep Milvus, Lucene, and local knowledge file storage unchanged.
- Preserve the existing uncommitted formatting changes in `SalesAssistant.java`.
- Use environment variables for PostgreSQL credentials.

---

### Task 1: Database persistence

**Files:** `pom.xml`, `compose.yaml`, `src/main/resources/application-app.yml`, `src/main/resources/db/migration/V1__chat_history.sql`, `src/main/java/com/example/salesagent/history/*`, `src/test/java/com/example/salesagent/history/*`.

**Interfaces:** `ChatHistoryStore.listSessions()`, `turns(sessionId)`, `recentTurns(sessionId, limit)`, `append(sessionId, question, ChatResponse)`. A turn stores question, answer, sources, steps, and timings.

- [x] Write failing tests for atomic append, listing order, ordered turns, and recent-turn bounds.
- [x] Run the focused tests and confirm failure is due to missing behavior.
- [x] Add PostgreSQL, MyBatis-Plus, Flyway dependencies and versioned DDL.
- [x] Implement the mapper and transactional history store.
- [x] Run focused tests and then all Maven tests.

### Task 2: Agent restoration and HTTP API

**Files:** `src/main/java/com/example/salesagent/agent/SalesAssistant.java`, `src/main/java/com/example/salesagent/api/ChatController.java`, `src/test/java/com/example/salesagent/agent/SalesAssistantTest.java`, `src/test/java/com/example/salesagent/api/ChatControllerTest.java`.

**Interfaces:** Existing `POST /api/chat`; new `GET /api/sessions`, `GET /api/sessions/{id}/turns`.

- [x] Write failing tests for history restoration, no success when append fails, and list/detail routes.
- [x] Run focused tests and confirm the expected failure.
- [x] Load recent turns from the store for each chat request; append only after a complete answer.
- [x] Add list and detail endpoints with unknown ID handling.
- [x] Run focused tests and the full Maven suite.

### Task 3: Browser history and documentation

**Files:** `src/main/resources/static/index.html`, `src/main/resources/static/app.js`, `src/main/resources/static/styles.css`, `README.md`, `docs/PROJECT.md`.

**Interfaces:** The browser consumes the two new GET routes and existing chat response.

- [x] Verify the existing page loses the selected session on refresh and has no history list.
- [x] Add history list, switching, new-chat behavior, selected-ID restoration, and busy-state protection.
- [x] Update startup and persistence documentation.
- [x] Run JavaScript syntax validation, Maven tests, and build; check browser behavior when a running service is available.

## Self-review

The three tasks cover schema, storage, transactional writes, context restoration, API, UI, and documentation. No knowledge-storage migration is included.
