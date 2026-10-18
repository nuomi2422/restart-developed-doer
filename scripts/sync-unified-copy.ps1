# sync-unified-copy.ps1
# ============================================================================
# 作用：把「自编译统一版」真源，镜像同步到 rdd存放 的**打包副本**，并按需重建 zip。
#
# 为什么有这个脚本（运维 > 功能）：
#   2026-09-27 实测发现 rdd存放\自编译统一版_20260927\ 里 **8 份文档过期**
#   （含 00-总索引 / 10-总流程与双模式 / README / INDEX / 09-更新日志 / 11 / 12 / 02）。
#   过期副本会让新 AI 读到旧规则 → 属于 15-收尾检测「写新丢旧」类事故。
#   本脚本把"复制真源到副本"变成一条可重跑命令，从根上防再生锈。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File sync-unified-copy.ps1
#   powershell ... -File sync-unified-copy.ps1 -NoZip      # 只同步目录，不重建 zip
#   powershell ... -File sync-unified-copy.ps1 -DryRun     # 只报告差异，不写
#
# 退出码：0=成功；1=真源缺失。
# 注意：只镜像 统一版 顶层 *.md（规则文档）；_原档/_快照/_根文档 归档目录一并镜像。
# ============================================================================
[CmdletBinding()]
param(
    [string]$Source = "E:\新建文件夹\rdd-docs\rdd架构\自编译统一版",
    [string]$DestRoot = "E:\新建文件夹\rdd-docs\rdd存放",
    [string]$CopyName = "自编译统一版_20260927",
    [string]$ZipName = "自编译统一版_20260927.zip",
    [switch]$NoZip,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new()

if (-not (Test-Path -LiteralPath $Source)) {
    Write-Error "真源不存在：$Source"; exit 1
}

$dest = Join-Path $DestRoot "$CopyName\自编译统一版"   # 注意：副本内嵌一层同名目录（解压习惯）

# 1) 先报差异（防盲写）
$stale = 0; $ok = 0
Get-ChildItem -LiteralPath $Source -Recurse -File -Force | ForEach-Object {
    $rel = $_.FullName.Substring($Source.Length).TrimStart('\')
    $d = Join-Path $dest $rel
    if (Test-Path -LiteralPath $d) {
        if ((Get-FileHash -LiteralPath $_.FullName -Algorithm MD5).Hash -ne (Get-FileHash -LiteralPath $d -Algorithm MD5).Hash) {
            $script:stale++; Write-Host "  [STALE] $rel"
        } else { $script:ok++ }
    } else {
        $script:stale++; Write-Host "  [MISS ] $rel"
    }
}
Write-Host "差异：一致 $ok / 待更新 $stale"

if ($DryRun) { Write-Host "(-DryRun 仅报告，未写入)"; exit 0 }

# 2) 镜像复制（robocopy /MIR：多退少补，副本严格等于真源）
$null = New-Item -ItemType Directory -Path $dest -Force
$rc = robocopy $Source $dest /MIR /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) { Write-Error "robocopy 失败 exit=$LASTEXITCODE"; exit 1 }

# 3) 重建 zip（从真源打，保证 zip 与真源一致）
$zipPath = Join-Path $DestRoot $ZipName
if (-not $NoZip) {
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    Compress-Archive -Path (Join-Path $Source '*') -DestinationPath $zipPath -CompressionLevel Optimal
    Write-Host ("zip 重建：{0}  {1}B" -f $zipName, (Get-Item -LiteralPath $zipPath).Length)
}

# 4) 校验：副本逐份与真源 MD5 一致
$left = 0
Get-ChildItem -LiteralPath $Source -Recurse -File -Force | ForEach-Object {
    $rel = $_.FullName.Substring($Source.Length).TrimStart('\')
    $d = Join-Path $dest $rel
    if (-not (Test-Path -LiteralPath $d) -or
        (Get-FileHash -LiteralPath $_.FullName -Algorithm MD5).Hash -ne (Get-FileHash -LiteralPath $d -Algorithm MD5).Hash) {
        $script:left++; Write-Warning "仍不一致：$rel"
    }
}
if ($left -gt 0) { Write-Error "同步后仍有 $left 份不一致"; exit 1 }
Write-Host "✅ 同步完成：副本与真源逐份一致。真源=$Source  副本=$dest"
