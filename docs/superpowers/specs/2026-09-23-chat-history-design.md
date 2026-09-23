# Chat History Persistence Design

## Scope

This is a single-user, localhost demo. Preserve complete question and answer turns in PostgreSQL with MyBatis-Plus. Keep uploaded knowledge files, Lucene, and Milvus as they are. Add a history list, switching between conversations, and the ability to continue a conversation after an app restart.

## Data and behavior

- `chat_session`: session ID, title derived from the first question, created and updated timestamps.
- `chat_turn`: one complete successful turn containing the question, final answer, verified sources, steps, retrieval time, total time, and timestamp. A foreign key ties each turn to its session.
- The database is the source of truth. Before each chat request, load the last 10 complete turns as AgentScope dialogue context. The in-memory session registry only protects an active session from concurrent requests and holds temporary Agent/tool state.
- Save the session and completed turn in one database transaction after a valid answer is produced. Failed model calls or database writes must not create a half turn or return a success response.
- `sessionId` identifies a conversation and is reused for each turn in that conversation. The API does not accept a per-turn request ID. If a response is lost after a turn was saved, retrying may execute and save the same question again.
- `GET /api/sessions` lists sessions newest first. `GET /api/sessions/{id}/turns` returns all saved turns in order and returns 404 for an unknown ID. `POST /api/chat` accepts `sessionId` and `message`, and returns the existing answer response shape.
- The browser shows a session list, loads saved turns when selecting an item, and remembers the selected ID across refreshes. A new conversation starts without a database row until the first successful answer.

## Boundaries and limits

No login, sharing, deletion, or multi-instance locking in this demo. Session IDs remain opaque and validated. Database credentials come from environment variables. Database schema is versioned. PostgreSQL unavailability is reported as a service error. The current knowledge storage and retrieval pipeline remains unchanged.

## Verification

Test the persistence transaction, newest-first listing, ordered turn loading, restart-style context restoration, unknown sessions, and failed writes. Run the full Maven test suite and build. Where Docker is available, verify against a real PostgreSQL container.
