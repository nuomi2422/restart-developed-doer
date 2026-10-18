# mc-screenshot.ps1 -- VISION: capture the Minecraft window to PNG (O reads it via Read tool).
# ASCII-only (PS5.1 misreads BOM-less UTF-8 Chinese and swallows newlines).
# Usage: powershell -File mc-screenshot.ps1 [-OutPath <png>] [-TitleMatch <regex>] [-MinSize 300] [-NoRestore] [-DryRun]
# Picks the LARGEST visible matching window (skips minimized 159x27 sentinel windows), restores+foregrounds it,
# then CopyFromScreen (OpenGL-safe; PrintWindow would be black).
param(
  [string]$OutPath = '',
  [string]$TitleMatch = 'Minecraft|我的世界',
  [int]$MinSize = 300,
  [switch]$NoRestore,
  [switch]$DryRun
)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class WinCap {
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc cb, IntPtr l);
  public delegate bool EnumWindowsProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int m);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out WinCapRECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  public static IntPtr best = IntPtr.Zero;
  public static string bestTitle = "";
  public static int bestArea = 0;
  public static void FindBest(string pattern, int minSize) {
    best = IntPtr.Zero; bestTitle = ""; bestArea = 0;
    var rx = new System.Text.RegularExpressions.Regex(pattern);
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      var sb = new StringBuilder(512);
      GetWindowText(h, sb, 512);
      var title = sb.ToString();
      if (!rx.IsMatch(title)) return true;
      WinCapRECT r;
      GetWindowRect(h, out r);
      int w = r.Right - r.Left, hh = r.Bottom - r.Top;
      if (w < minSize || hh < minSize) return true;   // skip minimized sentinel (159x27)
      int area = w * hh;
      if (area > bestArea) { bestArea = area; best = h; bestTitle = title; }
      return true;
    }, IntPtr.Zero);
  }
}
[StructLayout(LayoutKind.Sequential)]
public struct WinCapRECT { public int Left; public int Top; public int Right; public int Bottom; }
"@
[WinCap]::FindBest($TitleMatch, $MinSize)
$h = [WinCap]::best
if ($h -eq [IntPtr]::Zero) { Write-Output 'NO_WINDOW'; exit 2 }
$r = New-Object WinCapRECT
[WinCap]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.Right - $r.Left
$hh = $r.Bottom - $r.Top
Write-Output ("WINDOW '" + [WinCap]::bestTitle + "' " + $w + "x" + $hh + " @ " + $r.Left + "," + $r.Top)
if ($DryRun) { exit 0 }
if (-not $NoRestore) {
  [WinCap]::ShowWindow($h, 9) | Out-Null   # SW_RESTORE
  [WinCap]::SetForegroundWindow($h) | Out-Null
  Start-Sleep -Milliseconds 900
  [WinCap]::GetWindowRect($h, [ref]$r) | Out-Null
  $w = $r.Right - $r.Left; $hh = $r.Bottom - $r.Top
}
$bmp = New-Object System.Drawing.Bitmap $w, $hh
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size($w, $hh)))
if (-not $OutPath) { $OutPath = Join-Path $env:TEMP ('mc-shot-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.png') }
$bmp.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output $OutPath
