// 声明所属包，组织 com.example.salesagent.bailian 的类型并避免类名冲突。
package com.example.salesagent.bailian;

// 引入 SynchronizeHalfDuplexApi，用于以官方底层 SDK 显式配置 Embedding 超时。
import com.alibaba.dashscope.api.SynchronizeHalfDuplexApi;
// 引入 OutputMode，用于配置百炼输出处理模式。
import com.alibaba.dashscope.common.OutputMode;
// 引入官方 Embedding 请求参数与向量响应类型。
import com.alibaba.dashscope.embeddings.*;
// 引入官方 SDK 的传输协议、服务选项与连接超时类型。
import com.alibaba.dashscope.protocol.*;
// 引入 DemoProperties，用于读取模型地址、凭证、维度和超时配置。
import com.example.salesagent.config.DemoProperties;
// 引入 Duration，用于配置连接、读写和整体请求超时。
import java.time.Duration;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入 Profile，用于限制组件仅在 app 配置环境启用。
import org.springframework.context.annotation.Profile;
// 引入 Component，用于将模型客户端注册为 Spring Bean。
import org.springframework.stereotype.Component;

// 注册 Spring 组件，并限制其在 app 配置环境中启用。
@Component @Profile("app")
// 通过百炼官方 SDK 实现批量文本向量化，并恢复响应到输入顺序。
public class BailianEmbeddingClient implements EmbeddingClient {
    // 保存模型名、维度、服务地址、凭证和超时配置。
    private final DemoProperties.Bailian config;
    // 复用接受自定义连接选项的 Embedding 底层 SDK 客户端。
    private final SynchronizeHalfDuplexApi<TextEmbeddingParam> api;
    // 依据应用配置构造 Embedding 客户端。
    public BailianEmbeddingClient(DemoProperties properties) {
        // 只读取百炼相关配置，不依赖其他业务配置。
        config = properties.bailian();
        // 把配置中的超时秒数转换为 SDK 所需的 Duration。
        var timeout = Duration.ofSeconds(config.timeoutSeconds());
        // TextEmbedding 的便捷构造器不接收 ConnectionOptions；复用其底层官方 SDK，显式设置超时。
        // 使用 HTTP POST 声明向量模型服务的传输配置。
        var service = ApiServiceOption.builder().protocol(Protocol.HTTP).httpMethod(HttpMethod.POST)
                // 关闭流式传输，并沿用官方 Embedding 服务所需的输出模式。
                .streamingMode(StreamingMode.NONE).outputMode(OutputMode.DIVIDE)
                // 指定 embeddings 任务组及 text-embedding 任务和功能。
                .taskGroup("embeddings").task("text-embedding").function("text-embedding").build();
        // 设置配置中的百炼服务基础地址，支持不同地域端点。
        service.setBaseHttpUrl(config.baseUrl());
        // 创建官方半双工 API，并显式配置连接建立超时。
        api = new SynchronizeHalfDuplexApi<>(ConnectionOptions.builder().connectTimeout(timeout)
                // 同时设置读取、写入超时，并绑定向量服务选项。
                .readTimeout(timeout).writeTimeout(timeout).build(), service);
    }
    // 按输入顺序返回每段文本对应的 float 向量。
    @Override public List<float[]> embed(List<String> texts) {
        // 没有待处理文本时跳过凭证校验和远程调用。
        if (texts.isEmpty()) return List.of();
        // 调用模型前要求已配置 API Key。
        BailianCalls.requireKey(config.apiKey());
        // 按批次顺序收集最终向量结果。
        var all = new ArrayList<float[]>();
        // 小批量避免超过服务限制；text_index 是每一批内部的索引。
        // 每十段文本组成一个批次，限制服务请求规模。
        for (int start = 0; start < texts.size(); start += 10) {
            // 截取当前批次，最后一批可以少于十条。
            var batch = texts.subList(start, Math.min(start + 10, texts.size()));
            // 给当前请求绑定 API Key 和 Embedding 模型名。
            var param = TextEmbeddingParam.builder().apiKey(config.apiKey()).model(config.embeddingModel())
                    // 设置本批文本及期望向量维度。
                    .texts(batch).dimension(config.dimension()).build();
            // 经有限重试包装调用底层 SDK，并转换为标准 Embedding 响应。
            var result = BailianCalls.call(() -> TextEmbeddingResult.fromDashScopeResult(api.call(param)));
            // 拒绝空结果或缺失 output 的异常响应。
            if (result == null || result.getOutput() == null) throw new IllegalStateException("Embedding 响应为空");
            // 按 text_index 校验并恢复本批输入顺序，再追加到整体结果。
            all.addAll(map(result.getOutput().getEmbeddings(), batch.size(), config.dimension()));
        }
        // 返回与原始文本顺序一致的完整向量列表。
        return all;
    }
    // 独立映射函数负责校验响应条目并恢复同批输入顺序。
    static List<float[]> map(List<TextEmbeddingResultItem> items, int count, int dimension) {
        // 要求响应条目数与请求文本数量一致。
        if (items == null || items.size() != count) throw new IllegalStateException("Embedding 返回数量不匹配");
        // 按输入数量建立二维数组，用 text_index 将向量放回正确位置。
        float[][] vectors = new float[count][];
        // 逐项处理 SDK 返回的 Embedding 条目。
        for (var item : items) {
            // 读取当前向量所对应的批内文本下标。
            Integer index = item.getTextIndex();
            // 拒绝缺失、越界或重复下标，防止向量错配或覆盖。
            if (index == null || index < 0 || index >= count || vectors[index] != null
                    // 继续要求正文向量存在且长度等于配置维度。
                    || item.getEmbedding() == null || item.getEmbedding().size() != dimension)
                // 向上层报告模型返回的下标或维度与配置不一致。
                throw new IllegalStateException("Embedding index 或向量维度不匹配，需要检查模型配置");
            // 为当前合法条目分配固定维度的 float 数组。
            float[] vector = new float[dimension];
            // 逐维转换模型响应中的 Double 数值。
            for (int d = 0; d < dimension; d++) {
                // 读取当前维度的响应值。
                Double value = item.getEmbedding().get(d);
                // 拒绝 null、NaN 或转换到 float 后产生的无穷值。
                if (value == null || !Float.isFinite(value.floatValue())) throw new IllegalStateException("Embedding 包含无效数值");
                // 将有效数值转换为下游检索使用的 float。
                vector[d] = value.floatValue();
            }
            // 根据批内 text_index 保存向量，修正乱序返回。
            vectors[index] = vector;
        }
        // 返回按输入下标排列的固定大小向量列表。
        return Arrays.asList(vectors);
    }
}
