# 创建一个 mutation 工作区，返回 mutation-id
param(
    [string]$Requirement = "示例：注册一个 selfcompile_probe 工具，只读返回同伴状态",
    [ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE
)

$env:RDD_SELF_COMPILE_INSTANCE = if ($Instance) { $Instance } else { 'test' }
. "$PSScriptRoot\config.ps1"

$id = "mutation-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([guid]::NewGuid().ToString('N').Substring(0,6))"
$dir = Join-Path $global:RDD_SC.SC_MUTATIONS $id
New-Item -ItemType Directory -Path $dir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $dir "source") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $dir "jar") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $dir "deploy") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $dir "mcp-log") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $dir "monitor-snapshot") -Force | Out-Null

[IO.File]::WriteAllText((Join-Path $dir "requirement.txt"), "$Requirement`n", [Text.UTF8Encoding]::new($false))

$result = @{
    mutationId = $id
    dir = $dir
    instance = $global:RDD_SC.SC_INSTANCE
    gameDir = $global:RDD_SC.MC_GAME_DIR
    monitorUrl = $global:RDD_SC.MONITOR_URL
    mcpPort = $global:RDD_SC.MCP_PORT
    requirement = $Requirement
    state = "WORKSPACE_CREATED"
    createdAt = (Get-Date).ToString("o")
}
Write-ResultJson (Join-Path $dir "mutation.json") $result
Write-Output $id
exit 0
