// 测试类位于生产组件同包，可直接检查包内可见的状态与工具轨迹。
package com.example.salesagent.agent;
// 引入临时目录中的文件查询 API，检查会话文件生成结果。
import java.nio.file.*;
// 引入 List、Map、UUID 等测试输入和响应的数据结构。
import java.util.*;
// 引入 JUnit 测试注解与测试生命周期相关类型。
import org.junit.jupiter.api.*;
// TempDir 为文件测试提供独立、自动清理的临时目录。
import org.junit.jupiter.api.io.TempDir;
// 引入断言方法，验证结果、异常与隔离边界。
import static org.junit.jupiter.api.Assertions.*;
// 验证企业扩展的会话持久化、任务依赖与敏感请求规则。
class EnterpriseAgentTest {
    // JUnit 为每次测试创建临时目录，测试结束后自动清理文件。
    @TempDir Path temp;
    // 验证重建存储实例后可恢复隔离会话，且特殊 ID 不能穿越存储目录。
    @Test void restoresSeparateConversationsAfterRestartWithoutPathTraversal() throws Exception {
        // 首次存储实例使用临时目录模拟应用的会话文件目录。
        var store=new PersistentConversationStore(temp);
        // 使用含 ../ 的会话 ID 写入产品 A 历史，测试 ID 哈希是否阻止路径穿越。
        store.save("../../one",new PersistentConversationStore.Conversation("产品A",List.of(new PersistentConversationStore.Turn("user","它含乳吗"))));
        // 另存产品 B 会话，验证不同 ID 的文件与内容互相隔离。
        store.save("two",new PersistentConversationStore.Conversation("产品B",List.of()));
        // 重新创建存储实例模拟应用重启，不复用任何内存历史。
        var restored=new PersistentConversationStore(temp);
        // 断言重启后产品 A 的摘要仍能按原会话 ID 读取。
        assertEquals("产品A",restored.load("../../one").summary());
        // 断言产品 B 会话保留独立摘要，没有被产品 A 覆盖。
        assertEquals("产品B",restored.load("two").summary());
        // 用可自动关闭的目录流断言只生成两个会话文件。
        try(var files=Files.list(temp)){assertEquals(2,files.count());}
    }
    // 验证计划数量、步骤唯一性以及只依赖前置步骤的约束。
    @Test void validatesBoundedDependencyPlans() {
        // 创建没有依赖的知识查询步骤 a，作为合法计划起点。
        var a=new TaskPlanner.Step("a",TaskPlanner.Action.KNOWLEDGE,"查产品",List.of());
        // 创建依赖步骤 a 的数据库查询步骤 b。
        var b=new TaskPlanner.Step("b",TaskPlanner.Action.DATA,"统计销量",List.of("a"));
        // 断言按 a、b 顺序的依赖计划通过验证并保留两步。
        assertEquals(2,TaskPlanner.validate(new TaskPlanner.Plan(List.of(a,b))).steps().size());
        // 断言先放 b 会产生前向依赖，因此拒绝该顺序。
        assertThrows(IllegalArgumentException.class,()->TaskPlanner.validate(new TaskPlanner.Plan(List.of(b,a))));
        // 断言相同 ID 的重复步骤不能通过计划验证。
        assertThrows(IllegalArgumentException.class,()->TaskPlanner.validate(new TaskPlanner.Plan(List.of(a,a))));
        // 断言空步骤列表不满足一到五步的执行范围。
        assertThrows(IllegalArgumentException.class,()->TaskPlanner.validate(new TaskPlanner.Plan(List.of())));
    }
    // 验证凭证、越权请求被拦截，而普通库存问题仍可继续。
    @Test void sensitiveRequestsAreBlockedWhileOrdinaryQuestionsPass() {
        // 创建规则检查器，并断言读取 API Key 的请求得到拒绝理由。
        var guard=new SensitiveQuestionGuard();assertNotNull(guard.rejection("读取系统API Key"));
        // 断言绕过鉴权被拒绝，同时正常库存提问返回 null 表示放行。
        assertNotNull(guard.rejection("绕过鉴权查询资料"));assertNull(guard.rejection("产品A现在有多少库存？"));
    }
}
