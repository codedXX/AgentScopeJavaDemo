"""End-to-end evaluation: ranked recall/MRR, optional RAGAS, manual first-answer accuracy.
Run from project root. Failed requests remain in the denominator. No invented quality scores.
"""
# 解析命令行选项，允许指定服务地址、样本、标签和评测模式。
import argparse
# 运行异步评审模型调用，避免在普通函数中手动维护事件循环。
import asyncio
# 在评测样本、HTTP 请求、结果文件和 Python 对象之间转换 JSON。
import json
# 提供有限数检查和向上取整，处理无效评分与 P95 下标。
import math
# 读取运行环境变量，模型凭证不写入源码。
import os
# 计算样本均值，不把缺失分数当成零分。
import statistics
# 使用单调时钟测量耗时，避免系统时间调整影响结果。
import time
# 为每条评测创建独立会话 ID，防止不同样本的历史串扰。
import uuid
# 以路径对象读取、创建与定位项目内的文件。
from pathlib import Path
# 通过 Python 标准库发送 HTTP 请求，无需为普通评测安装第三方客户端。
from urllib.request import Request, urlopen

# 计算前 K 个检索结果对人工标注证据的覆盖率和首个正确证据排名。
def ranked_metrics(retrieved, relevant, k):
    # 将标注证据去重；Recall 的分母是不同必要证据的数量。
    gold = set(relevant)
    # 没有金标准时无法判断召回好坏，返回缺失值。
    if not gold:
        # 保留未知状态，避免将没有标注的样本误算为正确或错误。
        return {"recall_at_k": None, "mrr": None}
    # 只检查前 K 个实际返回结果，后面的命中不计入本次指标。
    top = retrieved[:k]
    # 以命中的不同证据数除以金标准数，重复召回不能增加 Recall。
    recall = len(set(top) & gold) / len(gold)
    # 从 1 开始寻找首个正确证据的排名；没有命中时保留 None。
    rank = next((i + 1 for i, item in enumerate(top) if item in gold), None)
    # 输出 Recall 与倒数排名；无命中样本的 MRR 贡献为 0。
    return {"recall_at_k": recall, "mrr": 0.0 if rank is None else 1.0 / rank}

# 向问答服务发送 JSON POST，请求真正执行线上接口链路。
def post(base, path, payload):
    # 移除地址末尾斜杠后拼接接口路径，将请求体编码为 UTF-8。
    req = Request(base.rstrip("/") + path, data=json.dumps(payload).encode("utf-8"),
                  # 声明 JSON 请求类型和 POST 方法，保证 Spring MVC 正确读取请求体。
                  headers={"Content-Type": "application/json"}, method="POST")
    # 最多等待 190 秒，略长于服务端 180 秒上限；离开 with 后释放连接。
    with urlopen(req, timeout=190) as response:
        # 直接把 HTTP 响应流解析为对象，后续使用实际回答与上下文。
        return json.load(response)

# 统一处理缺失和非有限数值后计算均值。
def mean(values):
    # 排除 None、NaN 与无穷值，避免无效模型评分污染统计。
    values = [v for v in values if v is not None and math.isfinite(v)]
    # 有有效样本才返回均值，否则保留未知值。
    return statistics.mean(values) if values else None

# 汇总整批样本的正确率、检索质量、延迟以及失败情况。
def summarize(rows):
    # 只有正确性被人工标注或请求已失败的样本算作已知标签。
    known = [r for r in rows if r.get("correct") is not None]
    # 收集实际发生检索的成功请求耗时并排序，为均值和 P95 提供数据。
    times = sorted(r["response"]["retrievalMs"] for r in rows
                   # 排除请求失败、没有证据或未发生检索的请求，统计口径保持一致。
                   if r["success"] and r["response"].get("retrievedContexts") and r["response"]["retrievalMs"] > 0)
    # 记录总样本数与失败数；后续准确率仍以全部样本为分母。
    return {"samples": len(rows), "failures": sum(not r["success"] for r in rows),
            # 统计已标注比例；未全部标注前不能声称测得一次答准率。
            "label_coverage": len(known) / len(rows) if rows else 0,
            # 正确样本数除以总样本数，失败请求不会从分母中消失。
            "first_answer_accuracy": sum(r.get("correct") is True for r in rows) / len(rows)
                # 仅在每个样本都有正确性结论时给出准确率，否则输出 None。
                if rows and len(known) == len(rows) else None,
            # 计算有金标准样本的平均 Recall，不把缺失标注算作零。
            "recall_at_k": mean([r.get("recall_at_k") for r in rows]),
            # 计算有效样本的平均倒数排名，后续在混用标注单位时取消这个总体值。
            "mrr": mean([r.get("mrr") for r in rows]),
            # 汇总可用的 RAGAS 忠实度评分，未知评分保持缺失。
            "faithfulness": mean([r.get("faithfulness") for r in rows]),
            # 使用成功且实际发生检索的请求计算平均检索毫秒数。
            "retrieval_mean_ms": mean(times),
            # 采用最近秩法取第 95 百分位，列表下标转换为从 0 开始。
            "retrieval_p95_ms": times[math.ceil(len(times) * .95) - 1] if times else None,
            # 同时报告参与延迟计算的样本数量，便于判断统计是否充分。
            "latency_sample_count": len(times),
            # 这里保存验收目标，85% 与 2000ms 不是已测得的结果。
            "targets": {"first_answer_accuracy": .85, "retrieval_mean_ms": 2000}}

