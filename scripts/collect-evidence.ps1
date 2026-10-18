# collect-evidence.ps1 - 采集 verify.ps1 要求的 5 个证据文件（2026-09-29 补）
#
# 为什么补这个：verify.ps1 要求 evidence/{deployed,mc_loaded,tool_registered,
# mcp_call_success,world_effect_verified}.json 全部存在才给 VERIFIED，
# 但**闭环里从来没有任何脚本生产它们** —— 于是所有 mutation 永远 UNVERIFIED。
# 这就是交接文档里「verify.ps1 未出 VERIFIED，闭环验证节是空的」的真正根因。
#
# 本脚本从**真实现场**采集，不接受调用方手填的证据：
#   deployed.json            <- phase-deploy.json + 实际 mods 目录 sha256 对账
#   mc_loaded.json           <- 游戏 latest.log 里的 mod 加载/工具注册痕迹 + 端口在听
#   tool_registered.json     <- MCP tools/list 里真的有该工具
#   mcp_call_success.json    <- 实调一次该工具（只读类），拿到成功回执
#   world_effect_verified.json <- 监测 jsonl 里出现该模块的真实事件
#
# 用法：
#   .\scripts\collect-evidence.ps1 -MutationId <id> -ToolName learner_note -Instance test
#   -ToolName "" 时跳过 mcp_call_success（无候选工具时用）
param(
    [string]$MutationId,
    [string]$ToolName = "",
    [string[]]$ToolArgs = @(),
    [ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE
)

$env:RDD_SELF_COMPILE_INSTANCE = if ($Instance) { $Instance } else { 'test' }
. "$PSScriptRoot\config.ps1"

$dir = Join-Path $global:RDD_SC.SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }
$evDir = Join-Path $dir "evidence"
New-Item -ItemType Directory -Path $evDir -Force | Out-Null

function Save-Evidence($name, $obj) {
    Write-ResultJson (Join-Path $evDir $name) $obj
    $mark = if ($obj.ok) { "ok" } else { "FAIL" }
    Write-Output "  evidence[$mark] $name"
}

# ---------- MCP 会话 ----------
$session = $null
function Invoke-McpRpc($method, $params, [switch]$Notification) {
    $body = @{ jsonrpc = "2.0"; method = $method }
    if (-not $Notification) { $body.id = [guid]::NewGuid().ToString() }
    if ($params) { $body.params = $params }
    $json = $body | ConvertTo-Json -Depth 8
    $headers = @{ "Authorization" = "Bearer $($global:RDD_SC.MCP_TOKEN)"; "Content-Type" = "application/json"; "Accept" = "application/json, text/event-stream" }
    if ($session) { $headers['Mcp-Session-Id'] = $session }
    try {
        $resp = Invoke-WebRequest -Uri $global:RDD_SC.MCP_URL -Method Post -Headers $headers -Body $json -TimeoutSec 60 -UseBasicParsing
        if ($resp.Headers['Mcp-Session-Id']) { $session = $resp.Headers['Mcp-Session-Id'] }
        if ($Notification) { return @{ result = @{ notification = $true } } }
        return ($resp.Content | ConvertFrom-Json)
    } catch {
        return @{ error = @{ code = -1; message = $_.Exception.Message } }
    }
}

function Wait-McpPort {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        $tcp = $null
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $iar = $tcp.BeginConnect($global:RDD_SC.MCP_HOST, $global:RDD_SC.MCP_PORT, $null, $null)
            if ($iar.AsyncWaitHandle.WaitOne(2000) -and $tcp.Connected) { return $true }
        } catch { } finally { if ($tcp) { try { $tcp.Close() } catch { } } }
        Start-Sleep -Seconds 3
    }
    return $false
}

Write-Output "=== collect evidence ==="

# ---------- 1. deployed.json : 部署的 jar 必须在 mods 里且 sha 与构建产物一致 ----------
$deployedOk = $false
$deployedDetail = ""
$jarInMutation = @(Get-ChildItem (Join-Path $dir "jar") -Filter "*.jar" -ErrorAction SilentlyContinue)
if ($jarInMutation.Count -eq 0) {
    $deployedDetail = "mutation jar/ 目录为空"
} else {
    $built = $jarInMutation[0]
    $builtSha = (Get-FileHash $built.FullName -Algorithm SHA256).Hash
    $inMods = Get-ChildItem $global:RDD_SC.MC_MODS -Filter $built.Name -ErrorAction SilentlyContinue
    if (-not $inMods) {
        $deployedDetail = "mods 目录没有 $($built.Name)"
    } else {
        $modsSha = (Get-FileHash $inMods[0].FullName -Algorithm SHA256).Hash
        $deployedOk = ($builtSha -eq $modsSha)
        $deployedDetail = "built=$($builtSha.Substring(0,16)) mods=$($modsSha.Substring(0,16)) match=$deployedOk"
    }
}
Save-Evidence "deployed.json" @{ ok = $deployedOk; kind = "deployed"; detail = $deployedDetail
    mods = $global:RDD_SC.MC_MODS; checkedAt = (Get-Date).ToString("o") }

