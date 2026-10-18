# 读监测台 + 世界/事件对撞，判定验证结论
param(
    [string]$MutationId,
    [ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE
)

$env:RDD_SELF_COMPILE_INSTANCE = if ($Instance) { $Instance } else { 'test' }
. "$PSScriptRoot\config.ps1"

$dir = Join-Path $global:RDD_SC.SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }

$snapshot = $null
try {
    $snapshot = Invoke-RestMethod -Uri "$($global:RDD_SC.MONITOR_URL)/api/snapshot" -Method Get -TimeoutSec 30
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
# 2026-09-29 修复：原先只看「文件存在」，不看里面的 ok 字段 ——
# 于是 collect-evidence 明明记了 {"ok":false,"detail":"tool returned isError"}，
# verify 照样给 VERIFIED。形式通过 = 假通过，正是 flow-gate/verify 这类门禁最该防的事。
$failed = @()
if (Test-Path $evidenceDir) {
    foreach ($f in $required) {
        $p = Join-Path $evidenceDir $f
        if (-not (Test-Path $p)) { $missing += $f; continue }
        $ok = $false
        try { $ok = [bool]((Get-Content -LiteralPath $p -Raw -Encoding UTF8 | ConvertFrom-Json).ok) }
        catch { $ok = $false }
        if (-not $ok) { $failed += $f }
    }
} else {
    $missing = @("NO_EVIDENCE_DIR")
}
$evidenceOk = ($missing.Count -eq 0) -and ($failed.Count -eq 0)
# Events prove only that something was observed; they never substitute for
# deployed/tool/world evidence. Historical fallback caused false VERIFIED.
$verified = $evidenceOk

$verdict = @{
    verdict = if ($verified) { "VERIFIED" } else { "UNVERIFIED" }
    stage = "verify"
    evidenceChain = $evidenceOk
    missingEvidence = $missing
    failedEvidence = $failed
    monitorTotal = $total
    hasEvents = $hasEvents
    monitorDir = $global:RDD_SC.MONITOR_DIR
    instance = $global:RDD_SC.SC_INSTANCE
    mcpPort = $global:RDD_SC.MCP_PORT
    snapshotSavedTo = $snapPath
    checkedAt = (Get-Date).ToString("o")
    note = if ($evidenceOk) { "证据链完整：$($required -join ',')" }
           elseif ($missing[0] -eq "NO_EVIDENCE_DIR") { "无 evidence 目录；监测事件不能替代验证证据" }
           elseif ($hasEvents) { "证据链缺失：$($missing -join ',')" }
           else { "监测台无事件，需检查游戏/MCP 是否就绪" }
    state = if ($verified) { "VERIFIED" } else { "FAILED" }
}
Write-ResultJson (Join-Path $dir "verdict.json") $verdict
if ($verified) { Write-Output "VERIFY_OK total=$total evidence=$evidenceOk" } else { Write-Output "VERIFY_FAIL total=$total evidence=$evidenceOk"; exit 1 }
exit 0
