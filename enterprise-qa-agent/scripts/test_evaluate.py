# 使用标准库单元测试，测试指标不需要任何模型或网络。
import unittest
# 导入被测试的纯指标函数，导入时不会运行 main 发起评测。
from evaluate import ranked_metrics, summarize

# 验证评测统计口径，防止重复证据或未知标签让指标失真。
class MetricsTest(unittest.TestCase):
    # 验证重复召回不增加 Recall、首个命中排名决定 MRR。
    def test_rank_and_recall(self):
        # 前 3 条只有 a 命中；a 出现在第 2 位，金标准包含 a 和 c。
        scores = ranked_metrics(["b", "a", "a", "c"], ["a", "c"], 3)
        # 确认 Recall=1/2，首个命中倒数排名=1/2。
        self.assertEqual(scores, {"recall_at_k": .5, "mrr": .5})
        # 空召回对已标注问题的 MRR 应为 0，而不是缺失值。
        self.assertEqual(ranked_metrics([], ["a"], 5)["mrr"], 0)

    # 没有人工正确性标签时，不允许把 HTTP 成功当作答案正确。
    def test_missing_labels_cannot_prove_accuracy(self):
        # 构造成功但未标注的样本，模拟只跑接口尚未人工评审的阶段。
        rows = [{"success": True, "correct": None, "response": {"retrievalMs": 0}}]
        # 未知正确性必须让一次答准率保持 None。
        self.assertIsNone(summarize(rows)["first_answer_accuracy"])

    # 确认请求失败仍纳入准确率分母，不能通过剔除失败样本提高数字。
    def test_failures_count_against_accuracy(self):
        # 构造一个请求失败和一个答对的样本，总体正确率应为 1/2。
        rows = [{"success": False, "correct": False},
                # 答对样本同时包含检索证据和耗时，可以正常进入延迟统计。
                {"success": True, "correct": True, "response": {"retrievalMs": 10, "retrievedContexts": ["evidence"]}}]
        # 两个样本中只有一个答对，正确率必须等于 0.5。
        self.assertEqual(summarize(rows)["first_answer_accuracy"], .5)

# 脚本直接运行时启动 unittest，导入时只提供测试定义。
if __name__ == "__main__": unittest.main()
