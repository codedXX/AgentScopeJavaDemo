package com.example.salesagent.agent;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;

/** 小型单机会话容器：状态不共享，繁忙会话不被清理，同会话禁止并发修改 Memory。 */
public class SessionRegistry<T> {
    public static class Session<T> {
        private T value;
        private final ReentrantLock lock = new ReentrantLock();
        private long touchedAt = System.nanoTime();
        Session(T value) { this.value = value; }
        public T value() { return value; }
    }
    private final Map<String, Session<T>> sessions = new HashMap<>();
    private final Supplier<T> factory;
    private final int capacity;
    private final long ttlNanos;
    public SessionRegistry(Supplier<T> factory, int capacity, Duration ttl) {
        this.factory = factory; this.capacity = capacity; this.ttlNanos = ttl.toNanos();
    }
    public <R> R withSession(String id, Function<Session<T>, R> action) {
        Session<T> session;
        synchronized (sessions) {
            long now = System.nanoTime();
            sessions.entrySet().removeIf(entry -> !entry.getValue().lock.isLocked() && now - entry.getValue().touchedAt > ttlNanos);
            session = sessions.get(id);
            if (session == null) {
                if (sessions.size() >= capacity) throw new IllegalStateException("会话数已达上限，请等待闲置会话过期");
                session = new Session<>(null); sessions.put(id, session);
            }
            if (!session.lock.tryLock()) throw new IllegalStateException("该会话正在处理问题，请稍后重试");
        }
        try {
            // 创建 Agent 可能访问外部服务，不能持有所有会话共享的监视器。
            if (session.value == null) session.value = factory.get();
            return action.apply(session);
        }
        finally {
            synchronized (sessions) {
                if (session.value == null) sessions.remove(id, session);
                session.touchedAt = System.nanoTime(); session.lock.unlock();
            }
        }
    }
}
