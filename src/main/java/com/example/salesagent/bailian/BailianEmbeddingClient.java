package com.example.salesagent.bailian;

import com.alibaba.dashscope.api.SynchronizeHalfDuplexApi;
import com.alibaba.dashscope.common.OutputMode;
import com.alibaba.dashscope.embeddings.*;
import com.alibaba.dashscope.protocol.*;
import com.example.salesagent.config.DemoProperties;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("app")
public class BailianEmbeddingClient implements EmbeddingClient {
    private final DemoProperties.Bailian config;
    private final SynchronizeHalfDuplexApi<TextEmbeddingParam> api;
    public BailianEmbeddingClient(DemoProperties properties) {
        config = properties.bailian();
        var timeout = Duration.ofSeconds(config.timeoutSeconds());
        // TextEmbedding 的便捷构造器不接收 ConnectionOptions；复用其底层官方 SDK，显式设置超时。
        var service = ApiServiceOption.builder().protocol(Protocol.HTTP).httpMethod(HttpMethod.POST)
                .streamingMode(StreamingMode.NONE).outputMode(OutputMode.DIVIDE)
                .taskGroup("embeddings").task("text-embedding").function("text-embedding").build();
        service.setBaseHttpUrl(config.baseUrl());
        api = new SynchronizeHalfDuplexApi<>(ConnectionOptions.builder().connectTimeout(timeout)
                .readTimeout(timeout).writeTimeout(timeout).build(), service);
    }
    @Override public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        BailianCalls.requireKey(config.apiKey());
        var all = new ArrayList<float[]>();
        // 小批量避免超过服务限制；text_index 是每一批内部的索引。
        for (int start = 0; start < texts.size(); start += 10) {
            var batch = texts.subList(start, Math.min(start + 10, texts.size()));
            var param = TextEmbeddingParam.builder().apiKey(config.apiKey()).model(config.embeddingModel())
                    .texts(batch).dimension(config.dimension()).build();
            var result = BailianCalls.call(() -> TextEmbeddingResult.fromDashScopeResult(api.call(param)));
            if (result == null || result.getOutput() == null) throw new IllegalStateException("Embedding 响应为空");
            all.addAll(map(result.getOutput().getEmbeddings(), batch.size(), config.dimension()));
        }
        return all;
    }
    static List<float[]> map(List<TextEmbeddingResultItem> items, int count, int dimension) {
        if (items == null || items.size() != count) throw new IllegalStateException("Embedding 返回数量不匹配");
        float[][] vectors = new float[count][];
        for (var item : items) {
            Integer index = item.getTextIndex();
            if (index == null || index < 0 || index >= count || vectors[index] != null
                    || item.getEmbedding() == null || item.getEmbedding().size() != dimension)
                throw new IllegalStateException("Embedding index 或向量维度不匹配，需要检查模型配置");
            float[] vector = new float[dimension];
            for (int d = 0; d < dimension; d++) {
                Double value = item.getEmbedding().get(d);
                if (value == null || !Float.isFinite(value.floatValue())) throw new IllegalStateException("Embedding 包含无效数值");
                vector[d] = value.floatValue();
            }
            vectors[index] = vector;
        }
        return Arrays.asList(vectors);
    }
}
