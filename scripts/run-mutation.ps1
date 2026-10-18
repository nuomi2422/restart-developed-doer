# Self-Compile 单轮闭环编排器
# 用法: .\scripts\run-mutation.ps1 [-Requirement "xxx"] [-SkipLaunch] [-SkipMcp]
param(
    [string]$Requirement = "示例：注册一个 selfcompile_probe 工具，只读返回同伴状态",
    # 默认 gpt（2026-09-30 用户指示；原来是 test，会让验证跑在错的存档上）
    [ValidateSet('test','gpt')][string]$Instance = 'gpt',
    [switch]$SkipLaunch,
    [switch]$SkipMcp,
    [string]$Module,
    # 2026-10-02 用户定：默认 120（2 小时），旧的「10 分钟/次」限制作废。
    [int]$AutoStopMinutes = 120,
    # 采集 verify 要求的五件证据；不开则 verify 必然 UNVERIFIED。
    [switch]$CollectEvidence,
    [string]$EvidenceTool = ""
)

$env:RDD_SELF_COMPILE_INSTANCE = $Instance
if (-not [string]::IsNullOrWhiteSpace($Module)) { $env:RDD_SELF_COMPILE_MODULE = $Module }
. "$PSScriptRoot\config.ps1"

Write-Output "=== Self-Compile 单轮闭环 ==="
Write-Output "需求: $Requirement"
Write-Output "实例: $($global:RDD_SC.SC_INSTANCE) ($($global:RDD_SC.MC_GAME_DIR))"
Write-Output "构建模块: $($global:RDD_SC.PLUGIN_MODULE)"

# 1. 创建 mutation 工作区
$id = & "$PSScriptRoot\new-mutation.ps1" -Requirement $Requirement -Instance $Instance
if ($LASTEXITCODE -ne 0) { Write-Error "new-mutation 失败"; exit 1 }
Write-Output "[1/6] mutation-id: $id"

# 2. 安全编译 + 打包
$buildOut = & "$PSScriptRoot\build-jar.ps1" -MutationId $id -ModuleOverride $Module
if ($LASTEXITCODE -ne 0) { Write-Error "build-jar 失败: $buildOut"; exit 1 }
Write-Output "[2/6] $buildOut"

# 3. 部署实验
$deployOut = & "$PSScriptRoot\deploy.ps1" -MutationId $id
if ($LASTEXITCODE -ne 0) { Write-Error "deploy 失败: $deployOut"; exit 1 }
Write-Output "[3/6] $deployOut"

# 4. 启动 MC（默认执行，-SkipLaunch 跳过）
if (-not $SkipLaunch) {
    $launchOut = & "$PSScriptRoot\launch-mc.ps1" -MutationId $id -AutoStopMinutes $AutoStopMinutes
    Write-Output "[4/6] $launchOut"
    if ($LASTEXITCODE -eq 1) { Write-Error "launch-mc 失败"; exit 1 }
    # 等待游戏加载（给玩家/世界加载留时间）
    Write-Output "等待游戏加载存档 $($global:RDD_SC.MC_SAVE) ..."
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

# 5b. 采集验证证据（2026-09-29 补）
# verify.ps1 要求 evidence/*.json 五件才给 VERIFIED，但闭环里从来没人生产过它们，
# 于是所有 mutation 永远 UNVERIFIED（交接文档「验证节是空的」的真正根因）。
if ($CollectEvidence) {
    $evOut = & "$PSScriptRoot\collect-evidence.ps1" -MutationId $id -Instance $Instance -ToolName $EvidenceTool
    Write-Output "[5b/6] $evOut"
} else {
    Write-Output "[5b/6] 跳过证据采集（-CollectEvidence 开启；不采集则 verify 必为 UNVERIFIED）"
}

# 6. 验证
$verifyOut = & "$PSScriptRoot\verify.ps1" -MutationId $id
Write-Output "[6/6] $verifyOut"
if ($LASTEXITCODE -ne 0) { Write-Error "verify 未通过"; exit 1 }

Write-Output "=== 单轮闭环完成 ==="
Write-Output "报告: $(Join-Path $global:RDD_SC.SC_MUTATIONS $id)"
exit 0
