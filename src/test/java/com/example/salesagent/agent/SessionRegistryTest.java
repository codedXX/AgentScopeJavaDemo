package com.example.salesagent.agent;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryTest {
    @Test void slowNewSessionDoesNotBlockExistingSession() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        SessionRegistry<String> registry = new SessionRegistry<String>(() -> {
            if (count.incrementAndGet() == 2) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return "value";
        }, 3, Duration.ofMinutes(30));
        registry.withSession("existing", s -> s.getValue());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> slow = executor.submit(() -> registry.withSession("new", s -> s.getValue()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                Future<String> fast = executor.submit(() -> registry.withSession("existing", s -> s.getValue()));
                assertEquals("value", fast.get(1, TimeUnit.SECONDS));
            } finally { release.countDown(); slow.get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void remembersSameSessionButIsolatesDifferentSessions() {
        SessionRegistry<ArrayList<String>> registry = new SessionRegistry<ArrayList<String>>(ArrayList::new, 2, Duration.ofMinutes(30));
        registry.withSession("a", session -> { session.getValue().add("hello"); return null; });
        assertEquals(1, registry.withSession("a", session -> session.getValue().size()).intValue());
        assertEquals(0, registry.withSession("b", session -> session.getValue().size()).intValue());
        assertThrows(IllegalStateException.class, () -> registry.withSession("c", session -> null));
    }
}
