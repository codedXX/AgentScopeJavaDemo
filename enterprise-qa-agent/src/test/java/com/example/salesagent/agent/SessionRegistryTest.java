// 测试类位于生产组件同包，可直接检查包内可见的状态与工具轨迹。
package com.example.salesagent.agent;
// Test 标记需要由 JUnit 执行的测试方法。
import org.junit.jupiter.api.Test;
// 指定测试中会话 TTL 或模型调用等待时限。
import java.time.Duration;
// 作为会话可修改状态，验证历史保留和跨会话隔离。
import java.util.ArrayList;
// 引入门闩、虚拟线程执行器和时间单位，验证并发锁粒度。
import java.util.concurrent.*;
// 原子计数区分两次状态工厂调用，并与 HTTP/工作线程安全交互。
import java.util.concurrent.atomic.AtomicInteger;
// 引入断言方法，验证结果、异常与隔离边界。
import static org.junit.jupiter.api.Assertions.*;

// 验证会话锁粒度、历史复用、隔离和容量边界。
class SessionRegistryTest {
    // 验证新会话初始化较慢时，不会阻塞其他已存在会话。
    @Test void slowNewSessionDoesNotBlockExistingSession() throws Exception {
        // entered 门闩用于通知主测试线程，慢初始化已经开始。
        var entered = new CountDownLatch(1);
        // release 门闩用于控制何时释放慢初始化线程。
        var release = new CountDownLatch(1);
        // 原子计数器区分第一次已有会话创建与第二次新会话创建。
        var count = new AtomicInteger();
        // 定义状态工厂，第二次调用时刻意阻塞以模拟外部连接很慢。
        var registry = new SessionRegistry<String>(() -> {
            // 仅在第二个会话初始化时进入阻塞分支。
            if (count.incrementAndGet() == 2) {
                // 通知主线程，新会话已经占据其独立锁并开始初始化。
                entered.countDown();
                // 最多等待五秒，防止测试失败时无限挂起。
                try { release.await(5, TimeUnit.SECONDS); }
                // 被中断时恢复线程的中断标记，保持正确的取消语义。
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            // 两个会话都返回相同内容，但各自拥有独立状态容器。
            return "value";
        // 注册表容量为三，闲置过期时间为三十分钟。
        }, 3, Duration.ofMinutes(30));
        // 提前创建 existing 会话，使其在并发验证时无需再次执行状态工厂。
        registry.withSession("existing", s -> s.value());
        // 用可自动关闭的虚拟线程执行器发起并发访问。
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 在线程中访问 new 会话，进入被门闩阻塞的慢初始化。
            var slow = executor.submit(() -> registry.withSession("new", s -> s.value()));
            // 断言两秒内进入慢初始化，确保后面的并行访问确实与它重叠。
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            // 发起已有会话访问后，finally 必须释放被阻塞的初始化线程。
            try {
                // 慢初始化仍在等待时，另一个线程访问 existing 会话。
                var fast = executor.submit(() -> registry.withSession("existing", s -> s.value()));
                // 断言已有会话在一秒内返回，证明没有被注册表共享锁长时间阻塞。
                assertEquals("value", fast.get(1, TimeUnit.SECONDS));
            // 结束验证后释放慢线程，并等待其完成，避免测试遗留挂起任务。
            } finally { release.countDown(); slow.get(5, TimeUnit.SECONDS); }
        }
    }
    // 验证相同会话保留状态、不同会话隔离，超过容量拒绝创建。
    @Test void remembersSameSessionButIsolatesDifferentSessions() {
        // 每个会话创建独立 ArrayList，容器最多允许两条会话。
        var registry = new SessionRegistry<ArrayList<String>>(ArrayList::new, 2, Duration.ofMinutes(30));
        // 向 a 会话列表追加 hello，返回 null 表示本次操作没有业务返回值。
        registry.withSession("a", session -> { session.value().add("hello"); return null; });
        // 断言再次访问 a 时仍保留一条消息。
        assertEquals(1, registry.withSession("a", session -> session.value().size()).intValue());
        // 断言新建 b 会话的列表为空，没有共享 a 的状态。
        assertEquals(0, registry.withSession("b", session -> session.value().size()).intValue());
        // 断言第三个 c 会话超过容量时抛出异常，不悄悄驱逐已有状态。
        assertThrows(IllegalStateException.class, () -> registry.withSession("c", session -> null));
    }
}
