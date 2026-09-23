package com.example.salesagent.model;

import java.util.Objects;

/** score 仅在同一 channel 内比较；混合召回不能直接比较两路原始分数。 */
public class SearchHit {
    private KnowledgeChunk chunk;
    private double score;
    private String channel;

    public SearchHit() {
    }

    public SearchHit(KnowledgeChunk chunk, double score, String channel) {
        this.chunk = chunk;
        this.score = score;
        this.channel = channel;
    }

    public KnowledgeChunk getChunk() {
        return chunk;
    }

    public void setChunk(KnowledgeChunk chunk) {
        this.chunk = chunk;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        SearchHit that = (SearchHit) object;
        return Double.compare(score, that.score) == 0
                && Objects.equals(chunk, that.chunk)
                && Objects.equals(channel, that.channel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunk, score, channel);
    }

    @Override
    public String toString() {
        return "SearchHit[chunk=" + chunk + ", score=" + score + ", channel=" + channel + "]";
    }
}
