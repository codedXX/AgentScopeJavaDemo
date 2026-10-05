# 评测与 Badcase 迭代

1. 固定文档、模型、分块参数、检索参数及评测样本。启动服务后重建知识库。
2. 使用 `python scripts/evaluate.py` 保存真实问答链路的上下文、检索片段、步骤与耗时。
3. 当前样本的 `expectedSource` 用于 source 级 Recall@K/MRR，不能冒充 chunk 级评测。严谨评测请人工标注 `relevantChunkIds`，对多证据问题列出全部必要分块；更改分块策略后重新标注。
4. 给每条实际回答人工标注 `correct=true/false`，不要照抄 `labels.example.json`。使用 `--input evaluation/results.jsonl --labels evaluation/my-labels.json` 将标签应用到同一批已保存回答，避免重新生成后标签错位。失败请求记为错误。所有样本标注完成后才输出一次答准率。
5. 安装评测依赖、配置独立评审模型，使用 `--ragas` 计算真实回答相对实际上下文的 Faithfulness。模型评分失败为 null，不能记为 0 或 1。Faithfulness 并不等同于答案正确率。
6. 检查 `badcases.jsonl`：填写 root_cause、fix，分类为意图误判、查询丢实体、父块截断、召回缺失、排序错误、工具失败、回答无依据。
7. 修改配置或实现后重新跑同一套样本，对比两份 summary；不得自动修改 golden truth 让分数变高。

85% 一次答准率及平均检索 2000ms 是目标。延迟包括 Query Rewrite、HyDE、Embedding、BM25、向量检索、RRF、Rerank 和父块展开；不包括路由、工具及最终生成。复合任务报告各次检索耗时之和。需要同时报告失败率、缓存、冷启动、均值与 P95。

RAGAS 接入采用 [官方 Faithfulness collections API](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/faithfulness/) 和 [官方 LLM adapter](https://docs.ragas.io/en/stable/howtos/llm-adapters/)。评审模型需支持结构化输出；其权限和可用性必须在你的账户验证。
