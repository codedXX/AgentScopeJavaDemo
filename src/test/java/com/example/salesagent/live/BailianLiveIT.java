package com.example.salesagent.live;

import com.example.salesagent.bailian.*;
import com.example.salesagent.config.*;
import com.example.salesagent.model.KnowledgeChunk;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 仅由 -Plive-it 执行；会产生少量真实模型费用，不存在凭证时明确 skip。 */
class BailianLiveIT {
    /** 已配置的三个模型都能完成一次调用。 */
    @Test void probesAllThreeConfiguredModels() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "未配置 DASHSCOPE_API_KEY，未验证真实模型");
        String url = System.getenv().getOrDefault("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/api/v1");
        DemoProperties p = new DemoProperties(new BailianProperties(key, url, "qwen3.7-flash", "qwen3.7-text-embedding", "qwen3.7-text-rerank", 1024, 20), null, null, null, null);
        List<float[]> vectors = new BailianEmbeddingClient(p).embed(List.of("产品A每袋含15克蛋白质"));
        assertEquals(1024, vectors.getFirst().length);
        List<com.example.salesagent.model.SearchHit> ranked = new BailianRerankClient(p).rank("蛋白质含量", List.of(
                new KnowledgeChunk("A", "产品A每袋含15克蛋白质", "products.md", 0),
                new KnowledgeChunk("B", "退货需要订单编号", "business.md", 0)), 2);
        assertEquals("A", ranked.getFirst().getChunk().getChunkId());
        Msg answer = ReActAgent.builder().name("live-probe").model(new AgentConfiguration().chatModel(p)).build()
                .call(Msg.builder().role(MsgRole.USER).textContent("请回复：连接成功").build()).block(Duration.ofSeconds(30));
        assertNotNull(answer); assertFalse(answer.getTextContent().isBlank());
    }
}
