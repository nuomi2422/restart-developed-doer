# every-10min-drive.ps1 — 自编译 V3 十分钟驱动器
# 每个 AI（含免费模型集群）必看的调度器：每 600s 发一次请求，慢慢推进负债。
# 用途：离线自主模式下，周期性对免费/多 provider 模型发一次小请求，把大任务拆成每天一点。
#
# 工作流：
#   每 10 分钟 →
#     1) 读任务队列 selfcompile-queue.json（有未完成任务才动作，无任务仅记心跳）
#     2) 投递一次请求标记（.json 文件，由承接方-opencode/外脑/监听进程-消费）
#     3) 调用当前 provider 模型（free 列表轮换，429/失败自动换下一个）
#     4) 写心跳日志 drive-heartbeat.log
#
# 用法:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File every-10min-drive.ps1 -Loop

param(
  [switch]$Loop,
  [int]$IntervalSec = 600,
  [switch]$NoNudge,
  [switch]$DryRun,
  [switch]$RestartOnStall,
  [int]$StallLimit = 3,
  [string]$Base = "E:\restart developing doer\rdd-selfcompile\scripts"
)

function Get-OpenCodeWindowHandle {
  Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class OcWin {
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int m);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  public static IntPtr Find() {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      StringBuilder sb = new StringBuilder(512);
      GetWindowText(h, sb, 512);
      if (sb.ToString().ToLowerInvariant().Contains("opencode")) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
  public static IntPtr Fg() { return GetForegroundWindow(); }
  public static bool Activate(IntPtr h) { return SetForegroundWindow(h); }
}
"@
  return [OcWin]::Find()
}

