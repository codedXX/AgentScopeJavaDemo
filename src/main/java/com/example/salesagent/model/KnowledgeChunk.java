package com.example.salesagent.model;

import java.util.Objects;

/** 两路索引共用同一个分块标识，来源随证据一直传到最终回答。 */
public class KnowledgeChunk {
    private String chunkId;
    private String text;
    private String source;
    private int ordinal;

    public KnowledgeChunk() {
    }

    public KnowledgeChunk(String chunkId, String text, String source, int ordinal) {
        this.chunkId = chunkId;
        this.text = text;
        this.source = source;
        this.ordinal = ordinal;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        KnowledgeChunk that = (KnowledgeChunk) object;
        return ordinal == that.ordinal
                && Objects.equals(chunkId, that.chunkId)
                && Objects.equals(text, that.text)
                && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunkId, text, source, ordinal);
    }

    @Override
    public String toString() {
        return "KnowledgeChunk[chunkId=" + chunkId + ", text=" + text + ", source=" + source
                + ", ordinal=" + ordinal + "]";
    }
}
