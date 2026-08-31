# 期3：自变异止损循环 — 多轮尝试直到通过或止损
# 用法: .\scripts\run-mutation-loop.ps1 -Requirement "xxx" [-MaxAttempts 3] [-SkipLaunch] [-SkipMcp]
param(
    [string]$Requirement,
    [int]$MaxAttempts = 3,
    [switch]$SkipLaunch,
    [switch]$SkipMcp
)

. "$PSScriptRoot\config.ps1"

if (-not $Requirement) { $Requirement = "示例：注册一个 NumenTool" }

Write-Output "=== Self-Compile 止损循环 (max=$MaxAttempts) ==="

$attempt = 0
$lastVerdict = $null
$lastError = ""

while ($attempt -lt $MaxAttempts) {
    $attempt++
    Write-Output ""
    Write-Output "----- 第 $attempt/$MaxAttempts 轮 -----"

    # 每轮一个独立 mutation-id（干净工作区）
    $id = & "$PSScriptRoot\new-mutation.ps1" -Requirement "$Requirement [attempt $attempt]"
    Write-Output "mutation-id: $id"

    # 编译打包
    $buildOut = & "$PSScriptRoot\build-jar.ps1" -MutationId $id
    if ($LASTEXITCODE -ne 0) {
        Write-Output "编译失败: $buildOut"
        $lastError = "compile-failed"
        # 读编译日志
        $log = Join-Path $Script:SC_MUTATIONS (Join-Path $id "phase-build.log")
        if (Test-Path $log) { $lastError = (Get-Content $log -Raw | Select-Object -First 1) }
        continue   # 下一轮改码重试
    }
    Write-Output "编译 OK: $buildOut"

    # 部署
    $deployOut = & "$PSScriptRoot\deploy.ps1" -MutationId $id
    if ($LASTEXITCODE -ne 0) { Write-Output "部署失败"; $lastError = "deploy-failed"; continue }
    Write-Output "部署 OK"

    # 启动 + MCP + 验证（除非跳过）
    if (-not $SkipLaunch) {
        $launchOut = & "$PSScriptRoot\launch-mc.ps1" -MutationId $id
        Write-Output "启动: $launchOut"
        if ($LASTEXITCODE -eq 1) { $lastError = "launch-failed"; continue }
        Write-Output "等待世界加载..."
        Start-Sleep -Seconds 40
    }
    if (-not $SkipMcp) {
        $mcpOut = & "$PSScriptRoot\mcp-drive.ps1" -MutationId $id
        Write-Output "MCP: $mcpOut"
        if ($LASTEXITCODE -ne 0) { $lastError = "mcp-failed"; continue }
    }

    $verifyOut = & "$PSScriptRoot\verify.ps1" -MutationId $id
    Write-Output "验证: $verifyOut"
    if ($LASTEXITCODE -eq 0) {
        $lastVerdict = "VERIFIED"
        Write-Output ""
        Write-Output "=== 第 $attempt 轮通过 ==="
        Write-Output "mutation: $id"
        Write-Output "verdict: $id/verdict.json"
        exit 0
    } else {
        $lastError = "verify-failed"
    }
}

# 止损
Write-Output ""
Write-Output "=== 止损: $MaxAttempts 轮均未通过 ==="
Write-Output "最后错误: $lastError"
exit 1