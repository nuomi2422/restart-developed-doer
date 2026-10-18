# 通过 Numen MCP 调用工具/读事件（阶段1：tools/list + list_companions + 可选 tools/call）
param(
    [string]$MutationId,
    # Empty means protocol/connectivity smoke only. It must not be reported as
    # a successful candidate-tool invocation.
    [string]$ToolName = "",
    [string]$Companion = "",
    [ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE
)

$env:RDD_SELF_COMPILE_INSTANCE = if ($Instance) { $Instance } else { 'test' }
. "$PSScriptRoot\config.ps1"

$dir = Join-Path $global:RDD_SC.SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }
if (-not $global:RDD_SC.MCP_ENABLED) { throw "MCP is disabled for $($global:RDD_SC.SC_INSTANCE); enable it in the instance config and restart the game" }
if ([string]::IsNullOrWhiteSpace($global:RDD_SC.MCP_TOKEN)) { throw "MCP token missing for $($global:RDD_SC.SC_INSTANCE)" }

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

# 0. Wait for the game to actually OPEN the MCP port.
#
# Observed 2026-09-29: run-mutation.ps1 launches MC and drives MCP ~40s later.
# On a cold start the listener was still absent, so initialize/initialized/
# tools/list/list_companions all failed with "unable to connect to remote
# server" and the round was reported as MCP_FAILED.  That is a startup race,
# not a real protocol failure, so wait for the TCP port before judging.
$waitDeadline = (Get-Date).AddSeconds(240)
$portReady = $false
while ((Get-Date) -lt $waitDeadline) {
    $tcp = $null
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect($global:RDD_SC.MCP_HOST, $global:RDD_SC.MCP_PORT, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(2000) -and $tcp.Connected) { $portReady = $true }
    } catch {
        $portReady = $false
    } finally {
        if ($tcp) { try { $tcp.Close() } catch { } }
    }
    if ($portReady) { break }
    Write-Output "MCP 端口 $($global:RDD_SC.MCP_HOST):$($global:RDD_SC.MCP_PORT) 未就绪，重试中 ..."
    Start-Sleep -Seconds 5
}
if (-not $portReady) { throw "MCP port $($global:RDD_SC.MCP_HOST):$($global:RDD_SC.MCP_PORT) not open after 240s" }
Write-Output "MCP 端口就绪: $($global:RDD_SC.MCP_HOST):$($global:RDD_SC.MCP_PORT)"

# 1. initialize.  Do not treat a listed tool or a HTTP 200 as an invocation
# success: every JSON-RPC and tool-level error remains evidence of failure.
$errors = [System.Collections.Generic.List[string]]::new()
$init = Invoke-McpRpc "initialize" @{ protocolVersion = "2025-06-18"; capabilities = @{}; clientInfo = @{ name = "rdd-selfcompile"; version = "0.2" } }
if ($init.error) { $errors.Add("initialize: $($init.error.message)") }
$ready = Invoke-McpRpc "notifications/initialized" $null -Notification
if ($ready.error) { $errors.Add("initialized notification: $($ready.error.message)") }
# 2. tools/list
$tools = Invoke-McpRpc "tools/list" @{}
if ($tools.error) { $errors.Add("tools/list: $($tools.error.message)") }
# 3. list_companions
$companions = Invoke-McpRpc "tools/call" @{ name = "list_companions"; arguments = @{} }
if ($companions.error -or $companions.result.isError) {
    # 2026-09-29: the MCP port can be open while the WORLD is still loading, so
    # list_companions answers "timed out waiting for the action to finish".
    # That is a readiness race, not a protocol failure -> retry a few times.
    $detail = if ($companions.error) { $companions.error.message } else { "tool returned isError" }
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        if ($detail -notmatch "timed out") { break }
        Write-Output "world not ready (list_companions timed out), retry $attempt/6 ..."
        Start-Sleep -Seconds 10
        $companions = Invoke-McpRpc "tools/call" @{ name = "list_companions"; arguments = @{} }
        if (-not ($companions.error -or $companions.result.isError)) { $detail = ""; break }
        $detail = if ($companions.error) { $companions.error.message } else { "tool returned isError" }
    }
    if ($companions.error -or $companions.result.isError) {
        $errors.Add("list_companions: $detail")
    }
}

$found = $false
if ($ToolName -and $tools.result -and $tools.result.tools) {
    $found = @($tools.result.tools | Where-Object { $_.name -eq $ToolName }).Count -gt 0
}

$callResult = $null
if ($ToolName) {
    if (-not $found) {
        $errors.Add("requested tool not listed: $ToolName")
    } elseif ([string]::IsNullOrWhiteSpace($Companion)) {
        # All candidate validation calls are companion-scoped.  Refuse to make
        # an ambiguous call rather than allowing a tool error to look healthy.
        $errors.Add("companion is required for requested tool: $ToolName")
    } else {
        $call = Invoke-McpRpc "tools/call" @{ name = $ToolName; arguments = @{ companion = $Companion } }
        $callResult = $call
        if ($call.error -or $call.result.isError) {
            $detail = if ($call.error) { $call.error.message } else { "tool returned isError" }
            $errors.Add("${ToolName}: $detail")
        }
    }
}

$connected = $errors.Count -eq 0
$driven = $connected -and -not [string]::IsNullOrWhiteSpace($ToolName)
$log = @{
    success = $connected
    stage = "mcp-drive"
    instance = $global:RDD_SC.SC_INSTANCE
    mcpPort = $global:RDD_SC.MCP_PORT
    tool = $ToolName
    toolFound = if ($ToolName) { $found } else { $null }
    toolsListed = @($tools.result.tools | Select-Object -First 5 | ForEach-Object { $_.name })
    companions = @($companions.result.content | ForEach-Object { $_.text })
    callResult = $callResult
    errors = @($errors)
    state = if ($driven) { "MCP_DRIVEN" } elseif ($connected) { "MCP_CONNECTED" } else { "MCP_FAILED" }
}
Write-ResultJson (Join-Path $dir "phase-mcp.json") $log
if ($driven) { Write-Output "MCP_DRIVEN" }
elseif ($connected) { Write-Output "MCP_CONNECTED" }
else { Write-Output "MCP_FAILED: $($errors -join '; ')"; exit 1 }
exit 0
