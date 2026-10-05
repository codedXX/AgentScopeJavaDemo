# 评测数据字段说明

JSON 和 JSONL 不支持注释，因此在此说明数据字段，数据文件继续保持可直接解析。JSONL 每行是一条独立 JSON 对象；`cases.jsonl` 的每行对应一个评测问题。

## 样本文件 `cases.jsonl`

| 字段 | 含义 |
|---|---|
| `id` | 样本的唯一标识，用于匹配人工标签和定位失败案例 |
| `question` | 实际提交给问答接口的问题 |
| `referenceAnswer` | 人工参考答案，帮助核对内容；脚本不以字符串相等自动判断正确性 |
| `expectedSource` | 预期知识来源文件，用于 source 级 Recall@K 和 MRR |
| `expectedIntent` | 参考意图，供人工排查路由问题；当前脚本不单独计算意图准确率 |
| `relevantChunkIds` | 可选的人工相关分块 ID 列表；提供后改用 chunk 级召回指标，分块变化后需重新标注 |
| `relevantSources` | 可选的人工相关来源列表；没有分块 ID 标注时优先于单一 `expectedSource`，支持多来源问题 |

## 标签文件 `labels.example.json`

对象的键是样本 `id`，值是 JSON 布尔值 `true` 或 `false`，表示人工判定该次实际回答是否正确。示例文件演示格式；给真实结果打标签时另存自己的标签文件，并配合 `--input` 使用同一批已保存回答。

## 输出文件

`results.jsonl` 保存每个问题的实际响应、成功状态、耗时、召回指标及可选人工标签与 Faithfulness。具体字段的生成过程已在 `scripts/evaluate.py` 逐行说明。`results.summary.json` 汇总样本数、失败数、标签覆盖率、准确率、召回、忠实度和检索延迟。缺少必要标签或评分时保留 `null`，不当作零分或满分。

`badcases.jsonl` 保留请求失败、人工判错、召回不足或忠实度较低的样本，并预留 `root_cause` 和 `fix` 用于记录原因与修复。完整迭代流程见 [Badcase 工作流](BADCASE_WORKFLOW.md)。
