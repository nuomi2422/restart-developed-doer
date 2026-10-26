# cleanup-logs.ps1
# ============================================================================
# 作用：按保留策略清理「会膨胀」的轮转日志 / 归档 / 历史产物。
#
# 为什么（运维 > 功能）：
#   2026-09-28 实测 monitor 目录 1.61GB，其中 context-*.jsonl 93 个占 1.50GB（93%）。
#   根因：rdd.jsonl / instrumentation.jsonl 代码里会「轮转 + 清超量归档」，
#         但 context-*.jsonl 只轮转、从不清理 → 长期无限增长。
#   本脚本做"按策略清理"，只删【轮转/归档/历史】产物，绝不碰活文件与源码。
#
# 安全设计：
#   - 默认【只报告】（DryRun），必须显式 -Apply 才真正删除。
#   - 只匹配「轮转命名」（如 context-<时间戳>.jsonl），绝不删 context.jsonl 等活文件。
#   - 不碰 selfcompile\stable（版本库）、不碰源码、不碰 .git。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File cleanup-logs.ps1           # 只报告
#   powershell ... -File cleanup-logs.ps1 -Apply                                   # 执行
#   powershell ... -File cleanup-logs.ps1 -InstanceDir "D:\rdd\mc\The Best of Twilight Forest"
#
# 保留策略（可用参数覆盖）：
#   ContextKeep=10  最大的 10 个 context-*.jsonl 保留，其余删
#   RddKeep=5       最大的 5  个 rdd-*.jsonl 保留
#   ArchiveKeep=3   monitor-archive 里最大的 3 个保留
# ============================================================================
[CmdletBinding()]
param(
    [string]$InstanceDir = "D:\rdd\mc\The Best of Twilight Forest",
    [string]$RuntimeDir  = "D:\rdd\workspace\rdd-selfcompile",
    [string]$BackupDir   = "$env:USERPROFILE\.config\opencode\AGENTS.backup",
    [int]$ContextKeep = 10,
    [int]$RddKeep = 5,
    [int]$ArchiveKeep = 3,
    [int]$ReportKeep = 3,
    [int]$MutationKeep = 10,
    [int]$HeartbeatMaxMB = 1,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new()

$plan = New-Object System.Collections.ArrayList   # 每项: @{Path; Reason; Bytes}
$freed = 0L

function Add-Plan($file, $reason) {
    if ($null -eq $file) { return }
    $b = 0L
    try { $b = (Get-Item -LiteralPath $file.FullName -ErrorAction SilentlyContinue).Length } catch {}
    [void]$plan.Add([pscustomobject]@{ Path = $file.FullName; Reason = $reason; Bytes = $b })
    $script:freed += $b
}

# 只保留「最新 Keep 个」轮转文件，删除更老的
function PrunePattern($dir, $pattern, $keep, $reason) {
    if (-not (Test-Path -LiteralPath $dir)) { return }
    $files = Get-ChildItem -LiteralPath $dir -File -Force -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -match $pattern } |
             Sort-Object LastWriteTime -Descending
    if ($files.Count -le $keep) { return }
    $files | Select-Object -Skip $keep | ForEach-Object { Add-Plan $_ $reason }
}

$monitor = Join-Path $InstanceDir "config\numen\monitor"
$archive = Join-Path $InstanceDir "config\numen\monitor-archive"

# 1) monitor 轮转文件（只匹配带时间戳的轮转名，活文件 context.jsonl/rdd.jsonl 不动）
PrunePattern $monitor '^context-\d+\.jsonl$' $ContextKeep  "context 轮转（只留最新 $ContextKeep）"
PrunePattern $monitor '^rdd-\d+\.jsonl$'     $RddKeep      "rdd 轮转（只留最新 $RddKeep）"
PrunePattern $monitor '^tools-\d+\.jsonl$'   $RddKeep      "tools 轮转（只留最新 $RddKeep）"
PrunePattern $monitor '^ai-\d+\.jsonl$'      $RddKeep      "ai 轮转（只留最新 $RddKeep）"
PrunePattern $monitor '^state-\d+\.jsonl$'   $RddKeep      "state 轮转（只留最新 $RddKeep）"
PrunePattern $monitor '^events-\d+\.jsonl$'  $RddKeep      "events 轮转（只留最新 $RddKeep）"

# 2) monitor-archive 大档案
PrunePattern $archive '\.jsonl$' $ArchiveKeep "monitor-archive（只留最新 $ArchiveKeep）"

