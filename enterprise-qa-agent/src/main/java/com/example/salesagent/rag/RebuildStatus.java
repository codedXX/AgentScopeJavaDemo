// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 Instant，用于持久化成功重建的时间点。
import java.time.Instant;

// 不可变状态记录依次包含可查询标志、分块数、成功重建时间与状态说明。
public record RebuildStatus(boolean ready, long chunkCount, Instant rebuiltAt, String message) {}
