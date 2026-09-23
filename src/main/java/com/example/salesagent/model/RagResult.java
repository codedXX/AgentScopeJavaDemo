package com.example.salesagent.model;

import java.util.List;
import java.util.Objects;

public class RagResult {
    private List<SearchHit> evidence;
    private boolean insufficient;
    private long retrievalMs;

    public RagResult() {
    }

    public RagResult(List<SearchHit> evidence, boolean insufficient, long retrievalMs) {
        this.evidence = evidence;
        this.insufficient = insufficient;
        this.retrievalMs = retrievalMs;
    }

    public List<SearchHit> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<SearchHit> evidence) {
        this.evidence = evidence;
    }

    public boolean isInsufficient() {
        return insufficient;
    }

    public void setInsufficient(boolean insufficient) {
        this.insufficient = insufficient;
    }

    public long getRetrievalMs() {
        return retrievalMs;
    }

    public void setRetrievalMs(long retrievalMs) {
        this.retrievalMs = retrievalMs;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        RagResult that = (RagResult) object;
        return insufficient == that.insufficient
                && retrievalMs == that.retrievalMs
                && Objects.equals(evidence, that.evidence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evidence, insufficient, retrievalMs);
    }

    @Override
    public String toString() {
        return "RagResult[evidence=" + evidence + ", insufficient=" + insufficient
                + ", retrievalMs=" + retrievalMs + "]";
    }
}
