package com.example.salesagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Objects;

public class ChatRequest {
    @Pattern(regexp = "[A-Za-z0-9-]{1,64}")
    private String sessionId;

    @NotBlank
    @Size(max = 2000)
    private String message;

    public ChatRequest() {
    }

    public ChatRequest(String sessionId, String message) {
        this.sessionId = sessionId;
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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
        ChatRequest that = (ChatRequest) object;
        return Objects.equals(sessionId, that.sessionId) && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, message);
    }

    @Override
    public String toString() {
        return "ChatRequest[sessionId=" + sessionId + ", message=" + message + "]";
    }
}
