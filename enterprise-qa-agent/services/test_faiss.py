# 重新加载服务模块，模拟进程重启后从磁盘恢复索引。
import importlib
# 覆盖快照环境变量，让测试写入独立目录而不影响演示索引。
import os
# 为快照准备自动清理的隔离临时目录。
import tempfile
# 使用标准库断言检索顺序、持久化与非法输入行为。
import unittest
# 构造临时快照路径，避免手动拼接平台分隔符。
from pathlib import Path

# 针对实际 FAISS 原生索引执行测试，不用手写搜索替身。
class FaissServiceTest(unittest.TestCase):
    # 检查归一化搜索、重复 ID 更新及重新加载后的结果一致性。
    def test_cosine_search_upsert_and_restart(self):
        # 每次测试独立保存 NPZ，测试结束自动清理该目录。
        with tempfile.TemporaryDirectory() as folder:
            # 在导入服务前指定测试快照，隔离项目的正式数据目录。
            os.environ["FAISS_SNAPSHOT"] = str(Path(folder) / "index.npz")
            # 导入实际服务模块，使原生 FAISS 和 NumPy 参与执行。
            import faiss_service
            # 清空模块内存状态并重新读取指定快照，确保测试起点可控。
            service = importlib.reload(faiss_service)
            # 建立二维空索引，方便人工判断两个正交方向的搜索结果。
            service.reset(service.Reset(dimension=2))
            # 写入两个分块及不同长度的正交向量，验证归一化不受向量长度影响。
            service.upsert(service.Upsert(chunks=[{"chunkId":"a","text":"A","source":"a.md","ordinal":0},
                                                  # 第二条分块与第二个向量对应，用于测试另一查询方向。
                                                  {"chunkId":"b","text":"B","source":"b.md","ordinal":0}],
                                         # 提供 [5,0] 与 [0,2]，它们归一化后分别是两个单位坐标方向。
                                         vectors=[[5,0],[0,2]]))
            # 沿第一个方向查询两条结果，a 应排在前面。
            hits = service.search(service.Search(vector=[1,0],topK=2))["hits"]
            # 验证 FAISS 行号正确映射到分块 a，没有来源错配。
            self.assertEqual(hits[0]["chunk"]["chunkId"], "a")
            # 相同方向的余弦相似度应近似 1，允许浮点计算误差。
            self.assertAlmostEqual(hits[0]["score"], 1)
            # 把当前原生索引、向量及元数据真实写入快照文件。
            service.publish()
            # 重新加载模块读取快照，模拟服务重启而非沿用旧内存引用。
            restored = importlib.reload(service)
            # 确认恢复或同 ID 更新后都保持两条分块，不重复插入旧 ID。
            self.assertEqual(restored.count()["count"], 2)
            # 沿第二个方向查询，恢复后的索引仍应返回分块 b。
            self.assertEqual(restored.search(restored.Search(vector=[0,1],topK=1))["hits"][0]["chunk"]["chunkId"], "b")
            # 更新 a 的正文与向量，测试按 ID 覆盖而非追加的写入语义。
            restored.upsert(restored.Upsert(chunks=[{"chunkId":"a","text":"updated","source":"a.md","ordinal":0}],vectors=[[0,1]]))
            # 确认恢复或同 ID 更新后都保持两条分块，不重复插入旧 ID。
            self.assertEqual(restored.count()["count"], 2)

    # 校验零向量与维度不一致的请求都会被服务明确拒绝。
    def test_rejects_wrong_dimensions_and_zero_vector(self):
        # 直接调用实际服务函数验证入口中的向量校验逻辑。
        import faiss_service as service
        # 建立二维空索引，方便人工判断两个正交方向的搜索结果。
        service.reset(service.Reset(dimension=2))
        # 零向量不能做余弦归一化，必须抛出错误。
        with self.assertRaises(Exception): service.search(service.Search(vector=[0,0],topK=1))
        # 二维索引收到三维向量时必须拒绝，不能隐式截断。
        with self.assertRaises(Exception): service.search(service.Search(vector=[1,0,0],topK=1))

# 直接运行文件时执行测试，导入时不自动操作索引。
if __name__ == "__main__": unittest.main()
