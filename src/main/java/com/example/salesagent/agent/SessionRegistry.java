package com.example.salesagent.agent;

import java.time.Duration;
import java.util.*;
import java.util.function.*;

/**
 * 单进程会话容器。每个会话持有自己的 Agent 状态和锁，同一会话不能同时修改 Memory；不同会话可以并行。
 * 空闲时间超过 TTL 的会话会在下一次访问时清理，正在处理请求的会话不会被清理。
 */
public class SessionRegistry<T> {
    private final Map<String, Session<T>> sessions = new HashMap<>();
    private final Supplier<T> factory;
    private final int capacity;
    private final long ttlNanos;
    public SessionRegistry(Supplier<T> factory, int capacity, Duration ttl) {
        this.factory = factory; this.capacity = capacity; this.ttlNanos = ttl.toNanos();
    }
    /**
     * 查找或创建会话时持有全局监视器，并用 tryLock 拒绝同一会话的并发请求。
     * Agent 初始化和 action 执行只持有该会话的锁，以免阻塞其他会话；异常时也会更新访问时间并释放锁。
     */
    public <R> R withSession(String id, Function<Session<T>, R> action) {
        Session<T> session;
        synchronized (sessions) {
            long now = System.nanoTime();
            sessions.entrySet().removeIf(entry -> entry.getValue().canExpire(now, ttlNanos));
            session = sessions.get(id);
            if (session == null) {
                if (sessions.size() >= capacity) throw new IllegalStateException("会话数已达上限，请等待闲置会话过期");
                session = new Session<>(null); sessions.put(id, session);
            }
            if (!session.tryLock()) throw new IllegalStateException("该会话正在处理问题，请稍后重试");
        }
        try {
            // 创建 Agent 可能访问外部服务，不能持有所有会话共享的监视器。
            session.initialize(factory);
            return action.apply(session);
        }
        finally {
            synchronized (sessions) {
                if (session.getValue() == null) sessions.remove(id, session);
                session.touch();
                session.unlock();
            }
        }
    }
}
