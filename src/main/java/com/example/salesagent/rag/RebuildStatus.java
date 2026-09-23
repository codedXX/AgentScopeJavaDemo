package com.example.salesagent.rag;

import java.time.Instant;
import java.util.Objects;

/** 知识库当前是否可用、片段数和最近一次重建信息。 */
public class RebuildStatus {
    private boolean ready;
    private long chunkCount;
    private Instant rebuiltAt;
    private String message;

    public RebuildStatus() {
    }

    public RebuildStatus(boolean ready, long chunkCount, Instant rebuiltAt, String message) {
        this.ready = ready;
        this.chunkCount = chunkCount;
        this.rebuiltAt = rebuiltAt;
        this.message = message;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public long getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(long chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Instant getRebuiltAt() {
        return rebuiltAt;
    }

    public void setRebuiltAt(Instant rebuiltAt) {
        this.rebuiltAt = rebuiltAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        RebuildStatus that = (RebuildStatus) object;
        return ready == that.ready
                && chunkCount == that.chunkCount
                && Objects.equals(rebuiltAt, that.rebuiltAt)
                && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ready, chunkCount, rebuiltAt, message);
    }

    @Override
    public String toString() {
        return "RebuildStatus[ready=" + ready + ", chunkCount=" + chunkCount
                + ", rebuiltAt=" + rebuiltAt + ", message=" + message + "]";
    }
}
