// 声明所属包，组织 com.example.salesagent.live 的类型并避免类名冲突。
package com.example.salesagent.live;

// 引入百炼模型客户端及向量化、重排序接口。
import com.example.salesagent.bailian.*;
// 引入应用模型配置与 AgentScope 模型装配工具。
import com.example.salesagent.config.*;
// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 ReActAgent，用于验证 AgentScope 真实聊天模型调用。
import io.agentscope.core.ReActAgent;
// 引入 AgentScope 消息与角色，发送真实模型测试请求。
import io.agentscope.core.message.*;
// 引入 Duration，用于配置连接、读写和整体请求超时。
import java.time.Duration;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;
// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;
// 引入 JUnit 前提检查，在缺少真实服务配置时明确跳过集成测试。
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 仅由 -Plive-it 执行；会产生少量真实模型费用，不存在凭证时明确 skip。 */
// 真实百炼联调测试；只在显式 live-it 环境下执行模型请求。
class BailianLiveIT {
    // 依次验证配置中的 Embedding、Rerank 和聊天模型可真实调用。
    @Test void probesAllThreeConfiguredModels() {
        // 读取环境变量中的百炼密钥，不在测试代码中硬编码凭证。
        String key = System.getenv("DASHSCOPE_API_KEY");
        // 没有有效密钥时明确跳过测试，避免把未执行误记为通过。
        assumeTrue(key != null && !key.isBlank(), "未配置 DASHSCOPE_API_KEY，未验证真实模型");
        // 支持环境配置服务地址，否则使用百炼标准 API 地址。
        String url = System.getenv().getOrDefault("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/api/v1");
        // 明确配置三个测试模型、1024 维向量和 20 秒模型超时；其他业务配置无需参与。
        var p = new DemoProperties(new DemoProperties.Bailian(key, url, "qwen3.7-flash", "qwen3.7-text-embedding", "qwen3.7-text-rerank", 1024, 20), null, null, null, null);
        // 调用真实向量模型处理中文产品正文。
        var vectors = new BailianEmbeddingClient(p).embed(List.of("产品A每袋含15克蛋白质"));
        // 断言实际返回向量满足配置的 1024 维。
        assertEquals(1024, vectors.getFirst().length);
        // 用蛋白质问题对两条真实知识候选调用重排序模型。
        var ranked = new BailianRerankClient(p).rank("蛋白质含量", List.of(
                // 第一个候选提供实际蛋白质含量，应当更相关。
                new KnowledgeChunk("A", "产品A每袋含15克蛋白质", "products.md", 0),
                // 第二个候选是无关退货说明，请求返回两条以比较排名。
                new KnowledgeChunk("B", "退货需要订单编号", "business.md", 0)), 2);
        // 断言蛋白质知识 A 在真实重排序中排第一。
        assertEquals("A", ranked.getFirst().chunk().chunkId());
        // 创建使用实际聊天模型的 AgentScope ReActAgent。
        var answer = ReActAgent.builder().name("live-probe").model(new AgentConfiguration().chatModel(p)).build()
                // 发送用户消息并最多等待 30 秒，验证 Agent 能产生模型响应。
                .call(Msg.builder().role(MsgRole.USER).textContent("请回复：连接成功").build()).block(Duration.ofSeconds(30));
        // 断言响应对象存在且正文非空，确认真实聊天调用成功。
        assertNotNull(answer); assertFalse(answer.getTextContent().isBlank());
    }
}
