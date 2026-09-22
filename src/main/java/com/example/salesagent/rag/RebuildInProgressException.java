package com.example.salesagent.rag;

public final class RebuildInProgressException extends IllegalStateException {
    public RebuildInProgressException() { super("知识库正在重建"); }
}
