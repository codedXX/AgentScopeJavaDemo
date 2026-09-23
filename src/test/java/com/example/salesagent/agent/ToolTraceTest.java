package com.example.salesagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolTraceTest {
    @Test void retainsValidSourceAndReportsSuccess() {
        ToolTrace trace = new ToolTrace(new ObjectMapper());
        trace.recordResult("listRepositoryFiles", "{\"source\":\"https://github.com/example/repo/tree/abc\",\"files\":[\"README.md\"]}");
        assertEquals(1, trace.sources.size());
        assertTrue(trace.failures.isEmpty());
        assertTrue(trace.steps.getFirst().contains("工具成功"));
    }
    @Test void explainsErrorWithoutExposingArbitraryUpstreamContent() {
        ToolTrace trace = new ToolTrace(new ObjectMapper());
        trace.recordResult("listRepositoryFiles", "Error: 工具失败：GitHub HTTP 403：private-sensitive-text");
        assertTrue(trace.failures.getFirst().contains("403"));
        assertFalse(trace.steps.toString().contains("private-sensitive-text"));
        assertTrue(trace.sources.isEmpty());
        trace.reset();
        assertTrue(trace.steps.isEmpty());
        assertTrue(trace.failures.isEmpty());
    }
    @Test void distinguishesTimeoutAndInvalidResult() {
        ToolTrace trace = new ToolTrace(new ObjectMapper());
        trace.recordResult("listRepositoryFiles", "Error: TimeoutException");
        assertTrue(trace.failures.getFirst().contains("超时"));
        trace.recordResult("listRepositoryFiles", "{\"source\":42}");
        assertTrue(trace.sources.isEmpty());
        assertTrue(trace.failures.getLast().contains("无有效来源"));
    }
}
