# 读监测台 + 世界/事件对撞，判定验证结论
param([string]$MutationId)

. "$PSScriptRoot\config.ps1"

$dir = Join-Path $Script:SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }

$snapshot = $null
try {
    $snapshot = Invoke-RestMethod -Uri "$($Script:MONITOR_URL)/api/snapshot" -Method Get -TimeoutSec 30
} catch {
    $snapshot = @{ total = 0; error = $_.Exception.Message }
}

# 保存监测台快照
$snapPath = Join-Path $dir "monitor-snapshot\snapshot.json"
[IO.File]::WriteAllText($snapPath, ($snapshot | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))

# 判定：monitor total > 0 且有事件，视为观测链路通；具体行为验证需 MCP 调用 + 世界对撞
$total = if ($snapshot.total) { $snapshot.total } else { 0 }
$hasEvents = $total -gt 0

$verdict = @{
    verdict = if ($hasEvents) { "VERIFIED" } else { "UNVERIFIED" }
    stage = "verify"
    monitorTotal = $total
    hasEvents = $hasEvents
    monitorDir = $Script:MONITOR_DIR
    snapshotSavedTo = $snapPath
    checkedAt = (Get-Date).ToString("o")
    note = if ($hasEvents) { "监测台观测到 Numen 事件" } else { "监测台无事件，需检查游戏/MCP 是否就绪" }
    state = if ($hasEvents) { "VERIFIED" } else { "FAILED" }
}
Write-ResultJson (Join-Path $dir "verdict.json") $verdict
if ($hasEvents) { Write-Output "VERIFY_OK total=$total" } else { Write-Output "VERIFY_FAIL total=$total"; exit 1 }
exit 0
