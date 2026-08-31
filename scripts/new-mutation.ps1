# 创建一个 mutation 工作区，返回 mutation-id
param([string]$Requirement = "示例：注册一个 selfcompile_probe 工具，只读返回同伴状态")

. "$PSScriptRoot\config.ps1"

$id = "mutation-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$dir = Join-Path $Script:SC_MUTATIONS $id
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
    requirement = $Requirement
    state = "WORKSPACE_CREATED"
    createdAt = (Get-Date).ToString("o")
}
Write-ResultJson (Join-Path $dir "mutation.json") $result
Write-Output $id
exit 0
