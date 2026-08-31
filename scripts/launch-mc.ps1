# 启动 Minecraft 并进入 gpt 存档
param([string]$MutationId)

. "$PSScriptRoot\config.ps1"

# 先检查是否已有游戏在跑
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine -match 'The Best of Twilight Forest' }
if ($running) {
    $result = @{ success = $false; stage = "launch-mc"; error = "minecraft already running; refusing to start second instance" }
    Write-ResultJson (Join-Path $Script:SC_MUTATIONS (Join-Path $MutationId "phase-launch.json")) $result
    Write-Output "LAUNCH_SKIP_ALREADY_RUNNING"
    exit 2
}

if (-not (Test-Path $Script:MC_BAT)) { throw "launcher bat not found: $Script:MC_BAT" }

Start-Process -FilePath $Script:MC_BAT -WorkingDirectory "E:\.minecraft\versions\The Best of Twilight Forest\"
Start-Sleep -Seconds 3

$result = @{
    success = $true
    stage = "launch-mc"
    save = $Script:MC_SAVE
    startedAt = (Get-Date).ToString("o")
    note = "Minecraft launching into save '$($Script:MC_SAVE)'; wait for world load before MCP"
    state = "LAUNCHED"
}
Write-ResultJson (Join-Path $Script:SC_MUTATIONS (Join-Path $MutationId "phase-launch.json")) $result
Write-Output "LAUNCH_OK"
exit 0
