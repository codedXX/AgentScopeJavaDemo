// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 Supplier，用于延迟创建连接或执行读锁操作。
import java.util.function.Supplier;

// 约束接口只保留一个抽象方法，允许使用 Lambda 替换实现。
@FunctionalInterface
// 抽象知识库是否可读、版本标识及读锁执行能力。
public interface KnowledgeReadiness {
    // 返回当前索引是否已完整发布并可被检索。
    boolean isReady();
    // 基础实现使用固定版本；实际入库服务会以重建时间覆盖此方法。
    default String revision() { return "default"; }

    // 默认直接执行回调；需要并发保护的实现可覆盖并获取读锁。
    default <T> T withReadLock(Supplier<T> action) {
        // 保留调用方回调的返回值与异常，不额外改变检索行为。
        return action.get();
    }
}
