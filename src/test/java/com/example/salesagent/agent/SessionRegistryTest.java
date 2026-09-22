package com.example.salesagent.agent;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryTest {
    @Test void slowNewSessionDoesNotBlockExistingSession() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        var registry = new SessionRegistry<String>(() -> {
            if (count.incrementAndGet() == 2) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return "value";
        }, 3, Duration.ofMinutes(30));
        registry.withSession("existing", s -> s.value());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var slow = executor.submit(() -> registry.withSession("new", s -> s.value()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                var fast = executor.submit(() -> registry.withSession("existing", s -> s.value()));
                assertEquals("value", fast.get(1, TimeUnit.SECONDS));
            } finally { release.countDown(); slow.get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void remembersSameSessionButIsolatesDifferentSessions() {
        var registry = new SessionRegistry<ArrayList<String>>(ArrayList::new, 2, Duration.ofMinutes(30));
        registry.withSession("a", session -> { session.value().add("hello"); return null; });
        assertEquals(1, registry.withSession("a", session -> session.value().size()).intValue());
        assertEquals(0, registry.withSession("b", session -> session.value().size()).intValue());
        assertThrows(IllegalStateException.class, () -> registry.withSession("c", session -> null));
    }
}
