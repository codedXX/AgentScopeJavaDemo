package com.example.salesagent.bailian;

import com.alibaba.dashscope.api.SynchronizeHalfDuplexApi;
import com.alibaba.dashscope.common.OutputMode;
import com.alibaba.dashscope.embeddings.*;
import com.alibaba.dashscope.protocol.*;
import com.example.salesagent.config.BailianProperties;
import com.example.salesagent.config.DemoProperties;

import java.time.Duration;
import java.util.*;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 调用百炼模型，把文本转成供向量检索使用的数字数组。 */
@Component @Profile("app")
public class BailianEmbeddingClient implements EmbeddingClient {
    private final BailianProperties config;

    private final SynchronizeHalfDuplexApi<TextEmbeddingParam> api;


    public BailianEmbeddingClient(DemoProperties properties) {
        config = properties.getBailian();
        Duration timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        // TextEmbedding 的便捷构造器不接收 ConnectionOptions；复用其底层官方 SDK，显式设置超时。
        ApiServiceOption service = ApiServiceOption.builder().protocol(Protocol.HTTP).httpMethod(HttpMethod.POST)
                .streamingMode(StreamingMode.NONE).outputMode(OutputMode.DIVIDE)
                .taskGroup("embeddings").task("text-embedding").function("text-embedding").build();
        service.setBaseHttpUrl(config.getBaseUrl());
        api = new SynchronizeHalfDuplexApi<>(ConnectionOptions.builder().connectTimeout(timeout)
                .readTimeout(timeout).writeTimeout(timeout).build(), service);
    }
    /** 分批请求向量，最后按原文顺序合并。 */
    @Override public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        BailianCalls.requireKey(config.getApiKey());
        List<float[]> all = new ArrayList<>();
        // 小批量避免超过服务限制；text_index 是每一批内部的索引。
        for (int start = 0; start < texts.size(); start += 10) {
            List<String> batch = texts.subList(start, Math.min(start + 10, texts.size()));
            TextEmbeddingParam param = TextEmbeddingParam.builder().apiKey(config.getApiKey()).model(config.getEmbeddingModel())
                    .texts(batch).dimension(config.getDimension()).build();
            TextEmbeddingResult result = BailianCalls.call(() -> TextEmbeddingResult.fromDashScopeResult(api.call(param)));
            if (result == null || result.getOutput() == null) throw new IllegalStateException("Embedding 响应为空");
            all.addAll(map(result.getOutput().getEmbeddings(), batch.size(), config.getDimension()));
        }
        return all;
    }
    /**
     * SDK 返回顺序不一定等于输入顺序，因此按 text_index 放回当前批次对应的位置。
     * 数量、索引、维度或数值不合法时立即报错，避免将错误向量写到别的知识片段上。
     */
    static List<float[]> map(List<TextEmbeddingResultItem> items, int count, int dimension) {
        if (items == null || items.size() != count) throw new IllegalStateException("Embedding 返回数量不匹配");
        float[][] vectors = new float[count][];
        for (TextEmbeddingResultItem item : items) {
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
