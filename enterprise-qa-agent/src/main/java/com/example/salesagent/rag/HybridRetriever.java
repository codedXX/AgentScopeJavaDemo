// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入百炼模型客户端及向量化、重排序接口。
import com.example.salesagent.bailian.*;
// 引入知识分块、命中证据与检索结果等业务数据类型。
import com.example.salesagent.model.*;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入虚拟线程执行器、异步任务与限时等待工具。
import java.util.concurrent.*;
// 协调查询扩展、双路召回、RRF 融合、重排序、父块恢复和结果缓存。
public final class HybridRetriever {
    // 关键词召回接口，生产环境由 Lucene BM25 实现。
    private final KeywordIndex keyword;
    // 向量召回接口，可切换 Milvus、FAISS 或 PGVector。
    private final VectorChunkStore vector;
    // 把原问题、改写问题和 HyDE 假设文本转换成语义向量。
    private final EmbeddingClient embedding;
    // 对融合后的候选重新计算相关性，得到最终证据排名。
    private final RerankClient rerank;
    // 知识库就绪状态与读锁接口，防止检索读取重建中的索引。
    private final KnowledgeReadiness ready;
    // 分别保存单路召回数、最终证据数、融合候选数、RRF 平滑常数和缓存有效秒数。
    private final int recallK, finalK, fusionK, rrfK, cacheSeconds;
    // 可选重排序最低分；低于阈值的结果标记为证据不足。
    private final Double minScore;
    // 可选查询优化器，生成同义改写和 HyDE 假设回答。
    private final QueryExpansion expansion;
    // 可选父文档存储，用于把命中子块替换为较完整的章节上下文。
    private final ParentDocumentStore parents;
    // 缓存记录保存检索结果和基于单调时钟计算的到期时间。
    private record Cached(RagResult result, long expires) {}
    // 采用线程安全、保持插入顺序的缓存映射；插入顺序用于容量淘汰。
    private final Map<String, Cached> cache = Collections.synchronizedMap(new LinkedHashMap<>());
    // 提供只配置基础双路召回依赖的便捷构造方法。
    public HybridRetriever(KeywordIndex k, VectorChunkStore v, EmbeddingClient e, RerankClient r,
            // 接收就绪检查、候选数量和可选重排序最低分。
            KnowledgeReadiness ready, int recallK, int finalK, Double minScore) {
        // 默认关闭扩展、父块恢复和缓存；RRF 常数为 60，融合上限为 100。
        this(k,v,e,r,ready,recallK,finalK,minScore,null,null,60,100,0);
    }
    // 提供可配置查询扩展、父块恢复和缓存的完整构造方法。
    public HybridRetriever(KeywordIndex k, VectorChunkStore v, EmbeddingClient e, RerankClient r,
            // 接收知识库状态、召回参数、证据阈值和查询优化器。
            KnowledgeReadiness ready, int recallK, int finalK, Double minScore, QueryExpansion expansion,
            // 接收父块存储、RRF 平滑常数、融合上限及缓存有效期。
            ParentDocumentStore parents, int rrfK, int fusionK, int cacheSeconds) {
        // 保存关键词、向量、Embedding、Rerank 和就绪状态依赖。
        this.keyword=k; this.vector=v; this.embedding=e; this.rerank=r; this.ready=ready;
        // 保存召回规模、最终结果规模、最低分与查询扩展配置。
        this.recallK=recallK; this.finalK=finalK; this.minScore=minScore; this.expansion=expansion;
        // 保存父文档映射、融合参数与缓存有效期。
        this.parents=parents; this.rrfK=rrfK; this.fusionK=fusionK; this.cacheSeconds=cacheSeconds;
    }
    // 默认允许读取和写入结果缓存，兼容仅传入问题的调用方式。
    public RagResult retrieve(String query) { return retrieve(query, false); }
    // 执行检索；bypassCache 为 true 时跳过缓存，用于准确评测或强制刷新。
    public RagResult retrieve(String query, boolean bypassCache) {
        // 使用单调纳秒时钟记录起点，避免系统时间校准影响耗时。
        long start = System.nanoTime();
        // 让知识库提供读锁保护，使一次完整检索读取同一个索引版本。
        return ready.withReadLock(() -> {
            // 知识库未就绪时立即拒绝检索，防止返回半成品索引结果。
            if (!ready.isReady()) throw new KnowledgeNotReadyException();
            // 缓存键同时包含知识库版本与问题，重建后自动与旧缓存隔离。
            String key = ready.revision() + "\n" + query;
            // 仅在调用方没有要求跳过缓存时检查已有结果。
            if (!bypassCache) {
                // 读取同一版本、同一问题的缓存记录。
                var old = cache.get(key);
                // 仅复用尚未到期的记录，过期判断使用单调时间。
                if (old != null && old.expires() > System.nanoTime())
                    // 复用缓存证据和不足标志，但重新计算本次请求实际耗时。
                    return new RagResult(old.result().evidence(), old.result().insufficient(), elapsed(start));
            }
            // 未配置扩展器时只检索原问题；配置后生成改写和假设文本。
            var expanded = expansion == null ? new QueryExpansion.Expanded(List.of(query), "") : expansion.expand(query);
            // 收集每个关键词或向量召回通道独立的有序命中列表。
            List<List<SearchHit>> channels = new ArrayList<>();
            // 首先把原问题和同义改写加入向量化文本集合。
            List<String> vectorTexts = new ArrayList<>(expanded.queries());
            // 只有非空 HyDE 假设文本才参与向量召回。
            if (expanded.hypotheticalDocument() != null && !expanded.hypotheticalDocument().isBlank())
                // 假设文本只扩展查询语义，不直接加入最终证据。
                vectorTexts.add(expanded.hypotheticalDocument());
            // 为召回任务建立虚拟线程执行器，结束时自动关闭。
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                // 记录异步关键词和向量检索任务，便于统一等待与处理异常。
                List<Future<List<SearchHit>>> tasks = new ArrayList<>();
                // 对每个原始或改写问题并发提交 BM25 召回任务。
                for (String q : expanded.queries()) tasks.add(pool.submit(() -> keyword.search(q, recallK)));
                // 批量向量化所有查询文本和可选的 HyDE 文本。
                var vectors = embedding.embed(vectorTexts);
                // 校验向量数量，保证每个查询文本都有对应检索向量。
                if (vectors.size() != vectorTexts.size()) throw new IllegalStateException("查询向量数量不正确");
                // 为每个查询向量并发提交向量召回任务。
                for (float[] v : vectors) tasks.add(pool.submit(() -> vector.search(v, recallK)));
                // 按提交顺序收集各通道结果；每次等待最多 25 秒。
                for (var task : tasks) channels.add(task.get(25, TimeUnit.SECONDS));
            // 被取消时恢复线程中断标志，并向上层报告检索取消。
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("检索已取消", ex); }
            // 将任务执行失败或等待超时统一转换为召回失败。
            catch (ExecutionException | TimeoutException ex) { throw new IllegalStateException("检索召回失败", ex); }
            // 根据各通道的名次累加 RRF 分数，去重并限制融合候选数量。
            var fused = ReciprocalRankFusion.fuse(channels, rrfK, fusionK);
            // 空候选跳过模型调用；否则按原问题重排序并截取最终 TopK。
            List<SearchHit> ranked = fused.isEmpty() ? List.of() : rerank.rank(query, fused.stream().map(SearchHit::chunk).toList(), finalK);
            // 无结果或最高相关性分数低于配置阈值时，标记证据不足。
            boolean insufficient = ranked.isEmpty() || minScore != null && ranked.getFirst().score() < minScore;
            // 未配置父块映射时保留短子块；配置后逐条恢复父文档。
            List<SearchHit> evidence = parents == null ? ranked : ranked.stream()
                // 扩展命中正文但保留重排序分数，注明结果来自父块恢复。
                .map(hit -> new SearchHit(parents.expand(hit.chunk()), hit.score(), "rerank-parent")).toList();
            // 生成不可修改的证据快照及本次检索耗时。
            var result = new RagResult(List.copyOf(evidence), insufficient, elapsed(start));
            // 只有启用缓存且没有显式跳过缓存时才更新映射，并对淘汰与插入共同加锁。
            if (cacheSeconds > 0 && !bypassCache) synchronized(cache) {
                // 缓存达到 500 条时移除最早插入的一条，限制内存占用。
                if (cache.size() >= 500) cache.remove(cache.keySet().iterator().next());
                // 保存当前结果并将有效秒数换算为纳秒到期时间。
                cache.put(key, new Cached(result, System.nanoTime() + TimeUnit.SECONDS.toNanos(cacheSeconds)));
            }
            // 向读锁包装器返回完整检索结果。
            return result;
        });
    }
    // 把单调纳秒差换算成整数毫秒，用于接口耗时展示。
    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
}
