// 将 RAG 返回值与其他业务数据模型放在同一包中。
package com.example.salesagent.model;
// 通过 List 保存排序后的检索命中集合。
import java.util.List;
// 定义检索结果，同时向调用方报告证据是否足够和检索耗时。
public record RagResult(
        // 已完成融合、重排与父文档回溯的证据条目。
        List<SearchHit> evidence,
        // 为 true 表示没有足够可信的检索证据，需要工具补充或兜底。
        boolean insufficient,
        // 本次检索消耗的毫秒数，计入响应和评测记录。
        long retrievalMs) {}
