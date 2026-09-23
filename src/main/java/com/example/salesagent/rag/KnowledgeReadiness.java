package com.example.salesagent.rag;

import java.util.function.Supplier;

/**
 * 检索侧看到的知识库就绪契约。生产实现用 withReadLock 包住一次完整检索，防止重建同时修改索引；
 * 默认实现直接执行 action，便于测试中用简单的就绪状态替身。
 */
@FunctionalInterface
public interface KnowledgeReadiness {
    boolean isReady();

    /** 在允许读取索引的时间窗口内执行 action，并原样返回其结果。 */
    default <T> T withReadLock(Supplier<T> action) {
        return action.get();
    }
}
