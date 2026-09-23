package com.example.salesagent.rag;

import java.time.Instant;
import java.util.Objects;

/** 记录上次成功重建的片段数、时间和向量维度。 */
public class IndexManifest {
    private long chunkCount;
    private Instant rebuiltAt;
    private int dimension;

    public IndexManifest() {
    }

    public IndexManifest(long chunkCount, Instant rebuiltAt, int dimension) {
        this.chunkCount = chunkCount;
        this.rebuiltAt = rebuiltAt;
        this.dimension = dimension;
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

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        IndexManifest that = (IndexManifest) object;
        return chunkCount == that.chunkCount
                && dimension == that.dimension
                && Objects.equals(rebuiltAt, that.rebuiltAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunkCount, rebuiltAt, dimension);
    }

    @Override
    public String toString() {
        return "IndexManifest[chunkCount=" + chunkCount + ", rebuiltAt=" + rebuiltAt
                + ", dimension=" + dimension + "]";
    }
}
