# 声明可选开关；指定 WorkspaceCache 时使用项目内 Maven 仓库。
param([switch]$WorkspaceCache)
# 把 PowerShell 命令错误转为终止异常，避免失败后继续执行。
$ErrorActionPreference = 'Stop'
# 切换到脚本的上级项目目录，并保存原工作目录供 finally 恢复。
Push-Location (Split-Path $PSScriptRoot -Parent)
# 无论构建成功或失败，都进入 finally 恢复调用者的工作目录。
try {
    # 复用 .work/m2 依赖缓存并完整执行 clean verify，适用于本机隔离验证。
    if ($WorkspaceCache) { & mvn '-Dmaven.repo.local=.work/m2' clean verify }
    # 普通开发环境使用 Maven 默认仓库，执行相同的构建与测试阶段。
    else { & mvn clean verify }
    # PowerShell 不会自动把外部进程非零退出码当作异常，这里显式检查。
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败' }
# 恢复进入脚本前的目录，构建失败也不会改变用户的终端位置。
} finally { Pop-Location }
