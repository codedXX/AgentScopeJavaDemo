package com.example.salesagent.history;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 一轮完整问答，包含问题、回答、来源、步骤和耗时。 */
public class ChatTurn {
    private long id;
    private String sessionId;
    private String question;
    private String answer;
    private List<String> sources;
    private List<String> steps;
    private long retrievalMs;
    private long totalMs;
    private Instant createdAt;

    public ChatTurn() {
    }

    public ChatTurn(long id, String sessionId, String question, String answer,
                    List<String> sources, List<String> steps, long retrievalMs,
                    long totalMs, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.question = question;
        this.answer = answer;
        this.sources = sources;
        this.steps = steps;
        this.retrievalMs = retrievalMs;
        this.totalMs = totalMs;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public List<String> getSteps() {
        return steps;
    }

    public void setSteps(List<String> steps) {
        this.steps = steps;
    }

    public long getRetrievalMs() {
        return retrievalMs;
    }

    public void setRetrievalMs(long retrievalMs) {
        this.retrievalMs = retrievalMs;
    }

    public long getTotalMs() {
        return totalMs;
    }

    public void setTotalMs(long totalMs) {
        this.totalMs = totalMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        ChatTurn that = (ChatTurn) object;
        return id == that.id
                && retrievalMs == that.retrievalMs
                && totalMs == that.totalMs
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(question, that.question)
                && Objects.equals(answer, that.answer)
                && Objects.equals(sources, that.sources)
                && Objects.equals(steps, that.steps)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sessionId, question, answer, sources, steps, retrievalMs, totalMs, createdAt);
    }

    @Override
    public String toString() {
        return "ChatTurn[id=" + id + ", sessionId=" + sessionId + ", question=" + question
                + ", answer=" + answer + ", sources=" + sources + ", steps=" + steps
                + ", retrievalMs=" + retrievalMs + ", totalMs=" + totalMs + ", createdAt=" + createdAt + "]";
    }
}
