# 实施记录

计划：`docs/superpowers/plans/2026-09-22-sales-agent-demo.md`

- Task 1：完成依赖版本锁定、编译和 SDK 接口验证；未配置 DASHSCOPE_API_KEY，真实模型验证留待配置凭证后执行。
- Task 2：完成递归切分、两路入库、重建互斥、manifest 恢复和测试。
- Task 3：完成 BM25 + 向量召回、按分块 ID 去重、Rerank 映射和测试。
- Task 4：完成真实 MCP HTTP 服务和客户端；已从打包应用经 MCP 读取指定 GitHub 仓库的文件树和 README，模拟业务调用测试通过。
- Task 5：完成意图与追问改写、AgentScope 工具循环、会话隔离、来源校验和超时控制；使用 HTTP 模型替身验证程序链路。
- Task 6：已生成 README、项目文档、21 条评测案例和评测脚本；最终验证结果见 docs/VERIFICATION.md。

最终验证：2026-09-22 15:33 +08:00，完整 `verify` 成功；22 项默认测试与 1 项真实 Milvus 测试通过，仅百炼真实模型探针因没有 Key 跳过。真实 Milvus collection 写入、检索和重建已验证；尚未跑真实模型质量/性能评测。

代码审查与联调发现并已修复：生成模型 URL 重复 /api/v1；Qwen3.7 需显式多模态端点；会话初始化持全局锁；MCP 故障阻断已有知识证据回答；显式 HTTP 404 被通用异常处理覆盖。

首轮真实 Docker 部署发现 MinIO Docker Hub 镜像失效，已改为同版本官方 Quay 镜像；Docker Desktop 已启动用于 Milvus 验证。

工作目录是新项目而非 Git 仓库，直接在用户指定目录实施，不创建没有基线的 worktree。
