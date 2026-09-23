package com.example.salesagent.config;

import java.nio.file.Path;
import java.util.Objects;

public class RagProperties {
    private Path knowledgeDir;
    private Path indexDir;
    private int chunkSize;
    private int overlap;
    private int recallTopK;
    private int finalTopK;
    private Double minRerankScore;

    public RagProperties() {
    }

    public RagProperties(Path knowledgeDir, Path indexDir, int chunkSize, int overlap,
                         int recallTopK, int finalTopK, Double minRerankScore) {
        this.knowledgeDir = knowledgeDir;
        this.indexDir = indexDir;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.recallTopK = recallTopK;
        this.finalTopK = finalTopK;
        this.minRerankScore = minRerankScore;
    }

    public Path getKnowledgeDir() {
        return knowledgeDir;
    }

    public void setKnowledgeDir(Path knowledgeDir) {
        this.knowledgeDir = knowledgeDir;
    }

    public Path getIndexDir() {
        return indexDir;
    }

    public void setIndexDir(Path indexDir) {
        this.indexDir = indexDir;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public int getRecallTopK() {
        return recallTopK;
    }

    public void setRecallTopK(int recallTopK) {
        this.recallTopK = recallTopK;
    }

    public int getFinalTopK() {
        return finalTopK;
    }

    public void setFinalTopK(int finalTopK) {
        this.finalTopK = finalTopK;
    }

    public Double getMinRerankScore() {
        return minRerankScore;
    }

    public void setMinRerankScore(Double minRerankScore) {
        this.minRerankScore = minRerankScore;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        RagProperties that = (RagProperties) object;
        return chunkSize == that.chunkSize
                && overlap == that.overlap
                && recallTopK == that.recallTopK
                && finalTopK == that.finalTopK
                && Objects.equals(knowledgeDir, that.knowledgeDir)
                && Objects.equals(indexDir, that.indexDir)
                && Objects.equals(minRerankScore, that.minRerankScore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(knowledgeDir, indexDir, chunkSize, overlap, recallTopK, finalTopK, minRerankScore);
    }

    @Override
    public String toString() {
        return "RagProperties[knowledgeDir=" + knowledgeDir + ", indexDir=" + indexDir
                + ", chunkSize=" + chunkSize + ", overlap=" + overlap + ", recallTopK=" + recallTopK
                + ", finalTopK=" + finalTopK + ", minRerankScore=" + minRerankScore + "]";
    }
}
