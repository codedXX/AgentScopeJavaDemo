package com.example.salesagent.rag;

/** 知识库尚未准备好时抛出的异常。 */
public final class KnowledgeNotReadyException extends IllegalStateException {
    public KnowledgeNotReadyException() {
        super("知识库尚未就绪或正在重建");
    }
}
