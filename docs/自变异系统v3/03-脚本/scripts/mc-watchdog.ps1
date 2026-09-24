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
    [string]$LogPath = ''
)

if ([string]::IsNullOrWhiteSpace($Instance)) { $Instance = 'test' }
if ([string]::IsNullOrWhiteSpace($GameDir)) { Write-Output 'WATCHDOG_NO_GAMEDIR'; exit 1 }
if ([string]::IsNullOrWhiteSpace($LogPath)) { $LogPath = 'E:\restart developing doer\rdd-selfcompile\mutations\watchdog-' + $Instance + '.log' }

# 唯一的进程互斥标记：同一实例只允许一个看门狗存活。
$SelfMarker = 'rdd-watchdog-' + $Instance

# 已有本实例看门狗在跑？是则退出（避免重复）。
$existing = Get-CimInstance Win32_Process -Filter "Name='powershell.exe' OR Name='pwsh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*mc-watchdog.ps1*' -and $_.CommandLine -like ('*' + $SelfMarker + '*') -and $_.ProcessId -ne $PID }
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

while ($true) {
    Start-Sleep -Seconds $CheckIntervalSeconds

    $alive = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like ('*' + $GameDir + '*') -and $_.CommandLine -notmatch 'mc-watchdog' }

    if (-not $alive) {
        $m = '[' + (Get-Date).ToString('s') + '] WATCHDOG_STOP game already closed; no kill needed.'
        Add-Content -LiteralPath $LogPath -Value $m -Encoding UTF8 -ErrorAction SilentlyContinue
        exit 0
    }

    if ((Get-Date) -ge $deadline) {
        foreach ($p in $alive) {
            $m = '[' + (Get-Date).ToString('s') + '] WATCHDOG_KILL pid=' + $p.ProcessId + ' — auto-stop deadline reached, closing to save power.'
            Add-Content -LiteralPath $LogPath -Value $m -Encoding UTF8 -ErrorAction SilentlyContinue
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
        $m = '[' + (Get-Date).ToString('s') + '] WATCHDOG_STOP game closed.'
        Add-Content -LiteralPath $LogPath -Value $m -Encoding UTF8 -ErrorAction SilentlyContinue
        exit 0
    }
}