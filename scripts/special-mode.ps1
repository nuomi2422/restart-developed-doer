# special-mode.ps1 -- Special Engineering Loop orchestrator (v3.1)
# Roles: O = OpenCode free AI (this agent) | W = web models GLM/Qwen/Kimi (playwright) | C = Codex gpt-5.6-terra (MCP)
# ASCII-only on purpose: PS5.1 misreads BOM-less UTF-8 Chinese. Chinese lives in template .md files.
#
# Usage:
#   .\special-mode.ps1 -Command new    -Module <name>
#   .\special-mode.ps1 -Command status
#   .\special-mode.ps1 -Command set    -Module <name> -State <STATE>
#   .\special-mode.ps1 -Command askall -Module <name> -Question "..."      # W layer: ask GLM+Qwen+Kimi
#   .\special-mode.ps1 -Command ask    -Module <name> -Platform glm -Question "..."
#   .\special-mode.ps1 -Command synth  -Module <name> -Instruction "..."   # W layer: GLM synthesizes
#   .\special-mode.ps1 -Command review -Module <name> [-Uncommitted] [-Base main] [-Commit sha]
#   .\special-mode.ps1 -Command next   -Module <name>
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('new', 'status', 'set', 'askall', 'ask', 'synth', 'review', 'next')]
  [string]$Command,
  [string]$Module = '',
  [string]$Question = '',
  [string]$Instruction = '',
  [ValidateSet('glm', 'qwen', 'kimi')][string]$Platform = 'glm',
  [string]$State = '',
  [switch]$Uncommitted,
  [string]$Base = '',
  [string]$Commit = '',
  [string]$Root = ''
)

$ErrorActionPreference = 'Stop'
$SC_ROOT = 'E:\restart developing doer\rdd-selfcompile'
# NOTE: PS5.1 reads this BOM-less UTF-8 script with the ANSI code page, so a
# literal Chinese path here would be mis-decoded (modules created under a
# mojibake dir). Keep the default root ASCII-only by decoding it from Base64
# at runtime. Root = 'E:\新建文件夹\rdd-docs\rdd架构\特殊模式'. Override with -Root or
# env SPECIAL_MODE_ROOT (ASCII writable path) when needed.
if (-not $Root) {
  if ($env:SPECIAL_MODE_ROOT) { $Root = $env:SPECIAL_MODE_ROOT }
  else { $Root = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('RTpc5paw5bu65paH5Lu25aS5XHJkZOaetuaehFznibnmrormqKHlvI8=')) }
}
$Templates = Join-Path $SC_ROOT 'templates\special-mode'
$WBrain = Join-Path $SC_ROOT 'scripts\w-brain-drive.js'
$CodexRun = Join-Path $SC_ROOT 'scripts\codex-run.ps1'
$Node = 'D:\nodejs\node.exe'

$VALID_STATES = @('INTENT', 'REQ_DRAFT', 'W_SURVEY', 'W_SYNTH', 'O_GLM_NEGOTIATE', 'REQ_FINAL',
  'IMPL', 'UNIT_TEST', 'W_REVIEW', 'MC_TEST', 'VERDICT', 'C_REVIEW', 'ARCH_STRESS', 'DONE')

$NEXT_HINT = @{
  'INTENT'          = 'O writes 02-req-draft.md, then set REQ_DRAFT'
  'REQ_DRAFT'       = 'run: askall (W layer) -> set W_SURVEY'
  'W_SURVEY'        = 'run: synth (GLM) -> set W_SYNTH'
  'W_SYNTH'         = 'O<->GLM negotiate 1-3 rounds; write 06-req-final.md; set O_GLM_NEGOTIATE'
  'O_GLM_NEGOTIATE' = 'finalize 06-req-final.md -> set REQ_FINAL (architecture items await human approval)'
  'REQ_FINAL'       = 'O implements code -> set IMPL'
  'IMPL'            = 'unit/boundary tests -> set UNIT_TEST'
  'UNIT_TEST'       = 'W review of code (ask/synth) -> set W_REVIEW'
  'W_REVIEW'        = 'run MC test in test save (creative) + scripts -> set MC_TEST'
  'MC_TEST'         = 'write verdict -> set VERDICT'
  'VERDICT'         = 'next round; every 3-4 areas/rounds run C review -> set C_REVIEW'
  'C_REVIEW'        = 'if Issue -> back to O+GLM; if clear -> impl next area / set REQ_FINAL'
  'ARCH_STRESS'     = 'after most modules: MC stress 1-2h -> record -> supplement scripts/requirements -> loop'
  'DONE'            = '(complete)'
}

