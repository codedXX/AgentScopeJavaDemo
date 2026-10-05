// 会话状态与并发控制放在 agent 包。
package com.example.salesagent.agent;

// Duration 将配置的闲置时长转换为纳秒，匹配单调时钟。
import java.time.Duration;
// Map/HashMap 保存会话 ID 到状态容器的映射。
import java.util.*;
// 每个会话有独立的可重入锁，防止同一 Memory 被并发修改。
import java.util.concurrent.locks.ReentrantLock;
// Supplier 构造会话状态，Function 执行一次会话内操作。
import java.util.function.*;

/** 小型单机会话容器：状态不共享，繁忙会话不被清理，同会话禁止并发修改 Memory。 */
// 泛型 T 表示调用方保存的会话状态，例如 SalesAssistant.State。
public class SessionRegistry<T> {
    // 单个会话容器保存状态、互斥锁和最后访问时间。
    public static class Session<T> {
        // 实际会话状态；null 表示尚未成功初始化。
        private T value;
        // 只保护当前会话的操作，其他会话可以并行执行。
        private final ReentrantLock lock = new ReentrantLock();
        // 使用单调时钟记录最后活动时间，避免系统时间调整影响过期判断。
        private long touchedAt = System.nanoTime();
        // 创建新会话时允许先传入 null，稍后在锁外构造实际 Agent。
        Session(T value) {
            // 保存初始状态值。
            this.value = value;
        }
        // 向会话操作回调提供当前状态。
        public T value() {
            // 返回该会话独有的状态对象。
            return value;
        }
    }
    // 共享注册表仅在短暂 synchronized 区域内读取和修改。
    private final Map<String, Session<T>> sessions = new HashMap<>();
    // 首次访问会话时调用的状态工厂。
    private final Supplier<T> factory;
    // 允许同时保留的最大会话数。
    private final int capacity;
    // 闲置会话的过期时间，以纳秒保存。
    private final long ttlNanos;
    // 构造注册表时注入状态工厂、容量和闲置时限。
    public SessionRegistry(Supplier<T> factory, int capacity, Duration ttl) {
        // 保留延迟创建状态的工厂。
        this.factory = factory;
        // 保存容量限制，避免状态无限堆积。
        this.capacity = capacity;
        // 转为纳秒，与 System.nanoTime 的差值直接比较。
        this.ttlNanos = ttl.toNanos();
    }
    // 在指定会话的独占锁下执行 action，并返回其泛型结果 R。
    public <R> R withSession(String id, Function<Session<T>, R> action) {
        // 当前请求即将使用的会话容器。
        Session<T> session;
        // 保护会话映射的查找、清理、新建和锁获取。
        synchronized (sessions) {
            // 获取一次统一的时间快照，供本轮过期检查使用。
            long now = System.nanoTime();
            // 只移除未在处理请求且闲置超时的会话，保留正在工作中的状态。
            sessions.entrySet().removeIf(entry -> !entry.getValue().lock.isLocked() && now - entry.getValue().touchedAt > ttlNanos);
            // 按 ID 查找已有会话，确保相同 ID 复用相同状态。
            session = sessions.get(id);
            // 尚无该 ID 时创建一个待初始化的容器。
            if (session == null) {
                // 清理后仍超过容量时拒绝新会话，避免驱逐正在使用的会话。
                if (sessions.size() >= capacity) throw new IllegalStateException("会话数已达上限，请等待闲置会话过期");
                // 先创建空容器，把可能较慢的状态构造延后到共享锁外。
                session = new Session<>(null);
                // 注册 ID 与容器之间的映射。
                sessions.put(id, session);
            }
            // 同一会话已有任务时立即拒绝，防止并发写入历史和工具状态。
            if (!session.lock.tryLock()) throw new IllegalStateException("该会话正在处理问题，请稍后重试");
        }
        // 当前会话锁已取得，初始化和业务回调统一进入最终释放锁的路径。
        try {
            // 创建 Agent 可能访问外部服务，不能持有所有会话共享的监视器。
            // 第一次操作时用工厂初始化状态，此时只持有当前会话的锁。
            if (session.value == null) session.value = factory.get();
            // 执行用户提供的回调，让其安全读取和修改当前会话。
            return action.apply(session);
        // 会话操作无论成功或抛出异常，都更新活动时间并释放锁。
        } finally {
            // 完成或失败后都在共享锁下同步更新映射与活动时间。
            synchronized (sessions) {
                // 初始化失败时移除空容器，让下一次请求能够重新创建。
                if (session.value == null) sessions.remove(id, session);
                // 从处理结束时计算新的闲置起点。
                session.touchedAt = System.nanoTime();
                // 释放当前会话锁，允许后续问题继续使用它。
                session.lock.unlock();
            }
        }
    }
}
