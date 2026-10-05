// 测试类位于生产组件同包，可直接检查包内可见的状态与工具轨迹。
package com.example.salesagent.agent;

// JSON 映射器负责构造或解析模型与工具测试响应。
import com.fasterxml.jackson.databind.ObjectMapper;
// Test 标记需要由 JUnit 执行的测试方法。
import org.junit.jupiter.api.Test;
// 引入断言方法，验证结果、异常与隔离边界。
import static org.junit.jupiter.api.Assertions.*;

// 验证工具来源收集、受控错误信息和跨轮清理。
class ToolTraceTest {
    // 验证带合法文本 source 的工具返回能登记成功。
    @Test void retainsValidSourceAndReportsSuccess() {
        // 创建独立工具轨迹记录器和真实 JSON 解析器。
        var trace = new ToolTrace(new ObjectMapper());
        // 提交含来源和文件列表的仓库工具 JSON，模拟一次成功调用。
        trace.recordResult("listRepositoryFiles", "{\"source\":\"https://github.com/example/repo/tree/abc\",\"files\":[\"README.md\"]}");
        // 断言只登记一个有效来源。
        assertEquals(1, trace.sources.size());
        // 断言成功响应没有误记为失败。
        assertTrue(trace.failures.isEmpty());
        // 断言第一条可观察步骤明确表示工具成功。
        assertTrue(trace.steps.getFirst().contains("工具成功"));
    }
    // 验证能公开 HTTP 错误类别，同时不会泄露任意上游敏感内容。
    @Test void explainsErrorWithoutExposingArbitraryUpstreamContent() {
        // 为失败场景创建新的轨迹记录器，避免混入前一用例来源。
        var trace = new ToolTrace(new ObjectMapper());
        // 输入带 403 状态和私有文本的普通错误消息，模拟上游工具失败。
        trace.recordResult("listRepositoryFiles", "Error: 工具失败：GitHub HTTP 403：private-sensitive-text");
        // 断言受控诊断仍保留有用的 403 状态码。
        assertTrue(trace.failures.getFirst().contains("403"));
        // 断言公开步骤没有包含任意私有响应文本。
        assertFalse(trace.steps.toString().contains("private-sensitive-text"));
        // 断言工具失败没有产生可引用来源。
        assertTrue(trace.sources.isEmpty());
        // 模拟开始新一轮问答，重置上轮工具记录。
        trace.reset();
        // 断言执行步骤已被全部清空。
        assertTrue(trace.steps.isEmpty());
        // 断言失败原因已被全部清空。
        assertTrue(trace.failures.isEmpty());
    }
    // 验证超时与缺少有效来源的返回结果能得到不同诊断。
    @Test void distinguishesTimeoutAndInvalidResult() {
        // 创建用于两种错误类别验证的轨迹记录器。
        var trace = new ToolTrace(new ObjectMapper());
        // 提交普通 TimeoutException 文本，触发超时分类。
        trace.recordResult("listRepositoryFiles", "Error: TimeoutException");
        // 断言超时诊断给出中文超时说明。
        assertTrue(trace.failures.getFirst().contains("超时"));
        // 提交 source 为数字的 JSON，验证来源必须是非空字符串。
        trace.recordResult("listRepositoryFiles", "{\"source\":42}");
        // 断言非法 source 不会进入引用白名单。
        assertTrue(trace.sources.isEmpty());
        // 断言最后一次诊断指出没有有效来源，而非误判成功。
        assertTrue(trace.failures.getLast().contains("无有效来源"));
    }
}
