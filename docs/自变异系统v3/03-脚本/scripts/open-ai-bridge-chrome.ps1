# ============================================
# open-ai-bridge-chrome.ps1
# 启动「AI Bridge 专用 Chrome」：独立 profile，隔离于日常 Chrome
# 用途：1) 手动登录一次新注册的 ChatGPT 邮箱账号（非 Google）
#       2) 之后作为 ai-bridge 扩展驱动目标的常驻窗口
# 为什么需要独立 profile：日常 Chrome 登录的是 Google 关联的旧 GPT 账号，
#   混在一起会污染；专用 profile 保证「邮箱直登账号」干�干净，与 Google 风控隔离。
# ============================================

param(
  [string]$Chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe",
  [string]$ProfileDir = "C:\Users\lenovo\.config\ai-bridge\chrome-profile",
  [string]$Url = "https://chatgpt.com/",
  [switch]$KeepAlive
)

if (-not (Test-Path $Chrome)) {
  Write-Error "找不到 Chrome: $Chrome"
  exit 1
}
if (-not (Test-Path $ProfileDir)) {
  New-Item -ItemType Directory -Path $ProfileDir -Force | Out-Null
}

# 关键：不加任何自动化参数（不加 --remote-debugging-port / --headless），
# 就是普通人类手动登录窗口；风控墙只拦带自动化指纹的浏览器。
# 注意：ProfileDir 路径在本机无空格，不需要引号；加了引号会被 PowerShell 拆坏导致 Chrome 并回主实例。
$args = @("--user-data-dir=$ProfileDir", $Url)

if ($KeepAlive) {
  Start-Process -FilePath $Chrome -ArgumentList $args
  Write-Output "已启动 AI Bridge 专用 Chrome (独立 profile): $ProfileDir"
  Write-Output "请手动登录新注册的 ChatGPT 邮箱账号。登录完成并进入对话页后，本窗口保持开着即可。"
} else {
  & $Chrome $args
}