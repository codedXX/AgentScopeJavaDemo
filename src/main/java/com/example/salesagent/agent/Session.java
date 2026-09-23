package com.example.salesagent.agent;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class Session<T> {
    private T value;
    private final ReentrantLock lock = new ReentrantLock();
    private long touchedAt = System.nanoTime();

    Session(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    boolean canExpire(long now, long ttlNanos) {
        return !lock.isLocked() && now - touchedAt > ttlNanos;
    }

    boolean tryLock() {
        return lock.tryLock();
    }

    void unlock() {
        lock.unlock();
    }

    void touch() {
        touchedAt = System.nanoTime();
    }

    void initialize(Supplier<T> factory) {
        if (value == null) {
            value = factory.get();
        }
    }
}
