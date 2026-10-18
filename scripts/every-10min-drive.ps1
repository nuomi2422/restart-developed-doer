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

# =====================================================================
# ★★ 总闸（2026-10-01 用户要求「把这个去掉不要再开了」）★★
#
#   这个脚本会每 600s 往 opencode 窗口用 SendKeys 注入一段文字，
#   **会插进用户正在打的字里**（用户消息被截断过两次）。
#   而且它会自我复活 —— 观察到一个现象：杀掉之后过一两分钟又出现新的，
#   父进程是 OpenCode.exe，但用户确认当时没有任何 AI 在跑。
#   靠「杀进程」和「改 autostart」都挡不住（已经都试过）。
#
#   所以加一道硬闸：**不给显式环境变量就立刻退出，一个字都不注入。**
#   任何东西（autostart / AI / 手滑 / 计划任务）直接跑它都没用。
#
#   ★ 要用的话必须显式开：
#       $env:RDD_ENABLE_SELFDRIVE = "1"; .\every-10min-drive.ps1 -Loop
#   用完记得关掉那个环境变量。
#
#   已同步：autostart-dev.ps1 改成默认不启动 watchdog（要开加 -WithWatchdog）。
# =====================================================================
if ($env:RDD_ENABLE_SELFDRIVE -ne "1") {
  $log0 = 'E:\restart developing doer\rdd-selfcompile\scripts\every-10min-drive.log'
  try {
    Add-Content -LiteralPath $log0 -Encoding UTF8 `
      ("[{0}] SKIPPED — 总闸关闭（未设 RDD_ENABLE_SELFDRIVE=1），不注入任何内容。" -f (Get-Date).ToString('s'))
  } catch {}
  exit 0
}

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
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
  private const byte VK_MENU = 0x12;
  private const uint KEYEVENTF_KEYUP = 0x0002;
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
  /// 绕过 Windows 的"前台锁"：后台进程直接 SetForegroundWindow 会被拒绝
  /// （这正是 MC 占焦点时 nudge 一直 skip 的根因）。把本线程的输入队列临时接到
  /// 当前前台窗口的线程上，再抢焦点就允许了，抢完立刻断开。
  public static bool ForceActivate(IntPtr target) {
    IntPtr fg = GetForegroundWindow();
    if (fg == target) return true;
    // 注意：不能用 C# 7 的 `out _` 丢弃符 —— PS 5.1 的 Add-Type 编译器不认识它，会整体编译失败。
    uint fgPid = 0;
    int fgThread = (fg == IntPtr.Zero) ? 0 : (int)GetWindowThreadProcessId(fg, out fgPid);
    int myThread = (int)GetCurrentThreadId();
    bool attached = false;
    bool ok = false;
    try {
      if (fgThread != 0 && fgThread != myThread) attached = AttachThreadInput((uint)myThread, (uint)fgThread, true);
      ok = SetForegroundWindow(target);
      if (!ok) {
        // 经典绕法：模拟一次 ALT 抬落，解除 Windows 的前台锁，再抢一次。
        // 实测：单靠 AttachThreadInput 会被前台锁顶回来（MC/浏览器占焦点时就是这样）。
        keybd_event(VK_MENU, 0, 0, UIntPtr.Zero);
        System.Threading.Thread.Sleep(60);
        keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        System.Threading.Thread.Sleep(60);
        ok = SetForegroundWindow(target);
      }
      return ok;
    } finally {
      if (attached) AttachThreadInput((uint)myThread, (uint)fgThread, false);
    }
  }
}
"@
  return [OcWin]::Find()
}

function Send-Nudge {
  param(
    [string]$Message = "[WATCHDOG/self-drive, NOT the user] continue working on current task, do not stop; if you hit an error log it and skip it.",
    [switch]$DryRun,
    [switch]$NoFocusRestore
  )
  Add-Type -AssemblyName System.Windows.Forms
  $h = Get-OpenCodeWindowHandle
  if ($h -eq [IntPtr]::Zero) {
    Write-Log "NUDGE fail: opencode window not found"
    return $false
  }
  # 记住当前焦点（通常是 MC），发完要还回去 —— 不能把玩家的游戏焦点抢走不放。
  $prev = [OcWin]::Fg()
  if ($prev -ne $h) {
    [OcWin]::Activate($h) | Out-Null
    Start-Sleep -Milliseconds 300
    if (([OcWin]::Fg()) -ne $h) {
      # 后台进程直接 SetForegroundWindow 会被 Windows 前台锁拒绝（MC 占焦点时就是这种）。
      # AttachThreadInput 把本线程输入队列接到前台线程上再抢 —— 这让它在 MC 占屏时也能送达。
      [OcWin]::ForceActivate($h) | Out-Null
      Start-Sleep -Milliseconds 350
    }
  }
  if (([OcWin]::Fg()) -ne $h) {
    Write-Log "NUDGE skip: foreground=$([OcWin]::Fg()) != target $h (前台锁抢不过来). Try again next round."
    return $false
  }
  Start-Sleep -Milliseconds 250
  if ($DryRun) {
    Write-Log "NUDGE dryrun target=$h fg=$([OcWin]::Fg()) (would send: $Message)"
    Restore-Focus $prev $h $NoFocusRestore
    return $true
  }
  [System.Windows.Forms.Clipboard]::SetText($Message)
  Start-Sleep -Milliseconds 200
  [System.Windows.Forms.SendKeys]::SendWait("^v")
  Start-Sleep -Milliseconds 150
  [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
  Write-Log ("NUDGE sent to window " + $h)
  Restore-Focus $prev $h $NoFocusRestore
  return $true
}

# 把焦点还给 nudge 之前的窗口（一般是 MC），好让玩家继续玩、也让 MC 继续收键盘。
function Restore-Focus {
  param([IntPtr]$Prev, [IntPtr]$Target, [switch]$NoRestore)
  try {
    if ($NoRestore) { return }
    if ($Prev -eq [IntPtr]::Zero -or $Prev -eq $Target) { return }
    [OcWin]::ForceActivate($Prev) | Out-Null
    Start-Sleep -Milliseconds 150
    Write-Log ("NUDGE focus restored to " + $Prev)
  } catch {
    Write-Log ("NUDGE focus restore failed: " + $_.Exception.Message)
  }
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
  # ② 请求投放目录 requests\req-*.json（只报 24h 内的新鲜件）
  #    2026-09-28 修：投放目录从没人清理，一个 9 天前已完成的旧请求（req-20260919123851.json，
  #    内容=检查 module-blueprint 技能，已确认存在）会让每轮 nudge 永久挂假"待处理"，误导后续每轮 AI。
  #    这里只过滤"上报口径"，不删文件：旧件留在盘上当证据，零风险。
  if (Test-Path $global:BP_DropDir) {
    $reqCutoff = (Get-Date).AddHours(-24)
    $reqs = @(Get-ChildItem $global:BP_DropDir -Filter "req-*.json" -File -ErrorAction SilentlyContinue |
      Where-Object { $_.LastWriteTime -gt $reqCutoff })
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

<#
  只读查当前任务上下文，供 nudge 带上。
  用户 2026-09-29 反馈：光"continue working"这种吹风没用 —— AI 回一段话就停了。
  所以 nudge 必须**自带当前任务**，它一眼就知道该干什么，不用先去查、也就没有借口只回话。
  用 numen-mcp.js（已带超时，游戏退出时快速失败，不会挂住）。
  注意：PowerShell 的块注释用的是尖括号井号标记，不是 Java 那套斜杠星号（写成后者会被当命令执行报错）。
#>
function Get-LiveTaskLine {
  try {
    $s = Join-Path $Base 'numen-mcp.js'
    if (-not (Test-Path -LiteralPath $s)) { return "" }
    $raw = & node $s call rdd_status rdd 2>$null | Out-String
    if (-not $raw -or -not $raw.Trim()) { return "" }
    $j = $raw.Trim() | ConvertFrom-Json
    $d = $j.data
    if (-not $d) { return "" }
    $cond = ""
    if ($d.condition) { $cond = "$($d.condition.asset_key) >= $($d.condition.minimum)" }
    return ("当前任务：primary=$($d.primary_status) subtask=$($d.subtask)($($d.subtask_status)) 目标条件=$cond")
  } catch { return "" }
}

<#
  构造 nudge 文本。

  ★ 2026-10-01 用户批准后改措辞（用户两次把这段贴出来要求改）：
    原来写的是「立刻用工具继续当前任务。不要只回话、不要只解释、不要反问我 ——
    纯文字回复等于这一轮白干。」
    删掉的是【对被执行者的操纵】，不是状态通知：
      · 「不要反问我」  → 压制 AI 判断「该不该问用户」
      · 「纯文字等于白干」→ 惩罚性话术，逼 AI 为了看起来在干活而调用工具
      · 「立刻」        → 制造紧迫感，跳过「这任务该不该继续」的判断
    保留 [NOT the user] 前缀 —— 那个是对的，防止被当成用户批准。
    详见 memory: issue/watchdog-injects-fake-user-turns-into-agent-context
    ⚠ scripts\ 是闭环真源，本次改动已获用户批准。
#>
function New-NudgeMessage {
  $head = "[WATCHDOG/self-drive, NOT the user] 检测到还有未完成的活（下面列了）。" +
          "如果你判断该继续就继续；如果判断不该继续（比如在等用户、或已经做完了），" +
          "直接说原因就行 —— 这里的判断权在你，这个脚本没有优先级。"
  $lines = @($head)
  $live = Get-LiveTaskLine
  if ($live) { $lines += $live }
  $pending = Get-PendingSummary
  if ($pending) { $lines += $pending }
  return ($lines -join "`n")
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
        $nudgeMsg = New-NudgeMessage
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
    $nudgeMsg = New-NudgeMessage
    Send-Nudge -Message $nudgeMsg -DryRun:$DryRun | Out-Null
  }
}
Write-Log "STOP every-10min-drive"