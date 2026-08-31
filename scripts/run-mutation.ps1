# Self-Compile 单轮闭环编排器
# 用法: .\scripts\run-mutation.ps1 [-Requirement "xxx"] [-SkipLaunch] [-SkipMcp]
param(
    [string]$Requirement = "示例：注册一个 selfcompile_probe 工具，只读返回同伴状态",
    [switch]$SkipLaunch,
    [switch]$SkipMcp
)

. "$PSScriptRoot\config.ps1"

Write-Output "=== Self-Compile 单轮闭环 ==="
Write-Output "需求: $Requirement"

# 1. 创建 mutation 工作区
$id = & "$PSScriptRoot\new-mutation.ps1" -Requirement $Requirement
if ($LASTEXITCODE -ne 0) { Write-Error "new-mutation 失败"; exit 1 }
Write-Output "[1/6] mutation-id: $id"

# 2. 安全编译 + 打包
$buildOut = & "$PSScriptRoot\build-jar.ps1" -MutationId $id
if ($LASTEXITCODE -ne 0) { Write-Error "build-jar 失败: $buildOut"; exit 1 }
Write-Output "[2/6] $buildOut"

# 3. 部署实验
$deployOut = & "$PSScriptRoot\deploy.ps1" -MutationId $id
if ($LASTEXITCODE -ne 0) { Write-Error "deploy 失败: $deployOut"; exit 1 }
Write-Output "[3/6] $deployOut"

# 4. 启动 MC（默认执行，-SkipLaunch 跳过）
if (-not $SkipLaunch) {
    $launchOut = & "$PSScriptRoot\launch-mc.ps1" -MutationId $id
    Write-Output "[4/6] $launchOut"
    if ($LASTEXITCODE -eq 1) { Write-Error "launch-mc 失败"; exit 1 }
    # 等待游戏加载（给玩家/世界加载留时间）
    Write-Output "等待游戏加载存档 gpt ..."
    Start-Sleep -Seconds 40
} else {
    Write-Output "[4/6] 跳过启动（-SkipLaunch）"
}

# 5. MCP 驱动
if (-not $SkipMcp) {
    $mcpOut = & "$PSScriptRoot\mcp-drive.ps1" -MutationId $id
    Write-Output "[5/6] $mcpOut"
    if ($LASTEXITCODE -ne 0) { Write-Error "mcp-drive 失败"; exit 1 }
} else {
    Write-Output "[5/6] 跳过 MCP（-SkipMcp）"
}

# 6. 验证
$verifyOut = & "$PSScriptRoot\verify.ps1" -MutationId $id
Write-Output "[6/6] $verifyOut"
if ($LASTEXITCODE -ne 0) { Write-Error "verify 未通过"; exit 1 }

Write-Output "=== 单轮闭环完成 ==="
Write-Output "报告: $(Join-Path $Script:SC_MUTATIONS $id)"
exit 0
