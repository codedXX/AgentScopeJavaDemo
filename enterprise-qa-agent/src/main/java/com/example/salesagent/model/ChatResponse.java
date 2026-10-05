// 回答数据对象位于 model 包，统一对外响应结构。
package com.example.salesagent.model;
// List 用于返回来源、步骤和检索证据的有序集合。
import java.util.List;
// record 自动生成只读字段、构造器和访问器，携带一轮问答的结果。
public record ChatResponse(
        // 标识本轮所属会话，客户端可在下一轮请求中继续携带。
        String sessionId,
        // 最终面向用户展示的中文回答文本。
        String answer,
        // 经应用校验后可以引用的证据来源。
        List<String> sources,
        // 检索、计划与工具调用的可观察执行步骤。
        List<String> steps,
        // 本轮检索阶段累计耗时，单位毫秒。
        long retrievalMs,
        // 从接收问题到完成回答的总耗时，单位毫秒。
        long totalMs,
        // 检索命中的分块 ID，用于 Recall@K 等离线评测。
        List<String> retrievedChunkIds,
        // 检索及工具返回的文本证据，供 Faithfulness 等指标检查。
        List<String> retrievedContexts,
        // 检索命中来源列表，辅助定位召回结果与引用差异。
        List<String> retrievedSources) {
    // 保留只需要基本回答字段的调用方式，省去手动传入三组空评测列表。
    public ChatResponse(String id,String answer,List<String> sources,List<String> steps,long retrievalMs,long totalMs) {
        // 转发到完整构造器；未提供检索详情时统一使用不可变空列表。
        this(id,answer,sources,steps,retrievalMs,totalMs,List.of(),List.of(),List.of());
    }
}
