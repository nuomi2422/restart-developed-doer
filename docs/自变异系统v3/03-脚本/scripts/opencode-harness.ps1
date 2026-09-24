[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$Task,
  [string]$Repo = 'E:\restart developing doer\minecraft-numen',
  [ValidateSet('readonly','implement')][string]$Mode = 'readonly',
  [string]$Model = 'opencode/big-pickle',
  [string]$Output = ''
)
$ErrorActionPreference = 'Stop'
$cli = $null
$candidates = @('D:\nodejs\cache\_npx\e2094862b59aac7b\node_modules\.bin\opencode.cmd')
foreach ($name in @('opencode.cmd', 'opencode')) {
  $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($command -and $command.Source) { $candidates += $command.Source }
}
foreach ($candidate in $candidates) {
  if (Test-Path -LiteralPath $candidate) { $cli = $candidate; break }
}
if (-not $cli) { throw 'OpenCode CLI not found. Install OpenCode or add opencode.cmd to PATH.' }
if (-not (Test-Path -LiteralPath (Join-Path $Repo '.git'))) { throw "Repository is not a git worktree: $Repo" }
$guard = if ($Mode -eq 'readonly') {
  'Read-only task. Do not modify files, do not commit, do not push. Report commands and evidence only.'
} else {
  'Implement only the requested task. Do not commit or push. Report changed files, tests, and remaining risks.'
}
$prompt = "$guard RDD commander context: work only inside $Repo. Execute this task now without asking for clarification: $Task Return a concise completion report with commands run, evidence, changed files (if any), and remaining risks."
$cliArgs = @('run','--pure','--dir',$Repo,'--format','json','--model',$Model,$prompt)
$started = (Get-Date).ToUniversalTime().ToString('o')
$raw = & $cli @cliArgs 2>&1 | Out-String
$result = [ordered]@{
  harness = 'opencode'
  mode = $Mode
  model = $Model
  repo = $Repo
  started_at = $started
  finished_at = (Get-Date).ToUniversalTime().ToString('o')
  exit_code = $LASTEXITCODE
  task = $Task
  output = $raw
}
$json = $result | ConvertTo-Json -Depth 8
if ($Output) {
  $parent = Split-Path -Parent $Output
  if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
  $json | Set-Content -LiteralPath $Output -Encoding UTF8
}
$json
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
