# codex-run.ps1 -- Special-Mode C-layer CLI bridge (for the orchestrator / O direct use)
# Same logic as codex-mcp-server.mjs: treats Codex (GPT-5.6-terra) as senior review / escalation.
# ASCII-only on purpose (PS5.1 misreads BOM-less UTF-8 Chinese).
#
# Usage:
#   .\codex-run.ps1 -Mode health
#   .\codex-run.ps1 -Mode ask -Prompt "review the design of XXX" [-Cwd <dir>] [-Sandbox read-only] [-Model name]
#   .\codex-run.ps1 -Mode review [-Uncommitted] [-Base main] [-Commit <sha>] [-Prompt "focus on boundaries"]
param(
    [ValidateSet('health','ask','review')][string]$Mode = 'ask',
    [string]$Prompt = '',
    [string]$Cwd = '',
    [ValidateSet('read-only','workspace-write','danger-full-access')][string]$Sandbox = 'read-only',
    [string]$Model = '',
    [switch]$Uncommitted,
    [string]$Base = '',
    [string]$Commit = '',
    [string]$CodexExe = ''
)

$ErrorActionPreference = 'Stop'
if (-not $Cwd) { $Cwd = if ($env:CODEX_CWD) { $env:CODEX_CWD } else { 'D:\rdd\workspace\minecraft-numen' } }
if (-not $CodexExe) { $CodexExe = if ($env:CODEX_EXE) { $env:CODEX_EXE } else { 'C:\Users\admin\AppData\Local\OpenAI\Codex\bin\d23520d1e41bfb24\codex.exe' } }
if (-not (Test-Path -LiteralPath $CodexExe)) { Write-Error "codex not found: $CodexExe"; exit 2 }

if ($Mode -eq 'health') {
    & $CodexExe --version
    exit $LASTEXITCODE
}

if ($Mode -eq 'review') {
    $argv = @('review')
    if ($Base) { $argv += @('--base', $Base) }
    if ($Commit) { $argv += @('--commit', $Commit) }
    if ($Uncommitted) { $argv += '--uncommitted' }
    if ($Prompt) { $argv += $Prompt }
    & $CodexExe @argv
    exit $LASTEXITCODE
}

# ask
if (-not $Prompt) { Write-Error '-Mode ask requires -Prompt'; exit 2 }
$tmp = Join-Path $env:TEMP ("codex-run-{0}.txt" -f ([guid]::NewGuid().ToString('N')))
$argv = @('exec', '--skip-git-repo-check', '--color', 'never', '--ephemeral',
    '-s', $Sandbox, '-o', $tmp)
if ($Model) { $argv += @('-m', $Model) }
if ($Cwd) { $argv += @('-C', $Cwd) }
$argv += '-'
$Prompt | & $CodexExe @argv
$code = $LASTEXITCODE
if (Test-Path -LiteralPath $tmp) {
    Get-Content -LiteralPath $tmp -Raw -Encoding UTF8
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}
exit $code
