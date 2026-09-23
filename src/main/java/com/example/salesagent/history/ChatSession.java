package com.example.salesagent.history;

import java.time.Instant;
import java.util.Objects;

public class ChatSession {
    private String id;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;

    public ChatSession() {
    }

    public ChatSession(String id, String title, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        ChatSession that = (ChatSession) object;
        return Objects.equals(id, that.id)
                && Objects.equals(title, that.title)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "ChatSession[id=" + id + ", title=" + title + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + "]";
    }
}
