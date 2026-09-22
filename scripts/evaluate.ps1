param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$CasesPath = "$PSScriptRoot/../evaluation/cases.jsonl",
    [string]$OutputPath = "$PSScriptRoot/../evaluation/results.jsonl"
)
$ErrorActionPreference = 'Stop'
# 此脚本会调用真实模型并产生费用；先按 README 启动服务并重建知识库。
$results = @()
foreach ($line in Get-Content -LiteralPath $CasesPath -Encoding UTF8) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $case = $line | ConvertFrom-Json
    $payload = @{ sessionId = [Guid]::NewGuid().ToString(); message = $case.question } | ConvertTo-Json
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $reply = Invoke-RestMethod -Uri "$BaseUrl/api/chat" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 100
        $result = [ordered]@{ id=$case.id; question=$case.question; referenceAnswer=$case.referenceAnswer; expectedSource=$case.expectedSource; expectedIntent=$case.expectedIntent; success=$true; response=$reply; clientMs=$timer.ElapsedMilliseconds; correct=$null }
    } catch {
        $result = [ordered]@{ id=$case.id; question=$case.question; expectedIntent=$case.expectedIntent; success=$false; error=$_.Exception.Message; clientMs=$timer.ElapsedMilliseconds; correct=$null }
    }
    $results += [pscustomobject]$result
}
$results | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 12 } | Set-Content -LiteralPath $OutputPath -Encoding UTF8
# 只统计实际发生了检索的成功请求；失败率单独报告，不能把失败样本隐去。
$retrieval = @($results | Where-Object { $_.success -and $_.response.retrievalMs -gt 0 } | ForEach-Object { [double]$_.response.retrievalMs } | Sort-Object)
$failed = @($results | Where-Object { -not $_.success }).Count
Write-Output "请求数=$($results.Count)，失败数=$failed；逐条结果已写入 $OutputPath"
if ($retrieval.Count -gt 0) {
    $avg = ($retrieval | Measure-Object -Average).Average
    $p95 = $retrieval[[Math]::Ceiling($retrieval.Count * .95) - 1]
    Write-Output "检索平均耗时=$([Math]::Round($avg,2)) ms，P95=$p95 ms"
}
Write-Output 'correct 字段留空，需人工结合事实、证据、是否直接解决问题标注 true/false，不能把 HTTP 成功当作答对。'
