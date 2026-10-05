// 检索命中的通用模型供 BM25、向量与 Rerank 通道共同使用。
package com.example.salesagent.model;
/** score 仅在同一 channel 内比较；混合召回不能直接比较两路原始分数。 */
// 定义一个带分数和通道信息的检索命中。
public record SearchHit(
        // 命中的知识分块及其来源。
        KnowledgeChunk chunk,
        // 当前检索或重排通道计算的相关性得分。
        double score,
        // 标识 bm25、vector、rrf 或 rerank 等得分产生阶段。
        String channel) {}
