"""Loopback-only FAISS service. Run from project root; no pickle/deserialization of remote files."""
# 在评测样本、HTTP 请求、结果文件和 Python 对象之间转换 JSON。
import json
# 读取运行环境变量，模型凭证不写入源码。
import os
# 通过可重入锁保护共享索引、元数据和向量，避免读写竞争。
import threading
# 以路径对象读取、创建与定位项目内的文件。
from pathlib import Path
# 使用 FAISS 原生索引库，而非手写距离排序替代实际后端。
import faiss
# 用连续 float32 数组传递向量，并保存不含 pickle 的快照。
import numpy as np
# 提供 HTTP 路由和明确的状态码错误响应。
from fastapi import FastAPI, HTTPException
# 定义请求体类型、维度及 TopK 范围，非法参数在入口处被拒绝。
from pydantic import BaseModel, Field

# 建立 FAISS HTTP 应用，供 Java FaissChunkStore 调用。
app = FastAPI(title="Enterprise FAISS")
# 同一线程可以再次获得此锁，所有索引操作共用它保护一致性。
lock = threading.RLock()
# 允许覆盖索引快照路径，默认保存在项目 data/faiss 目录。
snapshot = Path(os.environ.get("FAISS_SNAPSHOT", "data/faiss/index.npz"))
# 未入库且没有快照时没有可检索索引，搜索入口会返回未就绪。
index = None
# 按 FAISS 行号保存分块元数据，用于将搜索结果映射回正文和来源。
chunks = []
# 保留已归一化向量，用于重复 ID 更新后重建索引及快照保存。
vectors = None

# 检测已有快照，服务重启时恢复先前发布的数据。
if snapshot.exists():
    # 禁止反序列化 pickle 对象，快照只读取数组与 UTF-8 元数据。
    with np.load(snapshot, allow_pickle=False) as data:
        # 从快照中的原生索引字节恢复 FAISS 搜索结构。
        index = faiss.deserialize_index(data["index"])
        # 把字节数组还原为 UTF-8 JSON，再恢复与索引行对应的分块信息。
        chunks = json.loads(data["metadata"].tobytes().decode("utf-8"))
        # 读取保存的归一化向量，为后续 upsert 保留原始索引行数据。
        vectors = data["vectors"]
        # 索引行数、元数据数、向量数必须相等，防止返回错配的知识来源。
        if len(chunks) != index.ntotal or len(chunks) != len(vectors):
            # 快照不一致时明确拒绝启动，不静默使用损坏的索引。
            raise RuntimeError("FAISS snapshot is inconsistent")

# 声明重建请求结构，重建会替换当前服务索引。
class Reset(BaseModel):
    # 限制合法维度范围，避免建立零维或超大维度索引。
    dimension: int = Field(ge=1, le=16000)
# 声明批量写入请求，分块列表必须与向量列表一一对应。
class Upsert(BaseModel):
    # 每个分块包含 chunkId、text、source 和 ordinal 元数据。
    chunks: list[dict]
    # 二维浮点列表，每行是对应分块的 Embedding 向量。
    vectors: list[list[float]]
# 声明单条查询向量与返回数量的请求结构。
class Search(BaseModel):
    # 接收查询向量，实际维度会与已建索引再次校验。
    vector: list[float]
    # 限制返回 1 至 100 条，避免无限扩张响应。
    topK: int = Field(ge=1, le=100)

# 供写入、发布、查询共用的索引就绪检查。
def require_index():
    # 索引不存在时拒绝操作，调用者必须先完成 reset/rebuild。
    if index is None:
        # 通过 HTTP 503 表明知识索引尚未建立，而非返回空结果冒充成功。
        raise HTTPException(503, "reset/rebuild first")

# 为 Java 侧的 reset 操作注册 POST 路由。
@app.post("/reset")
# 按照指定维度建立空索引，开始一次全量重建。
def reset(req: Reset):
    # 本操作会替换进程级共享索引、分块和向量引用。
    global index, chunks, vectors
    # 在持锁期间操作共享状态，搜索不会看到索引与元数据的中间态。
    with lock:
        # 建立内积平面索引；向量归一化后内积即余弦相似度。
        index = faiss.IndexFlatIP(req.dimension)
        # 按 FAISS 行号保存分块元数据，用于将搜索结果映射回正文和来源。
        chunks = []
        # 建立形状为 0×dimension 的 float32 空矩阵，保留正确维度。
        vectors = np.empty((0, req.dimension), dtype="float32")
    # 向调用者确认本次操作完成，不在此响应中暴露其他索引数据。
    return {"ok": True}

