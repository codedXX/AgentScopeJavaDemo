// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 用专门异常区分重复重建请求与普通入库失败。
public final class RebuildInProgressException extends IllegalStateException {
    // 构造表示知识库已有重建任务正在执行的状态异常。
    public RebuildInProgressException() { super("知识库正在重建"); }
}
