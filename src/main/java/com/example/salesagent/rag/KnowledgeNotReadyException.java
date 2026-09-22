package com.example.salesagent.rag;

public final class KnowledgeNotReadyException extends IllegalStateException {
    public KnowledgeNotReadyException() {
        super("知识库尚未就绪或正在重建");
    }
}