# 注册分块和向量的批量更新接口。
@app.post("/upsert")
# 按分块 ID 去重更新数据，再同步重建 FAISS 与元数据行号。
def upsert(req: Upsert):
    # 本操作会替换进程级共享索引、分块和向量引用。
    global index, chunks, vectors
    # 在持锁期间操作共享状态，搜索不会看到索引与元数据的中间态。
    with lock:
        # 先确认已经初始化索引，后面的维度检查才有有效基准。
        require_index()
        # 分块和向量数量不同会造成行号错配，因此立即拒绝。
        if len(req.chunks) != len(req.vectors):
            # 用 HTTP 400 明确提示调用者修正批量数量。
            raise HTTPException(400, "chunk/vector count mismatch")
        # 空批次视为无需更新，避免对空数组执行 stack。
        if not req.chunks:
            # 向调用者确认本次操作完成，不在此响应中暴露其他索引数据。
            return {"ok": True}
        # 统一将输入向量转换为 FAISS 需要的 float32 数组。
        array = np.asarray(req.vectors, dtype="float32")
        # 检查二维形状、索引维度和有限数值，拒绝 NaN 与无穷值。
        if array.ndim != 2 or array.shape[1] != index.d or not np.isfinite(array).all():
            # 拒绝维度或数值不合法的批次，保持原索引不受污染。
            raise HTTPException(400, "invalid dimensions or values")
        # 零向量没有可定义的余弦方向，不能参与归一化检索。
        if (np.linalg.norm(array, axis=1) == 0).any():
            # 将零向量错误明确反馈给 Embedding 调用方。
            raise HTTPException(400, "zero vector")
        # 逐条校验分块元数据，确保响应可还原正文及来源。
        for chunk in req.chunks:
            # 四个必要字段都必须存在，缺少一个就无法可靠映射搜索结果。
            if not all(k in chunk for k in ("chunkId", "text", "source", "ordinal")):
                # 拒绝缺字段分块，不接受无法溯源的数据。
                raise HTTPException(400, "invalid chunk")
        # 就地将每行向量归一化为单位长度，使内积搜索等价于余弦搜索。
        faiss.normalize_L2(array)
        # 以 chunkId 建立旧分块和向量的映射，准备按 ID 覆盖更新。
        rows = {c["chunkId"]: (c, v) for c, v in zip(chunks, vectors)}
        # 新批次中同 ID 替换旧数据，新的 ID 则加入映射。
        rows.update({c["chunkId"]: (c, v) for c, v in zip(req.chunks, array)})
        # 按映射顺序重建元数据列表，保证与下一行向量顺序一致。
        chunks = [row[0] for row in rows.values()]
        # 把每条分块的归一化向量重新拼成连续二维矩阵。
        vectors = np.stack([row[1] for row in rows.values()]).astype("float32")
        # 创建同维度新索引，避免重复 ID 更新造成旧向量残留。
        index = faiss.IndexFlatIP(index.d)
        # 按矩阵行顺序写入全部向量；FAISS 行号与 chunks 下标保持一致。
        index.add(vectors)
    # 向调用者确认本次操作完成，不在此响应中暴露其他索引数据。
    return {"ok": True}

# 注册将内存中一致索引发布到磁盘的接口。
@app.post("/publish")
# 把原生索引、向量和元数据保存为一个可原子替换的快照。
def publish():
    # 在持锁期间操作共享状态，搜索不会看到索引与元数据的中间态。
    with lock:
        # 先确认已经初始化索引，后面的维度检查才有有效基准。
        require_index()
        # 首次发布时创建快照父目录，已有目录不报错。
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        # 先写同目录临时文件，完整成功后再替换正式快照。
        temporary = snapshot.with_suffix(".tmp")
        # 以二进制模式写入临时快照，with 结束时确保文件关闭。
        with temporary.open("wb") as stream:
            # 将 FAISS 序列化字节及向量保存进同一个 NPZ，避免多文件版本错配。
            np.savez(stream, index=faiss.serialize_index(index), vectors=vectors,
                     # 把中文 JSON 编码为 UTF-8 字节数组，无需 pickle 即可保存元数据。
                     metadata=np.frombuffer(json.dumps(chunks, ensure_ascii=False).encode("utf-8"), dtype=np.uint8))
        # 完成写入后替换正式快照，启动恢复不会读取半写文件。
        temporary.replace(snapshot)
    # 向调用者确认本次操作完成，不在此响应中暴露其他索引数据。
    return {"ok": True}

# 为 Java 入库一致性检查提供只读分块数量接口。
@app.get("/count")
# 返回当前实际索引行数，尚未初始化时返回 0。
def count():
    # 在持锁期间操作共享状态，搜索不会看到索引与元数据的中间态。
    with lock:
        # 使用 FAISS ntotal 作为计数，避免只根据外部元数据估计。
        return {"count": 0 if index is None else index.ntotal}

# 注册向量 TopK 搜索接口，返回分块正文、来源及分数。
@app.post("/search")
# 对单个查询向量执行归一化内积搜索。
def search(req: Search):
    # 在持锁期间操作共享状态，搜索不会看到索引与元数据的中间态。
    with lock:
        # 先确认已经初始化索引，后面的维度检查才有有效基准。
        require_index()
        # 将查询包装为只有一行的 float32 矩阵，符合 FAISS batch API。
        array = np.asarray([req.vector], dtype="float32")
        # 查询也必须维度一致、数值有限且非零，防止检索异常。
        if array.shape[1] != index.d or not np.isfinite(array).all() or np.linalg.norm(array) == 0:
            # 非法查询向量返回 HTTP 400，不返回无意义的相似度结果。
            raise HTTPException(400, "invalid query vector")
        # 就地将每行向量归一化为单位长度，使内积搜索等价于余弦搜索。
        faiss.normalize_L2(array)
        # 搜索数量不超过已有行数；空索引至少请求一位再过滤 FAISS 的 -1 哨兵。
        scores, ids = index.search(array, min(req.topK, max(1, index.ntotal)))
        # 按 FAISS 排序结果还原分块，并把 NumPy 标量转为可 JSON 编码的普通数值。
        return {"hits": [{"chunk": chunks[int(i)], "score": float(score)}
                         # 跳过 -1 哨兵行，避免空索引意外返回 chunks 的最后一条数据。
                         for i, score in zip(ids[0], scores[0]) if i >= 0]}
