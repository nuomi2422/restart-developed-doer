# 部署 jar 到实验 mods（当前生产实例 mods 目录，阶段1同实例双存档）
param(
    [string]$MutationId,
    [ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE
)

$env:RDD_SELF_COMPILE_INSTANCE = if ($Instance) { $Instance } else { 'test' }
. "$PSScriptRoot\config.ps1"

$dir = Join-Path $global:RDD_SC.SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }

$phase = Get-Content (Join-Path $dir "phase-build.json") -Raw | ConvertFrom-Json
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