function Write-Utf8($path, $text) {
  $dir = Split-Path -Parent $path
  if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
  [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
}
function ModuleDir { Join-Path $Root $Module }
function StatePath { Join-Path (ModuleDir) '_state.json' }
function LogPath { Join-Path (ModuleDir) '_log.md' }
function TS { Get-Date -Format 'yyyyMMdd-HHmmss' }
function LogLine($msg) { Add-Content -LiteralPath (LogPath) -Value ("{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) -Encoding UTF8 }
function ReadState { if (Test-Path -LiteralPath (StatePath)) { Get-Content -LiteralPath (StatePath) -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null } }
function WriteState($obj) { Write-Utf8 (StatePath) (($obj | ConvertTo-Json -Depth 6) + "`n") }
function SaveJson($subdir, $name, $content) {
  $p = Join-Path (ModuleDir) $subdir
  if (-not (Test-Path -LiteralPath $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
  $f = Join-Path $p ($name + '-' + (TS) + '.json')
  Write-Utf8 $f $content
  return $f
}

if ($Command -eq 'status') {
  if (-not (Test-Path -LiteralPath $Root)) { Write-Output "no modules yet: $Root"; exit 0 }
  Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName '_state.json') } | ForEach-Object {
    $st = $null
    $sf = Join-Path $_.FullName '_state.json'
    if (Test-Path -LiteralPath $sf) { try { $st = Get-Content -LiteralPath $sf -Raw -Encoding UTF8 | ConvertFrom-Json } catch {} }
    $state = if ($st) { $st.state } else { '?' }
    $round = if ($st) { $st.round } else { '?' }
    Write-Output ("{0,-24} {1,-16} round={2}" -f $_.Name, $state, $round)
  }
  exit 0
}

if (-not $Module) { Write-Error "Command '$Command' requires -Module"; exit 2 }
$mdir = ModuleDir

if ($Command -eq 'new') {
  if (Test-Path -LiteralPath (StatePath)) { Write-Error "module already exists: $mdir"; exit 1 }
  New-Item -ItemType Directory -Path $mdir -Force | Out-Null
  foreach ($d in @('03-w-survey', '04-w-synth', '07-impl', '08-tests', '10-mc-test')) {
    New-Item -ItemType Directory -Path (Join-Path $mdir $d) -Force | Out-Null
  }
  Get-ChildItem -LiteralPath $Templates -Filter '*.md' -ErrorAction SilentlyContinue | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $mdir $_.Name) -Force
  }
  Write-Utf8 (LogPath) ("# Special-Mode log: $Module`n`n")
  $st = [ordered]@{ module = $Module; state = 'INTENT'; round = 0; waived = @(); createdAt = (Get-Date).ToString('o'); updatedAt = (Get-Date).ToString('o') }
  WriteState $st
  LogLine "NEW module created"
  Write-Output "created: $mdir"
  Write-Output "next: $($NEXT_HINT['INTENT'])"
  exit 0
}

if (-not (Test-Path -LiteralPath (StatePath))) { Write-Error "module not found: $Module (StatePath=$StatePath)"; exit 1 }
$cur = ReadState
if ($null -eq $cur) { Write-Error "cannot parse state file: $StatePath"; exit 1 }

if ($Command -eq 'set') {
  if ($VALID_STATES -notcontains $State) { Write-Error "invalid state '$State'. valid: $($VALID_STATES -join ', ')"; exit 2 }
  $cur.state = $State
  $cur.updatedAt = (Get-Date).ToString('o')
  if ($State -eq 'REQ_FINAL') { $cur.round = [int]$cur.round + 1 }
  WriteState $cur
  LogLine "STATE -> $State"
  Write-Output "state set: $State"
  Write-Output "next: $($NEXT_HINT[$State])"
  exit 0
}

if ($Command -eq 'askall') {
  if (-not $Question) { Write-Error 'askall requires -Question'; exit 2 }
  if (-not $env:W_TIMEOUT_MS) { $env:W_TIMEOUT_MS = '240000' }
  if (-not $env:W_PER_PLATFORM_MS) { $env:W_PER_PLATFORM_MS = '180000' }
  $raw = & $Node $WBrain askall $Question | Out-String
  $f = SaveJson '03-w-survey' 'askall' $raw
  LogLine "W askall saved $f"
  Write-Output $raw
  exit 0
}

if ($Command -eq 'ask') {
  if (-not $Question) { Write-Error 'ask requires -Question'; exit 2 }
  if (-not $env:W_TIMEOUT_MS) { $env:W_TIMEOUT_MS = '240000' }
  $raw = & $Node $WBrain ask $Platform $Question | Out-String
  $f = SaveJson '03-w-survey' $Platform $raw
  LogLine "W ask $Platform saved $f"
  Write-Output $raw
  exit 0
}

if ($Command -eq 'synth') {
  if (-not $Instruction) { Write-Error 'synth requires -Instruction'; exit 2 }
  if (-not $env:W_TIMEOUT_MS) { $env:W_TIMEOUT_MS = '400000' }
  $raw = & $Node $WBrain synth $Instruction | Out-String
  $f = SaveJson '04-w-synth' 'glm-synth' $raw
  LogLine "W synth saved $f"
  Write-Output $raw
  exit 0
}

if ($Command -eq 'review') {
  $args = @('-Mode', 'review')
  if ($Uncommitted) { $args += '-Uncommitted' }
  if ($Base) { $args += @('-Base', $Base) }
  if ($Commit) { $args += @('-Commit', $Commit) }
  $raw = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $CodexRun @args 2>&1 | Out-String
  $f = SaveJson '09-w-review' 'C-review' $raw
  LogLine "C review saved $f"
  Write-Output $raw
  exit 0
}

if ($Command -eq 'next') {
  Write-Output ("module={0} state={1} round={2}" -f $Module, $cur.state, $cur.round)
  Write-Output ("next: {0}" -f $NEXT_HINT[$cur.state])
  exit 0
}
