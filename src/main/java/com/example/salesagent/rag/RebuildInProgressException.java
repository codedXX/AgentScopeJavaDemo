package com.example.salesagent.rag;

/** 已有重建任务运行时抛出的异常。 */
public final class RebuildInProgressException extends IllegalStateException {
    public RebuildInProgressException() { super("知识库正在重建"); }
}
