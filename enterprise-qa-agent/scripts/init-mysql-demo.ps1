# 使用已构建 JAR 中的 JDBC 驱动，不需要安装 mysql 命令行客户端。
param([string]$AdminConfig = '')
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AdminConfig)) {
    $AdminConfig = Join-Path $projectDirectory 'mysql-admin.properties'
}
$applicationJar = Join-Path $projectDirectory 'target/enterprise-qa-agent-1.0.0.jar'
if (-not (Test-Path -LiteralPath $AdminConfig)) {
    throw '请复制 mysql-admin.properties.example 为 mysql-admin.properties，并填写管理账号密码。'
}
if (-not (Test-Path -LiteralPath $applicationJar)) {
    throw '请先运行 mvn -DskipTests package。'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$toolsDirectory = Join-Path $projectDirectory '.work/mysql-tools'
New-Item -ItemType Directory -Path $toolsDirectory -Force | Out-Null
$driverPath = Join-Path $toolsDirectory 'mysql-connector-j.jar'
$archive = [System.IO.Compression.ZipFile]::OpenRead($applicationJar)
try {
    $driver = @($archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/mysql-connector-j-[^/]+\.jar$' })
    if ($driver.Count -ne 1) { throw '构建产物中找不到 MySQL JDBC 驱动，请重新运行 mvn -DskipTests package。' }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($driver[0], $driverPath, $true)
} finally {
    $archive.Dispose()
}
# Java 21 可直接执行源文件；密码只从本地文件读取，不出现在命令行。
& java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' '--class-path' $driverPath (Join-Path $PSScriptRoot 'MySqlDemoSetup.java') $AdminConfig (Join-Path $PSScriptRoot 'mysql-demo.sql')
if ($LASTEXITCODE -ne 0) { throw 'MySQL 初始化失败，请根据上面的错误检查连接和账号。' }