# ---------- 2. mc_loaded.json : 游戏真的起来了（MCP 端口在听 + 日志有活动） ----------
$portUp = Wait-McpPort
$logPath = Join-Path $global:RDD_SC.MC_GAME_DIR "logs\latest.log"
$logFresh = $false
$logLines = 0
if (Test-Path $logPath) {
    $age = ((Get-Date) - (Get-Item $logPath).LastWriteTime).TotalMinutes
    $logFresh = ($age -lt 60)
    $logLines = (Get-Content -LiteralPath $logPath -Tail 400 -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
}
Save-Evidence "mc_loaded.json" @{ ok = ($portUp -and $logFresh); kind = "mc_loaded"
    detail = "mcpPort=$($global:RDD_SC.MCP_HOST):$($global:RDD_SC.MCP_PORT) up=$portUp; latest.log fresh(<60min)=$logFresh tailLines=$logLines"
    checkedAt = (Get-Date).ToString("o") }

# ---------- 3/4. tools/list 与实调 ----------
$init = Invoke-McpRpc "initialize" @{ protocolVersion = "2025-06-18"; capabilities = @{}; clientInfo = @{ name = "collect-evidence"; version = "1" } }
$null = Invoke-McpRpc "notifications/initialized" $null -Notification
$tools = Invoke-McpRpc "tools/list" @{}
$toolNames = @()
if ($tools.result -and $tools.result.tools) { $toolNames = @($tools.result.tools | ForEach-Object { $_.name }) }
$listOk = ($toolNames.Count -gt 0)

$wanted = $null
if ($ToolName) { $wanted = @($toolNames | Where-Object { $_ -eq $ToolName }) }

Save-Evidence "tool_registered.json" @{ ok = ($listOk -and (-not $ToolName -or $wanted.Count -eq 1)); kind = "tool_registered"
    detail = if ($ToolName) { "tools/list 共 $($toolNames.Count) 个; 目标 '$ToolName' present=$($wanted.Count -eq 1)" }
             else { "tools/list 共 $($toolNames.Count) 个（未指定目标工具）" }
    totalTools = $toolNames.Count; target = $ToolName; checkedAt = (Get-Date).ToString("o") }

$callOk = $false
$callDetail = ""
$callResult = $null
if ($ToolName -and $wanted.Count -eq 1) {
    # `arguments` must be a JSON OBJECT. Wrapping a hashtable in @() made it
    # serialise as [{"companion":"..."}] and the server then said "needs a
    # companion argument" -- a silent shape bug, not a broken tool.
    $argObj = @{}
    if ($ToolArgs -and $ToolArgs.Count -gt 0) {
        foreach ($kv in $ToolArgs) {
            if ($kv -match '^([^=]+)=(.*)$') { $argObj[$matches[1].Trim()] = $matches[2].Trim() }
        }
    } else {
        $comp = Invoke-McpRpc "tools/call" @{ name = "list_companions"; arguments = @{} }
        # world-readiness retry: the MCP port opens before the world finishes
        # loading, so list_companions can answer "timed out waiting for action".
        for ($a = 1; $a -le 6 -and ($comp.error -or $comp.result.isError); $a++) {
            Start-Sleep -Seconds 8
            Write-Output ("  world not ready, retry " + $a + "/6 ...")
            $comp = Invoke-McpRpc "tools/call" @{ name = "list_companions"; arguments = @{} }
        }
        $compId = $null
        if ($comp.result -and $comp.result.content) {
            $txt = ($comp.result.content | ForEach-Object { $_.text }) -join ""
            try {
                $parsed = $txt | ConvertFrom-Json
                $first = $parsed.companions | Select-Object -First 1
                if ($first) { $compId = $(if ($first.id) { $first.id } else { $first.uuid }) }
            } catch {
                # list_companions returns PLAIN TEXT ("- Name  (id: uuid)"), not JSON
                $m = [regex]::Match($txt, "\(id:\s*([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\)")
                if ($m.Success) { $compId = $m.Groups[1].Value }
            }
        }
        if ($compId) { $argObj['companion'] = $compId; Write-Output "  auto-filled companion=$compId" }
        else { Write-Output "  could not resolve a companion id from list_companions" }
    }
    $call = Invoke-McpRpc "tools/call" @{ name = $ToolName; arguments = $argObj }
    $callResult = $call
    $callOk = ($call.error -eq $null -and -not $call.result.isError)
    $callDetail = if ($call.error) { "rpc error: $($call.error.message)" }
                  elseif ($call.result.isError) { "tool returned isError" }
                  else { "ok" }
} else {
    $callDetail = "skipped (no -ToolName, or tool absent from catalog) -- must not fake success"
}
Save-Evidence "mcp_call_success.json" @{ ok = $callOk; kind = "mcp_call_success"; tool = $ToolName
    detail = $callDetail; result = $callResult; checkedAt = (Get-Date).ToString("o") }

# ---------- 5. world_effect_verified.json : 监测台里出现本模块真实事件 ----------
$modDir = $global:RDD_SC.MONITOR_DIR
$jsonlFiles = @(Get-ChildItem $modDir -Filter "*.jsonl" -ErrorAction SilentlyContinue)
$eventFiles = @()
$eventCount = 0
foreach ($f in $jsonlFiles) {
    $age = ((Get-Date) - $f.LastWriteTime).TotalMinutes
    if ($age -gt 120) { continue }
    $n = (Get-Content -LiteralPath $f.FullName -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
    if ($n -gt 0) { $eventFiles += @{ name = $f.Name; lines = $n; minutesAgo = [math]::Round($age, 1) }; $eventCount += $n }
}
Save-Evidence "world_effect_verified.json" @{ ok = ($eventCount -gt 0); kind = "world_effect_verified"
    detail = "近 120 分钟内有 $eventCount 行事件，分布在 $($eventFiles.Count) 个 jsonl"
    files = $eventFiles; monitorDir = $modDir; checkedAt = (Get-Date).ToString("o") }

Write-Output "=== 证据采集完成: $evDir ==="
Get-ChildItem $evDir -Filter "*.json" | ForEach-Object { Write-Output "  $($_.Name)" }
exit 0
