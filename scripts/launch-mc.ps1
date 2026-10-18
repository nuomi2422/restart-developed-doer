# 启动 Minecraft 并进入 gpt 存档
param(
    [string]$MutationId,
    [ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE,
    # 默认 120（2 小时）—— 2026-10-02 用户定：「不要再加什么十分钟的检测，默认一律 2 小时」。
    # 旧的「用户在场 <=10 分钟/次」铁律作废；自动关只为防空转耗电，不是门禁。
    [int]$AutoStopMinutes = 120
)

# 2026-09-30 用户指示：默认 gpt（原来两处 fallback 都是 test，导致"默认跑错存档"）。
# ⚠️ 这里必须与 config.ps1 的 Select-RddSelfCompileInstance 保持一致 ——
#    两处不一致时本行会先把 env 写成 test，config 再读到非空 env 就跟着用 test，
#    改一处不改另一处 = 没改。
$env:RDD_SELF_COMPILE_INSTANCE = if ($Instance) { $Instance } else { 'gpt' }
. "$PSScriptRoot\config.ps1"

# 先检查是否已有游戏在跑
# 注意：server 目录名可能出现在非游戏进程里（如 WineFox soul-core-bridge 的
# --memory-root 就含 "The Best of Twilight Forest"），必须排除它们，否则永远误报
# "already running"，游戏永远拉不起来。
# 2026-09-30：判定收进 mc-instance.ps1（单一真源），并回显**实际检查的目录** ——
# 之前"检查 test 却启动 gpt"的错就是靠手写路径造成的，这里让路径只有一个来源。
$instanceDir = $global:RDD_SC.MC_GAME_DIR
$running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine -like "*$instanceDir*" -and $_.CommandLine -notlike "*soul-core-bridge*" })
Write-Output "LAUNCH_CHECK instance=$($global:RDD_SC.SC_INSTANCE) dir=$instanceDir running=$($running.Count)"
if ($running.Count -gt 0) {
    $result = @{ success = $false; stage = "launch-mc"; error = "minecraft already running; refusing to start second instance" }
    if ($MutationId) {
        Write-ResultJson (Join-Path $global:RDD_SC.SC_MUTATIONS (Join-Path $MutationId "phase-launch.json")) $result
    }
    Write-Output "LAUNCH_SKIP_ALREADY_RUNNING"
    exit 2
}

if (-not (Test-Path $global:RDD_SC.MC_BAT)) { throw "launcher bat not found: $($global:RDD_SC.MC_BAT)" }

# 启动器 bat 以 `pause` 结尾（等人按键看"游戏已退出"），自动化下它会永远挂在
# cmd.exe 上不退出；若不重定向输出，它还会继承调用方的 stdout 管道 → 上层
# shell（AI 的 Bash 调用）永远等不到 EOF，表现为"游戏退了命令还卡着"。
# 修法：输出重定向到文件 + 隐藏控制台 + 记下 PID 供看门狗回收。
if (-not (Test-Path $global:RDD_SC.SC_MUTATIONS)) { New-Item -ItemType Directory -Path $global:RDD_SC.SC_MUTATIONS -Force | Out-Null }
$launchLog = Join-Path $global:RDD_SC.SC_MUTATIONS "launch-$($global:RDD_SC.SC_INSTANCE).log"
$launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', ('"' + $global:RDD_SC.MC_BAT + '"')) `
    -WorkingDirectory $global:RDD_SC.MC_GAME_DIR `
    -RedirectStandardOutput $launchLog `
    -RedirectStandardError "$launchLog.err" `
    -WindowStyle Hidden -PassThru
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
        '-LogPath', ('"' + $wdLog + '"'),
        '-LauncherPid', ([string]$launcher.Id)
    ) -RedirectStandardOutput "$wdLog.out" -RedirectStandardError "$wdLog.err" -WindowStyle Hidden -PassThru
}

$result = @{
    success = $true
    stage = "launch-mc"
    save = $global:RDD_SC.MC_SAVE
    instance = $global:RDD_SC.SC_INSTANCE
    gameDir = $global:RDD_SC.MC_GAME_DIR
    startedAt = (Get-Date).ToString("o")
    watchdog = if ($watchdog) { @{ pid = $watchdog.Id; autoStopMinutes = $AutoStopMinutes; log = (Join-Path $global:RDD_SC.SC_MUTATIONS "watchdog-$($global:RDD_SC.SC_INSTANCE).log") } } else { $null }
    launcher = @{ pid = $launcher.Id; log = $launchLog; errLog = "$launchLog.err"; note = "输出已重定向，脱离调用方管道；控制台已隐藏，游戏窗口照常显示" }
    note = "Minecraft launching into save '$($global:RDD_SC.MC_SAVE)'; wait for world load before MCP" + $(if ($watchdog) { "; auto-stop in ${AutoStopMinutes}min (watchdog pid $($watchdog.Id))" } else { "" })
    state = "LAUNCHED"
}
Write-Output "LAUNCH_OK"
# 只有带了 -MutationId 才写 phase-launch.json。
# 不带 MutationId 是"临时手动起一次"的常见用法，此时 Join-Path 会因空串抛
# ParameterBindingValidationException（刷一屏红字，还会盖住真正的错误）。
if ($MutationId) {
    Write-ResultJson (Join-Path $global:RDD_SC.SC_MUTATIONS (Join-Path $MutationId "phase-launch.json")) $result
}
exit 0