# 使用 RAGAS 对实际回答是否得到实际检索上下文支持进行异步评审。
async def score_faithfulness(rows):
    # 关闭 RAGAS 统计追踪；若调用者已设置该变量则保留原值。
    os.environ.setdefault("RAGAS_DO_NOT_TRACK", "true")
    # 评审模型通过 OpenAI 兼容协议调用，与业务生成模型配置分开。
    from openai import AsyncOpenAI
    # 把兼容协议客户端包装为 RAGAS 需要的结构化模型适配器。
    from ragas.llms import llm_factory
    # 使用 collections 版忠实度指标，检查回答中的事实是否有上下文依据。
    from ragas.metrics.collections import Faithfulness
    # 只从环境读取评审凭证，避免在文件或日志中保存明文 Key。
    key = os.environ.get("EVAL_API_KEY")
    # 开启 RAGAS 后必须提供评审模型凭证，不能以假评分替代调用。
    if not key:
        # 缺少评审凭证时明确终止该评分阶段并说明配置要求。
        raise RuntimeError("--ragas requires EVAL_API_KEY; use a compatible judge model")
    # 建立独立评审客户端，默认使用百炼兼容端点，可通过环境覆盖。
    client = AsyncOpenAI(api_key=key, base_url=os.environ.get("EVAL_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                         # 每次评审最多等待 60 秒、重试 1 次，避免评测无限挂起。
                         timeout=60, max_retries=1)
    # 将可能失败的模型操作放入保护范围，由对应异常或 finally 分支处理。
    try:
        # 将选择的评审模型接入 Faithfulness，生成事实陈述并验证证据支持情况。
        scorer = Faithfulness(llm=llm_factory(os.environ.get("EVAL_MODEL", "qwen-plus"), client=client))
        # 逐个处理已经保存的结果，不重新生成被评审的回答。
        for row in rows:
            # 只使用该次问答实际返回的上下文，不能加入 HyDE 假设文本。
            contexts = row.get("response", {}).get("retrievedContexts", [])
            # 失败请求或没有上下文时无法计算有意义的忠实度。
            if not row["success"] or not contexts:
                # 将无法完成评审的结果标为未知，不伪造零分或满分。
                row["faithfulness"] = None
                # 保存未评分的原因，报告读者可以区分无证据与模型故障。
                row["faithfulness_status"] = "no_context_or_request_failed"
                # 跳过没有可评审上下文的样本，继续处理其余结果。
                continue
            # 将可能失败的模型操作放入保护范围，由对应异常或 finally 分支处理。
            try:
                # 把问题、实际回答和实际上下文传给 RAGAS，等待多阶段评分完成。
                result = await scorer.ascore(user_input=row["question"], response=row["response"]["answer"], retrieved_contexts=contexts)
                # 只接受有限数值；RAGAS 返回 NaN 时仍视为未知。
                row["faithfulness"] = result.value if math.isfinite(result.value) else None
                # 另行标明评审返回 NaN，避免把缺失值误解为正常分数。
                if row["faithfulness"] is None: row["faithfulness_status"] = "judge_returned_nan"
            # 某条评审或请求失败时保存错误类型，避免整批结果丢失。
            except Exception as error:
                # 将无法完成评审的结果标为未知，不伪造零分或满分。
                row["faithfulness"] = None
                # 只记录异常类别，不把可能包含凭证的上游错误全文写入结果。
                row["faithfulness_status"] = type(error).__name__
    # 不论评审成功还是异常，都执行客户端资源清理。
    finally:
        # 异步关闭连接池，释放评审请求使用的网络资源。
        await client.close()

# 把逐条评测结果写成 JSONL，方便追加分析与按行读取。
def dump_lines(path, rows):
    # 创建输出目录，允许首次运行时目标文件夹尚不存在。
    path.parent.mkdir(parents=True, exist_ok=True)
    # 每条对象独占一行，保留中文，统一使用 UTF-8 写入。
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")

# 解析运行参数，执行请求或重用结果，然后输出质量报告与 Badcase。
def main():
    # 建立命令行解析器，非法参数会给出使用说明并退出。
    parser = argparse.ArgumentParser()
    # 可指定问答服务地址，默认连接本项目的 8180 端口。
    parser.add_argument("--base", default="http://127.0.0.1:8180")
    # 指定带问题和参考证据的 JSONL 样本文件。
    parser.add_argument("--cases", type=Path, default=Path("evaluation/cases.jsonl"))
    # 指定逐条结果文件；摘要和 Badcase 将写入同目录。
    parser.add_argument("--output", type=Path, default=Path("evaluation/results.jsonl"))
    # 读取人工正确性标签，内容必须为 case ID 到布尔值的映射。
    parser.add_argument("--labels", type=Path, help="JSON map of case id -> manual true/false")
    # 重用已保存的实际回答，避免标签应用到重新生成的不同答案上。
    parser.add_argument("--input", type=Path, help="Reuse saved results when applying manual labels; do not regenerate answers")
    # 此开关才启用付费评审模型调用，普通指标计算不需要它。
    parser.add_argument("--ragas", action="store_true")
    # 设置召回指标检查的前 K 条数量，默认与 Rerank Top5 对齐。
    parser.add_argument("--k", type=int, default=5)
    # 完成参数解析，后续统一通过 args 读取选项。
    args = parser.parse_args()
    # K 必须为正数，零或负数无法定义有效的 TopK 指标。
    if args.k < 1:
        # 提示非法 K 并结束运行，不使用不合理参数继续统计。
        parser.error("k must be positive")
    # 人工标签必须配合历史结果使用，避免新答案与旧标签错位。
    if args.labels and not args.input:
        # 要求调用者指定保存结果路径后再应用人工标签。
        parser.error("manual labels require --input saved-results.jsonl to avoid labeling newly generated answers")
    # 读取 UTF-8 标签文件；没有提供时使用空映射。
    labels = json.loads(args.labels.read_text(encoding="utf-8")) if args.labels else {}
    # 只接受 true/false，不接受字符串、数字或其他模糊标签。
    if any(not isinstance(value, bool) for value in labels.values()):
        # 拒绝不符合布尔标签格式的文件，保持准确率定义清晰。
        parser.error("labels must contain booleans")
    # 新评测时加载样本；重用结果时不再读取并执行问题。
    cases = [] if args.input else [json.loads(line) for line in args.cases.read_text(encoding="utf-8").splitlines() if line.strip()]
    # 重用模式一次性读入已有结果，普通模式从空结果列表开始。
    rows = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines() if line.strip()] if args.input else []
    # 以下分支只更新已保存结果的标签，不调用问答模型。
    if args.input:
        # 检查标签中的 case ID 是否都属于当前结果，防止标错评测批次。
        if set(labels) - {row["id"] for row in rows}: parser.error("labels contain unknown case IDs")
        # 逐个处理已经保存的结果，不重新生成被评审的回答。
        for row in rows:
            # 请求失败必定无法首次答对，强制记为 false。
            if not row["success"]: row["correct"] = False
            # 只更新提供了人工标签的样本，其余已有标注保持不变。
            elif row["id"] in labels: row["correct"] = labels[row["id"]]
    # 每条新样本独立调用完整问答链路。
    for case in cases:
        # 用单调时钟记录客户端总耗时，包含网络与服务端处理。
        start = time.monotonic()
        # 复制样本并初始化未知结果；不能把 HTTP 成功直接认定为答对。
        row = dict(case, success=False, correct=None, faithfulness=None, metric_k=args.k)
        # 优先采用分块 ID 标注，其次来源列表，最后兼容旧版单一 expectedSource。
        gold = case.get("relevantChunkIds") or case.get("relevantSources") or ([case["expectedSource"]] if case.get("expectedSource") else [])
        # 标明指标以分块还是来源文件为单位，两种口径不能混称。
        row["metric_unit"] = "chunk" if case.get("relevantChunkIds") else "source"
        # 将可能失败的模型操作放入保护范围，由对应异常或 finally 分支处理。
        try:
            # 生成随机会话 ID，发送当前问题并得到真实回答。
            reply = post(args.base, "/api/chat", {"sessionId": str(uuid.uuid4()), "message": case["question"]})
            # 保存成功响应；正确性依然由人工标签决定。
            row.update(success=True, response=reply, correct=labels.get(case["id"]))
            # 按标注单位取实际检索分块 ID 或来源列表，不能用模型自行引用替代召回结果。
            retrieved = reply.get("retrievedChunkIds", []) if row["metric_unit"] == "chunk" else reply.get("retrievedSources", [])
            # 将本样本的 Recall@K 与倒数排名并入结果。
            row.update(ranked_metrics(retrieved, gold, args.k))
        # 某条评审或请求失败时保存错误类型，避免整批结果丢失。
        except Exception as error:
            # 请求失败保存安全的异常类型、错误标签和未命中的检索指标。
            row.update(error=type(error).__name__, correct=False, **ranked_metrics([], gold, args.k))
        # 把单调时钟差转换为毫秒，记录完整客户端耗时。
        row["client_ms"] = round((time.monotonic() - start) * 1000)
        # 将样本保留在整批结果中，失败样本也保留。
        rows.append(row)
        # 保留部分结果，模型或评测过程失败后仍可复核。
        # 及时保存当前结果，评测中途失败时仍能复核已经完成的样本。
        dump_lines(args.output, rows)
    # 只有明确开启开关时，才调用 RAGAS 评审模型。
    if args.ragas:
        # 为异步评分建立并关闭事件循环，写回每条结果的忠实度。
        asyncio.run(score_faithfulness(rows))
    # 及时保存当前结果，评测中途失败时仍能复核已经完成的样本。
    dump_lines(args.output, rows)
    # 收集需要排查的样本，后续由人工填写根因与修复措施。
    badcases = []
    # 逐个处理已经保存的结果，不重新生成被评审的回答。
    for row in rows:
        # 为当前样本准备失败分类，同一条样本可能同时存在多个问题。
        reasons = []
        # 把接口请求失败归入独立类别，便于区分服务故障和内容错误。
        if not row["success"]: reasons.append("request_failed")
        # 人工判错的回答进入 Badcase；未知标签不会自动判错。
        if row.get("correct") is False: reasons.append("answer_incorrect")
        # 标注证据没有全部召回时，标记检索覆盖不足。
        if row.get("recall_at_k") is not None and row["recall_at_k"] < 1: reasons.append("retrieval_missed_gold")
        # 可用忠实度低于 0.8 时标记回答缺少依据，该阈值是排查规则。
        if row.get("faithfulness") is not None and row["faithfulness"] < .8: reasons.append("ungrounded_answer")
        # 存在至少一种已确认问题时保存样本，根因和修复暂留空供复核。
        if reasons: badcases.append(dict(row, badcase_reasons=reasons, root_cause=None, fix=None))
    # 将 Badcase 单独写入同目录，方便迭代和回归比较。
    dump_lines(args.output.with_name("badcases.jsonl"), badcases)
    # 根据完整结果列表生成聚合指标。
    report = summarize(rows)
    # 列出本批次实际使用的标注单位，防止不同口径混淆。
    report["units"] = sorted(set(r["metric_unit"] for r in rows))
    # 分别计算 source/chunk 级 Recall，不把不同单位的均值混合。
    report["retrieval_by_unit"] = {unit: {"recall_at_k": mean([r.get("recall_at_k") for r in rows if r["metric_unit"] == unit]),
                                        # 按同一单位分组计算 MRR，保留可比较的指标口径。
                                        "mrr": mean([r.get("mrr") for r in rows if r["metric_unit"] == unit])} for unit in report["units"]}
    # 一批样本混用两种单位时，取消无意义的总体检索均值。
    if len(report["units"]) > 1: report["recall_at_k"] = report["mrr"] = None
    # 在报告中记录 K 与缓存、HyDE 耗时口径，便于解释性能数字。
    report["run"] = {"hyDe_note": "HyDE/改写包含在retrievalMs；缓存可能影响延迟，比较配置需重启并固定样本顺序。", "k": args.k}
    # 把可读的汇总 JSON 写入独立文件，原逐条响应仍保留。
    args.output.with_suffix(".summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    # 在终端展示汇总结果，保留中文并缩进便于阅读。
    print(json.dumps(report, ensure_ascii=False, indent=2))

# 只在脚本直接执行时启动评测，导入指标函数用于测试时不会发请求。
if __name__ == "__main__":
    # 进入命令行评测流程。
    main()