function Send-Nudge {
  param(
    [string]$Message = "continue working on current task, do not stop; if you hit an error log it and skip it.",
    [switch]$DryRun
  )
  Add-Type -AssemblyName System.Windows.Forms
  $h = Get-OpenCodeWindowHandle
  if ($h -eq [IntPtr]::Zero) {
    Write-Log "NUDGE fail: opencode window not found"
    return $false
  }
  $sh = New-Object -ComObject WScript.Shell
  $tryForeground = [OcWin]::Fg()
  if ($tryForeground -ne $h) {
    [OcWin]::Activate($h) | Out-Null
    Start-Sleep -Milliseconds 400
    $tryForeground = [OcWin]::Fg()
  }
  if ($tryForeground -ne $h) {
    Write-Log "NUDGE skip: foreground=$tryForeground != target $h (another window owns focus, e.g. MC). Try again next round."
    return $false
  }
  Start-Sleep -Milliseconds 300
  if ($DryRun) {
    Write-Log "NUDGE dryrun target=$h fg=$tryForeground (would send: $Message)"
    return $true
  }
  [System.Windows.Forms.Clipboard]::SetText($Message)
  Start-Sleep -Milliseconds 200
  [System.Windows.Forms.SendKeys]::SendWait("^v")
  Start-Sleep -Milliseconds 150
  [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
  Write-Log ("NUDGE sent to window " + $h)
  return $true
}

$ErrorActionPreference = "Continue"

$global:BP_LogPath = Join-Path $Base "drive-heartbeat.log"
$global:BP_QueuePath = Join-Path $Base "selfcompile-queue.json"
$global:BP_DropDir = Join-Path $Base "requests"
New-Item -ItemType Directory -Path $global:BP_DropDir -Force | Out-Null

function Write-Log {
  param([string]$msg)
  $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
  Add-Content -Path $global:BP_LogPath -Value $line -Encoding UTF8
  Write-Output $line
}

function Read-Queue {
  if (-not (Test-Path $global:BP_QueuePath)) { return $null }
  try {
    $q = Get-Content $global:BP_QueuePath -Raw -Encoding UTF8 | ConvertFrom-Json
    return $q
  } catch { Write-Log "QUEUE parse fail: $_" ; return $null }
}

# 游戏内 selfcompile 请求落盘目录（每个 MC 实例一个；非终态 manifest = 待处理）。
$global:BP_MutationDirs = @(
  "E:\.minecraft\versions\The Best of Twilight Forest\config\numen\selfcompile\mutations",
  "E:\restart developing doer\rdd-selfcompile\instances\test\config\numen\selfcompile\mutations"
)

# 汇总“待处理自变异请求”，供 nudge 文本带上——让执行 AI 每轮都能注意到，不靠人工提醒。
function Get-PendingSummary {
  $parts = @()
  # ① 外部队列 selfcompile-queue.json
  if (Test-Path $global:BP_QueuePath) {
    try {
      $qq = Get-Content $global:BP_QueuePath -Raw -Encoding UTF8 | ConvertFrom-Json
      if ($qq.tasks) { $n = @($qq.tasks).Count; if ($n -gt 0) { $parts += "queue=$n" } }
    } catch { }
  }
  # ② 请求投放目录 requests\req-*.json
  if (Test-Path $global:BP_DropDir) {
    $reqs = @(Get-ChildItem $global:BP_DropDir -Filter "req-*.json" -File -ErrorAction SilentlyContinue)
    if ($reqs.Count -gt 0) { $parts += "requests=$($reqs.Count)" }
  }
  # ③ 游戏内 selfcompile mutations（非终态 = 待处理）
  $pend = @()
  foreach ($d in $global:BP_MutationDirs) {
    if (-not (Test-Path $d)) { continue }
    foreach ($m in (Get-ChildItem $d -Directory -ErrorAction SilentlyContinue)) {
      $mf = Join-Path $m.FullName "manifest.json"
      if (-not (Test-Path $mf)) { continue }
      try {
        $man = Get-Content $mf -Raw -Encoding UTF8 | ConvertFrom-Json
        $state = [string]$man.state
        if ($state -and ($state -notin @("VERIFIED", "FAILED", "ACTIVE", "PROMOTED"))) {
          # 只报近 24h 的，避免历史遗留目录长期刷屏
          $fresh = $true
          try { $c = [datetime]$man.createdAt; if ($c.ToUniversalTime() -lt (Get-Date).ToUniversalTime().AddHours(-24)) { $fresh = $false } } catch { }
          if ($fresh) { $pend += $man }
        }
      } catch { }
    }
  }
  if ($pend.Count -gt 0) {
    $latest = $pend | Sort-Object createdAt -Descending | Select-Object -First 1
    $req = [string]$latest.requirement
    if ($req.Length -gt 60) { $req = $req.Substring(0, 60) + "..." }
    $parts += "mutations=$($pend.Count)(latest $($latest.state): $req)"
  }
  if ($parts.Count -eq 0) { return "" }
  return "[自变异待处理] " + ($parts -join "; ")
}

function Do-One-Round {
  Write-Log "ROUND begin"
  $q = Read-Queue
  if ($null -eq $q) {
    Write-Log "ROUND queue empty(no file) - heartbeat only"
    return
  }
  $task = $null
  try {
    if ($q.tasks -and $q.tasks.Count -gt 0) { $task = $q.tasks[0].text }
  } catch { $task = $null }
  if ($null -ne $task -and $task.Length -gt 0) {
    $reqId = "req-{0:yyyyMMddHHmmss}" -f (Get-Date)
    $body = @{
      ts    = (Get-Date).ToUniversalTime().ToString("o")
      reqId = $reqId
      task  = $task
      source = "every-10min-drive"
    } | ConvertTo-Json -Depth 4
    $reqFile = Join-Path $global:BP_DropDir ($reqId + ".json")
    Set-Content -Path $reqFile -Value $body -Encoding UTF8
    Write-Log ("REQUEST dropped " + $reqFile)
  } else {
    Write-Log "ROUND queue no task - heartbeat only"
  }
}

Write-Log "START every-10min-drive pid=$PID interval=${IntervalSec}s loop=$Loop nudge=$(-not $NoNudge)"
$stallCount = 0
if ($Loop) {
  while ($true) {
    try { Do-One-Round } catch { Write-Log ("ROUND error: " + $_.Exception.Message) }
    try {
      if (-not $NoNudge) {
        $nudgeMsg = "continue working on current task, do not stop; if you hit an error log it and skip it."
        $pending = Get-PendingSummary
        if ($pending) { $nudgeMsg = $nudgeMsg + "`n" + $pending }
        if (Send-Nudge -Message $nudgeMsg -DryRun:$DryRun) {
          $stallCount = 0
        } else {
          $stallCount++
          Write-Log "NUDGE fail count=$stallCount"
          if ($RestartOnStall -and $stallCount -ge $StallLimit) {
            Write-Log "STALL limit reached ($StallLimit) - restarting OpenCode"
            Get-Process | Where-Object { $_.ProcessName -eq 'OpenCode' -and $_.MainWindowHandle -eq 0 } | Stop-Process -Force -ErrorAction SilentlyContinue
            Start-Process -FilePath "$env:LOCALAPPDATA\Programs\@opencode-aidesktop\OpenCode.exe" -ErrorAction SilentlyContinue
            $stallCount = 0
          }
        }
      }
    } catch { Write-Log ("NUDGE error: " + $_.Exception.Message) }
    Start-Sleep -Seconds $IntervalSec
  }
} else {
  Do-One-Round
  if (-not $NoNudge) {
    $nudgeMsg = "continue working on current task, do not stop; if you hit an error log it and skip it."
    $pending = Get-PendingSummary
    if ($pending) { $nudgeMsg = $nudgeMsg + "`n" + $pending }
    Send-Nudge -Message $nudgeMsg -DryRun:$DryRun | Out-Null
  }
}
Write-Log "STOP every-10min-drive"