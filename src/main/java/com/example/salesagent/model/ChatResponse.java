package com.example.salesagent.model;

import java.util.List;
import java.util.Objects;

/** 返回给前端的回答、来源、处理步骤和耗时。 */
public class ChatResponse {
    private String sessionId;
    private String answer;
    private List<String> sources;
    private List<String> steps;
    private long retrievalMs;
    private long totalMs;

    public ChatResponse() {
    }

    public ChatResponse(String sessionId, String answer, List<String> sources, List<String> steps,
                        long retrievalMs, long totalMs) {
        this.sessionId = sessionId;
        this.answer = answer;
        this.sources = sources;
        this.steps = steps;
        this.retrievalMs = retrievalMs;
        this.totalMs = totalMs;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        ChatResponse that = (ChatResponse) object;
        return retrievalMs == that.retrievalMs
                && totalMs == that.totalMs
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(answer, that.answer)
                && Objects.equals(sources, that.sources)
                && Objects.equals(steps, that.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, answer, sources, steps, retrievalMs, totalMs);
    }

    @Override
    public String toString() {
        return "ChatResponse[sessionId=" + sessionId + ", answer=" + answer + ", sources=" + sources
                + ", steps=" + steps + ", retrievalMs=" + retrievalMs + ", totalMs=" + totalMs + "]";
    }
}