# 2b) monitor-archive 里「大且不新」的档案 → gzip 压缩（保证据、省空间；不删）
$ArchiveGzipOverMB = 50
if (Test-Path -LiteralPath $archive) {
    Get-ChildItem -LiteralPath $archive -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -eq '.jsonl' -and $_.Length -gt ($ArchiveGzipOverMB * 1MB) -and $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
        ForEach-Object { [void]$plan.Add([pscustomobject]@{ Path = $_.FullName; Reason = "monitor-archive 大档案 gzip（>${ArchiveGzipOverMB}MB，保证据）"; Bytes = [long]($_.Length * 0.85) }) }  # 预估可省 ~85%
}

# 3) 游戏内 selfcompile mutation（历史工作区）
$mut = Join-Path $InstanceDir "config\numen\selfcompile\mutations"
if (Test-Path -LiteralPath $mut) {
    Get-ChildItem -LiteralPath $mut -Directory -Force -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -Skip $MutationKeep |
        ForEach-Object { Add-Plan (Get-Item -LiteralPath $_.FullName) "游戏内 mutation（只留最新 $MutationKeep）" }
}

# 4) 外部 report 产物
$rep = Join-Path $RuntimeDir "report"
if (Test-Path -LiteralPath $rep) {
    Get-ChildItem -LiteralPath $rep -File -Force -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -Skip $ReportKeep |
        ForEach-Object { Add-Plan $_ "report 产物（只留最新 $ReportKeep）" }
}

# 5) requests 旧请求（保留最近 5）
$req = Join-Path $RuntimeDir "scripts\requests"
PrunePattern $req '^req-.*\.json$' 5 "旧请求（只留最新 5）"

# 6) drive-heartbeat.log 超限轮转（不删，改成 .1 备份）
$hb = Join-Path $RuntimeDir "scripts\drive-heartbeat.log"
if (Test-Path -LiteralPath $hb) {
    $mb = (Get-Item -LiteralPath $hb).Length / 1MB
    if ($mb -gt $HeartbeatMaxMB) {
        [void]$plan.Add([pscustomobject]@{ Path = $hb; Reason = "心跳日志超 ${HeartbeatMaxMB}MB → 轮转为 .1（不删）"; Bytes = 0 })
    }
}

# 7) AGENTS 备份只留最近 10
if (Test-Path -LiteralPath $BackupDir) {
    Get-ChildItem -LiteralPath $BackupDir -File -Force -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -Skip 10 |
        ForEach-Object { Add-Plan $_ "AGENTS 备份（只留最新 10）" }
}

# ---- 输出 ----
Write-Host "===== 清理计划（$(if($Apply){'执行'}else{'只报告'})） ====="
if ($plan.Count -eq 0) {
    Write-Host "无需清理。"
} else {
    $plan | Group-Object Reason | ForEach-Object {
        $s = ($_.Group | Measure-Object Bytes -Sum).Sum
        Write-Host ("  {0,3} 项  {1,9:N1} MB  {2}" -f $_.Count, ($s/1MB), $_.Name)
    }
    Write-Host ("  --- 合计可释放 {0:N1} MB（{1} 项）" -f ($freed/1MB), $plan.Count)
}

if (-not $Apply) {
    Write-Host ""
    Write-Host "（默认只报告；加 -Apply 才真正删除）"
    exit 0
}

$deleted = 0
foreach ($p in $plan) {
    try {
        if ($p.Reason -like "*轮转为 .1*") {
            $one = ($p.Path).Substring(0, ($p.Path).Length - 4) + ".1.log"
            if (Test-Path -LiteralPath $one) { Remove-Item -LiteralPath $one -Force }
            Move-Item -LiteralPath $p.Path -Destination $one -Force
        } elseif ($p.Reason -like "*gzip*") {
            $gz = $p.Path + ".gz"
            if (Test-Path -LiteralPath $gz) { Remove-Item -LiteralPath $gz -Force }
            $in = [System.IO.File]::OpenRead($p.Path)
            $out = [System.IO.File]::Create($gz)
            $gzs = New-Object System.IO.Compression.GZipStream($out, [System.IO.Compression.CompressionLevel]::Optimal)
            try { $in.CopyTo($gzs) } finally { $gzs.Dispose(); $out.Dispose(); $in.Dispose() }
            Remove-Item -LiteralPath $p.Path -Force
        } else {
            Remove-Item -LiteralPath $p.Path -Recurse -Force
        }
        $deleted++
    } catch { Write-Warning "处理失败：$($p.Path)  $($_.Exception.Message)" }
}
Write-Host ("✅ 已处理 $deleted/$($plan.Count) 项，释放约 {0:N1} MB" -f ($freed/1MB))
