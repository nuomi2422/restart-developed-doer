# mc-focus.ps1 — 把 Minecraft 窗口抢到前台（键鼠注入的前提）
#
# 为什么需要（2026-09-30 实测）：
#   OpenClaw 的命令能发出去、状态也能读回来（`getStatus` 有坐标、有背包），
#   但 `keyDown w` **人不动** —— 因为 MC 窗口没有焦点，键盘事件进了 GLFW 却被丢弃。
#   这是"命令跑完了但什么也没发生"的经典原因，看起来像 OpenClaw 坏了，其实是焦点。
#
# 为什么不用现成的 every-10min-drive.ps1 里那段：
#   那段 Find() 找的是**标题含 "opencode"** 的窗口（服务于 nudge），
#   这里要找 Minecraft 的窗口。抢焦点的那套（AttachThreadInput + ALT 抬落）
#   完全可复用，所以抽成共用模块，两边都用它 —— 焦点逻辑只留一份。
#
# 用法：
#   . mc-focus.ps1 ; Focus-MinecraftWindow          # 抢到前台并返回窗口标题
#   . mc-focus.ps1 ; Test-MinecraftFocused         # $true/$false

if (-not ("McFocus.McFocusWin" -as [type])) {
# 注意：必须用 -TypeDefinition（整段源码）而不是 -MemberDefinition，
# 因为下面带了自己的 namespace 声明；-MemberDefinition 会把 namespace 当成类名的一部分而编译失败。
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
namespace McFocus {
public class McFocusWin {
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int m);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool f);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  private const byte VK_MENU = 0x12;
  private const uint KEYEVENTF_KEYUP = 0x0002;

  public static System.Collections.Generic.List<string> Titles = new System.Collections.Generic.List<string>();

  // 列出所有可见窗口标题（诊断用：这个存档的游戏窗口到底叫什么）
  public static void ListTitles() {
    Titles.Clear();
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      System.Text.StringBuilder sb = new System.Text.StringBuilder(512);
      GetWindowText(h, sb, 512);
      if (sb.Length > 0) Titles.Add(sb.ToString());
      return true;
    }, IntPtr.Zero);
  }

  // 找 Minecraft 窗口：标题含关键字（默认 minecraft）
  public static IntPtr Find(string keyword) {
    string kw = (keyword == null || keyword.Length == 0) ? "minecraft" : keyword.ToLowerInvariant();
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      System.Text.StringBuilder sb = new System.Text.StringBuilder(512);
      GetWindowText(h, sb, 512);
      if (sb.ToString().ToLowerInvariant().Contains(kw)) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }

  public static string TitleOf(IntPtr h) {
    if (h == IntPtr.Zero) return "";
    System.Text.StringBuilder sb = new System.Text.StringBuilder(512);
    GetWindowText(h, sb, 512);
    return sb.ToString();
  }

  public static bool IsForeground(IntPtr h) { return h != IntPtr.Zero && GetForegroundWindow() == h; }

  // 绕过 Windows 前台锁：AttachThreadInput + ALT 抬落（实测单靠 SetForegroundWindow 会被顶回来）
  public static bool ForceActivate(IntPtr target) {
    if (target == IntPtr.Zero) return false;
    IntPtr fg = GetForegroundWindow();
    if (fg == target) return true;
    uint fgPid = 0;
    int fgThread = (fg == IntPtr.Zero) ? 0 : (int)GetWindowThreadProcessId(fg, out fgPid);
    int myThread = (int)GetCurrentThreadId();
    bool attached = false;
    bool ok = false;
    try {
      if (fgThread != 0 && fgThread != myThread) attached = AttachThreadInput((uint)myThread, (uint)fgThread, true);
      ok = SetForegroundWindow(target);
      if (!ok) {
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
}
"@
}

# 列出所有可见窗口标题（诊断：这个存档的游戏窗口叫什么）
function Get-MinecraftWindowTitles {
    [McFocus.McFocusWin]::ListTitles()
    return [McFocus.McFocusWin]::Titles
}

# 把 Minecraft 窗口抢到前台。返回窗口标题（没找到返回空串）
function Focus-MinecraftWindow {
    param([string]$Keyword = 'minecraft')
    $h = [McFocus.McFocusWin]::Find($Keyword)
    if ($h -eq [IntPtr]::Zero) { return '' }
    $ok = [McFocus.McFocusWin]::ForceActivate($h)
    Start-Sleep -Milliseconds 350     # 给窗口一点时间真正拿到焦点
    $title = [McFocus.McFocusWin]::TitleOf($h)
    if ($ok -and [McFocus.McFocusWin]::IsForeground($h)) {
        Write-Output "FOCUS_OK '$title'"
    } else {
        Write-Output "FOCUS_FAIL '$title' (SetForegroundWindow 被系统拒绝)"
    }
    return $title
}

# Minecraft 窗口现在是否在前台
function Test-MinecraftFocused {
    param([string]$Keyword = 'minecraft')
    $h = [McFocus.McFocusWin]::Find($Keyword)
    if ($h -eq [IntPtr]::Zero) { return $false }
    return [McFocus.McFocusWin]::IsForeground($h)
}