// 知识分块数据属于 model 包，供切分、索引和回答阶段共享。
package com.example.salesagent.model;
/** 两路索引共用同一个分块标识，来源随证据一直传到最终回答。 */
// 定义一个不可变知识分块；关键词索引和向量索引使用相同结构。
public record KnowledgeChunk(
        // 分块唯一标识，用于去重、融合与检索评测。
        String chunkId,
        // 当前分块的实际文本，作为 Embedding 输入和回答证据。
        String text,
        // 原始文档的来源标识，回答时据此给出引用。
        String source,
        // 当前分块在所属文档中的序号，便于保留文档顺序。
        int ordinal) {}
