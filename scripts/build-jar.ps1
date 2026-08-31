# 安全编译 + 打包 selfcompile 插件 jar（期3：正确检测编译失败）
param([string]$MutationId)

. "$PSScriptRoot\config.ps1"

$dir = Join-Path $Script:SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }

$env:JAVA_HOME = $Script:JAVA_HOME
$env:GRADLE_OPTS = $Script:GRADLE_OPTS

Write-Output "编译插件模块 $Script:PLUGIN_MODULE ..."
Push-Location $Script:NUMMEN_REPO
$logFile = Join-Path $dir "phase-build.log"
try {
    & $Script:GRADLE -p $Script:NUMMEN_REPO ":$($Script:PLUGIN_MODULE):jar" --no-build-cache --no-daemon *> $logFile
} finally {
    Pop-Location
}

# 编译日志内容
$log = if (Test-Path $logFile) { Get-Content $logFile -Raw } else { "" }

# 失败判定：日志含编译失败标记，或缺少 BUILD SUCCESSFUL
$compileFailed = ($log -match "错误") -or ($log -match "\d+ 个错误") -or ($log -match "BUILD FAILED")
$buildOk = (-not $compileFailed) -and ($log -match "BUILD SUCCESSFUL")

if (-not $buildOk) {
    # 提取错误摘要
    $errSummary = ""
    $errLines = $log -split "`n" | Select-String -Pattern "错误|FAILED" | Select-Object -First 3
    if ($errLines) { $errSummary = ($errLines -join " | ") }
    $result = @{ success = $false; stage = "build-jar"; error = $errSummary; log = $logFile }
    Write-ResultJson (Join-Path $dir "phase-build.json") $result
    Write-Output "BUILD_FAIL: $errSummary"
    exit 1
}

# 找产物 jar
$jar = Get-ChildItem (Join-Path $Script:NUMMEN_REPO $Script:PLUGIN_JAR_GLOB) -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) {
    $result = @{ success = $false; stage = "build-jar"; error = "no plugin jar produced"; log = $logFile }
    Write-ResultJson (Join-Path $dir "phase-build.json") $result
    Write-Output "BUILD_FAIL: no jar"
    exit 1
}

$jarDest = Join-Path $dir "jar"
Copy-Item $jar.FullName $jarDest -Force
$sha = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash

$result = @{
    success = $true
    stage = "build-jar"
    jarPath = $jar.FullName
    jarCopiedTo = (Join-Path $jarDest $jar.Name)
    sha256 = $sha
    state = "COMPILED"
}
Write-ResultJson (Join-Path $dir "phase-build.json") $result
Write-Output "BUILD_OK"
exit 0