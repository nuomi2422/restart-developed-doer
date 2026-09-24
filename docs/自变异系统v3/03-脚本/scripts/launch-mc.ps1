# 启动 Minecraft 并进入 gpt 存档
param(
    [string]$MutationId,
    [ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE,
    [int]$AutoStopMinutes = 120
)

$env:RDD_SELF_COMPILE_INSTANCE = if ($Instance) { $Instance } else { 'test' }
. "$PSScriptRoot\config.ps1"

# 先检查是否已有游戏在跑
# 注意：server 目录名可能出现在非游戏进程里（如 WineFox soul-core-bridge 的
# --memory-root 就含 "The Best of Twilight Forest"），必须排除它们，否则永远误报
# "already running"，游戏永远拉不起来。
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine -like "*$($global:RDD_SC.MC_GAME_DIR)*" -and $_.CommandLine -notlike "*soul-core-bridge*" }
if ($running) {
    $result = @{ success = $false; stage = "launch-mc"; error = "minecraft already running; refusing to start second instance" }
    Write-ResultJson (Join-Path $global:RDD_SC.SC_MUTATIONS (Join-Path $MutationId "phase-launch.json")) $result
    Write-Output "LAUNCH_SKIP_ALREADY_RUNNING"
    exit 2
}

if (-not (Test-Path $global:RDD_SC.MC_BAT)) { throw "launcher bat not found: $($global:RDD_SC.MC_BAT)" }

Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', ('"' + $global:RDD_SC.MC_BAT + '"')) -WorkingDirectory $global:RDD_SC.MC_GAME_DIR
Start-Sleep -Seconds 3

# 连带拉起看门狗：到点自动关闭游戏，防止 AI 崩溃/离线时空转耗电
$watchdog = $null
if ($AutoStopMinutes -gt 0) {
    $wdScript = Join-Path $PSScriptRoot 'mc-watchdog.ps1'
    $wdLog = Join-Path $global:RDD_SC.SC_MUTATIONS "watchdog-$($global:RDD_SC.SC_INSTANCE).log"
    $watchdog = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $wdScript + '"'),
        '-Instance', ($global:RDD_SC.SC_INSTANCE),
        '-GameDir', ('"' + $global:RDD_SC.MC_GAME_DIR + '"'),
        '-AutoStopMinutes', ([string]$AutoStopMinutes),
        '-LogPath', ('"' + $wdLog + '"')
    ) -WindowStyle Hidden -PassThru
}

$result = @{
    success = $true
    stage = "launch-mc"
    save = $global:RDD_SC.MC_SAVE
    instance = $global:RDD_SC.SC_INSTANCE
    gameDir = $global:RDD_SC.MC_GAME_DIR
    startedAt = (Get-Date).ToString("o")
    watchdog = if ($watchdog) { @{ pid = $watchdog.Id; autoStopMinutes = $AutoStopMinutes; log = (Join-Path $global:RDD_SC.SC_MUTATIONS "watchdog-$($global:RDD_SC.SC_INSTANCE).log") } } else { $null }
    note = "Minecraft launching into save '$($global:RDD_SC.MC_SAVE)'; wait for world load before MCP" + $(if ($watchdog) { "; auto-stop in ${AutoStopMinutes}min (watchdog pid $($watchdog.Id))" } else { "" })
    state = "LAUNCHED"
}
Write-ResultJson (Join-Path $global:RDD_SC.SC_MUTATIONS (Join-Path $MutationId "phase-launch.json")) $result
Write-Output "LAUNCH_OK"
exit 0
