// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入 SearchHit，用于检索命中分块、分数和通道。
import com.example.salesagent.model.SearchHit;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 实现按名次融合的 RRF 算法，避免直接比较不同通道分数尺度。
public final class ReciprocalRankFusion {
    // 工具类只暴露静态方法，私有构造器阻止实例化。
    private ReciprocalRankFusion() {}
    // 融合多个有序召回通道，k 为名次平滑常数，limit 为输出上限。
    public static List<SearchHit> fuse(List<List<SearchHit>> channels, int k, int limit) {
        // 要求平滑常数与输出上限均为正数。
        if (k < 1 || limit < 1) throw new IllegalArgumentException("RRF 参数不合法");
        // 分别保存每个 chunkId 对应的原始命中与累计 RRF 分数。
        Map<String, SearchHit> hits = new HashMap<>(); Map<String, Double> scores = new HashMap<>();
        // 逐个处理 BM25、改写或向量等独立召回通道。
        for (var channel : channels) {
            // 每个通道独立维护已见 ID，避免同一通道的重复命中反复加分。
            Set<String> seen = new HashSet<>();
            // 按零起始下标遍历当前通道已有排名。
            for (int i = 0; i < channel.size(); i++) {
                // 提取当前命中对象与用于跨通道去重的知识分块 ID。
                var hit = channel.get(i); String id = hit.chunk().chunkId();
                // 同通道第一次出现才登记候选，并累加 1/(k+一基名次)；跨通道贡献可叠加。
                if (seen.add(id)) { hits.putIfAbsent(id, hit); scores.merge(id, 1.0 / (k + i + 1), Double::sum); }
            }
        }
        // 按累计 RRF 分数从高到低排列候选。
        return scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()
            // 相同分数时按 ID 排序使结果稳定，并限制输出候选数。
            .thenComparing(Map.Entry.comparingByKey())).limit(limit)
            // 用融合分数生成统一命中，保留真实分块正文并标记 rrf 通道。
            .map(e -> new SearchHit(hits.get(e.getKey()).chunk(), e.getValue(), "rrf")).toList();
    }
}
