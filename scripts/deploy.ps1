# 部署 jar 到实验 mods（当前生产实例 mods 目录，阶段1同实例双存档）
param(
    [string]$MutationId,
    [ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE
)

$env:RDD_SELF_COMPILE_INSTANCE = if ($Instance) { $Instance } else { 'test' }
. "$PSScriptRoot\config.ps1"

$dir = Join-Path $global:RDD_SC.SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }

# 必须显式 -Encoding UTF8：Write-ResultJson 写的是 UTF-8 **无 BOM**，而 PS 5.1 的
# Get-Content 默认按系统 ANSI(GBK) 读 —— 里面有中文(比如构建失败时写的 gradle 错误摘要)
# 就会被读成乱码并把 JSON 解析搞崩，症状是"Invalid object passed in"而不是"build failed"，
# 把真正的错误盖掉。同一类坑已在 mc-watchdog.ps1(缺 BOM)和 2026-09-28 的控制字符污染上各撞一次。
$phase = Get-Content -LiteralPath (Join-Path $dir "phase-build.json") -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $phase.success) { throw "build phase not successful" }
if ($phase.instance -and $phase.instance -ne $global:RDD_SC.SC_INSTANCE) { throw "mutation instance '$($phase.instance)' does not match selected '$($global:RDD_SC.SC_INSTANCE)'" }

$jar = Get-ChildItem (Join-Path $dir "jar") -Filter "*.jar" | Select-Object -First 1
if (-not $jar) { throw "no jar in mutation jar/ dir" }

# 部署到实验 mods（同实例 mods）
$dest = Join-Path $global:RDD_SC.MC_MODS $jar.Name
if (Test-Path $dest) {
    $bak = "$dest.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Copy-Item $dest $bak -Force
}
Copy-Item $jar.FullName $dest -Force
$sha = (Get-FileHash $dest -Algorithm SHA256).Hash

$result = @{
    success = $true
    stage = "deploy"
    deployedTo = $dest
    instance = $global:RDD_SC.SC_INSTANCE
    sha256 = $sha
    state = "DEPLOYED_EXPERIMENT"
}
Write-ResultJson (Join-Path $dir "phase-deploy.json") $result
Write-Output "DEPLOY_OK"
exit 0
