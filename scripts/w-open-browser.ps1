# w-open-browser.ps1 -- launch the dedicated W-layer Edge with CDP (kept open for login/reuse)
# It opens GLM / DeepSeek only (2026-10-05: W pool cut down to these two).
# Log into the two sites by hand, then w-brain-drive.js reuses port 9223.
# Usage: powershell -File w-open-browser.ps1 [-Port 9223]
param(
  [int]$Port = 9223,
  [string]$Profile = "$env:USERPROFILE\.playwright-profile-w"
)
$ErrorActionPreference = 'Stop'
$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
if (-not (Test-Path -LiteralPath $edge)) { Write-Error "Edge not found: $edge"; exit 1 }
$urls = @('https://chatglm.cn/', 'https://chat.deepseek.com/')
$argv = @("--remote-debugging-port=$Port", "--user-data-dir=$Profile", '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--disable-gpu-compositing', '--new-window') + $urls
Start-Process -FilePath $edge -ArgumentList $argv
Write-Output "Edge started: port=$Port profile=$Profile"
Write-Output ("opened: " + ($urls -join ', '))
Write-Output "next: login the 2 sites, then run: node w-brain-drive.js probe"
