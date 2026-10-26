<#
    mc-watchdog.ps1 - 游戏看门狗：到点自动关闭 Minecraft 游戏进程。
    由 launch-mc.ps1 在启动成功后连带拉起，与 launch 进程解耦：
    即使 AI / 编码器崩溃或退出，本看门狗仍独立存活，到 AutoStopMinutes 后强杀游戏，防止空转耗电。

    用法（供 launch-mc.ps1 调用，不必手动跑）：
      powershell -NoProfile -ExecutionPolicy Bypass -File mc-watchdog.ps1 -Instance test -GameDir "<dir>" -AutoStopMinutes 120
#>
param(
    [string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE,
    [string]$GameDir = '',
    [int]$AutoStopMinutes = 120,
    [int]$CheckIntervalSeconds = 30,
    [string]$LogPath = '',
    [int]$LauncherPid = 0
)

if ([string]::IsNullOrWhiteSpace($Instance)) { $Instance = 'test' }
if ([string]::IsNullOrWhiteSpace($GameDir)) { Write-Output 'WATCHDOG_NO_GAMEDIR'; exit 1 }
if ([string]::IsNullOrWhiteSpace($LogPath)) { $LogPath = 'D:\rdd\workspace\rdd-selfcompile\mutations\watchdog-' + $Instance + '.log' }

# 同一实例只允许一个看门狗存活。
# 注意:判据必须落在**子进程命令行里真实存在的参数**上。以前这里匹配的是一个
# 叫 $SelfMarker 的字符串,而 launch-mc.ps1 从来没把它传下去 —— 于是这条判据
# 永远匹配不到,每次启动都多拉一个看门狗(实测:同一实例并存两个)。
$InstanceArg = '-Instance ' + $Instance
$existing = Get-CimInstance Win32_Process -Filter "Name='powershell.exe' OR Name='pwsh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*mc-watchdog.ps1*' -and $_.CommandLine -like ('*' + $InstanceArg + '*') -and $_.ProcessId -ne $PID }
if ($existing) {
    $msg = '[' + (Get-Date).ToString('s') + '] watchdog already running for ' + $Instance + ', this instance exits.'
    Add-Content -LiteralPath $LogPath -Value $msg -Encoding UTF8 -ErrorAction SilentlyContinue
    Write-Output 'WATCHDOG_EXIT_DUPLICATE'
    exit 0
}

$deadline = (Get-Date).AddMinutes($AutoStopMinutes)
$msg = '[' + (Get-Date).ToString('s') + '] WATCHDOG_START instance=' + $Instance + ' deadline=' + $deadline.ToString('s') + ' minutes=' + $AutoStopMinutes + ' gameDir=' + $GameDir
Add-Content -LiteralPath $LogPath -Value $msg -Encoding UTF8 -ErrorAction SilentlyContinue
Write-Output $msg

$pattern = (Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like ('*' + $GameDir + '*') -and $_.CommandLine -notmatch 'mc-watchdog' } | ForEach-Object { $_.ProcessId })

# 回收启动器控制台：bat 以 pause 结尾，游戏退出后 cmd.exe 会永远等按键。
# 不回收就会残留一个空控制台（多次运行会累积），也曾因继承 stdout 管道导致
# 上层 shell 永不 EOF。只杀本实例 GameDir 对应的 cmd.exe，不碰别的。
function Remove-StaleLauncherConsole {
    $stale = Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like ('*' + $GameDir + '*') -and $_.ProcessId -ne $PID }
    foreach ($p in $stale) {
        Add-Content -LiteralPath $LogPath -Value ('[' + (Get-Date).ToString('s') + '] REAP_STALE_LAUNCHER pid=' + $p.ProcessId) -Encoding UTF8 -ErrorAction SilentlyContinue
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($LauncherPid -gt 0) {
        $lp = Get-Process -Id $LauncherPid -ErrorAction SilentlyContinue
        if ($lp) {
            Add-Content -LiteralPath $LogPath -Value ('[' + (Get-Date).ToString('s') + '] REAP_LAUNCHER_PID pid=' + $LauncherPid) -Encoding UTF8 -ErrorAction SilentlyContinue
            Stop-Process -Id $LauncherPid -Force -ErrorAction SilentlyContinue
        }
    }
}

while ($true) {
    Start-Sleep -Seconds $CheckIntervalSeconds

    $alive = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like ('*' + $GameDir + '*') -and $_.CommandLine -notmatch 'mc-watchdog' }

    if (-not $alive) {
        Remove-StaleLauncherConsole
        $m = '[' + (Get-Date).ToString('s') + '] WATCHDOG_STOP game already closed; no kill needed; launcher console reaped.'
        Add-Content -LiteralPath $LogPath -Value $m -Encoding UTF8 -ErrorAction SilentlyContinue
        exit 0
    }

    if ((Get-Date) -ge $deadline) {
        foreach ($p in $alive) {
            $m = '[' + (Get-Date).ToString('s') + '] WATCHDOG_KILL pid=' + $p.ProcessId + ' — auto-stop deadline reached, closing to save power.'
            Add-Content -LiteralPath $LogPath -Value $m -Encoding UTF8 -ErrorAction SilentlyContinue
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Seconds 2
        Remove-StaleLauncherConsole
        $m = '[' + (Get-Date).ToString('s') + '] WATCHDOG_STOP game closed; launcher console reaped.'
        Add-Content -LiteralPath $LogPath -Value $m -Encoding UTF8 -ErrorAction SilentlyContinue
        exit 0
    }
}