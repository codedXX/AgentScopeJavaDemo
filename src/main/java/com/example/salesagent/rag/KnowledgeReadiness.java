package com.example.salesagent.rag;

import java.util.function.Supplier;

@FunctionalInterface
public interface KnowledgeReadiness {
    boolean isReady();

    default <T> T withReadLock(Supplier<T> action) {
        return action.get();
    }
}
