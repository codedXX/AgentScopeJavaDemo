// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 用专门的状态异常标识知识库未建成或正在重建。
public final class KnowledgeNotReadyException extends IllegalStateException {
    // 构造可供接口层识别的未就绪异常。
    public KnowledgeNotReadyException() {
        // 提供统一的知识库状态错误说明。
        super("知识库尚未就绪或正在重建");
    }
}
