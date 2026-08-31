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

# 收紧 verdict（2026-08-31）：优先检查证据链 evidence/*.json（与 MutationVerification 对齐）。
# 只有全部证据文件存在才 VERIFIED；无 evidence 目录时回退旧 hasEvents 判定（兼容旧流程）。
$total = if ($snapshot.total) { $snapshot.total } else { 0 }
$hasEvents = $total -gt 0

$evidenceDir = Join-Path $dir "evidence"
$required = @("deployed.json","mc_loaded.json","tool_registered.json","mcp_call_success.json","world_effect_verified.json")
$missing = @()
if (Test-Path $evidenceDir) {
    foreach ($f in $required) { if (-not (Test-Path (Join-Path $evidenceDir $f))) { $missing += $f } }
} else {
    $missing = @("NO_EVIDENCE_DIR")
}
$evidenceOk = $missing.Count -eq 0
$verified = if ($evidenceOk) { $true } elseif ($missing[0] -eq "NO_EVIDENCE_DIR" -and $hasEvents) { $true } else { $false }

$verdict = @{
    verdict = if ($verified) { "VERIFIED" } else { "UNVERIFIED" }
    stage = "verify"
    evidenceChain = $evidenceOk
    missingEvidence = $missing
    monitorTotal = $total
    hasEvents = $hasEvents
    monitorDir = $Script:MONITOR_DIR
    snapshotSavedTo = $snapPath
    checkedAt = (Get-Date).ToString("o")
    note = if ($evidenceOk) { "证据链完整：$($required -join ',')" }
           elseif ($missing[0] -eq "NO_EVIDENCE_DIR") { "无 evidence 目录，回退 hasEvents（旧流程，建议接入证据链）" }
           elseif ($hasEvents) { "证据链缺失：$($missing -join ',')" }
           else { "监测台无事件，需检查游戏/MCP 是否就绪" }
    state = if ($verified) { "VERIFIED" } else { "FAILED" }
}
Write-ResultJson (Join-Path $dir "verdict.json") $verdict
if ($verified) { Write-Output "VERIFY_OK total=$total evidence=$evidenceOk" } else { Write-Output "VERIFY_FAIL total=$total evidence=$evidenceOk"; exit 1 }
exit 0
