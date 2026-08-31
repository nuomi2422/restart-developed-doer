# 通过 Numen MCP 调用工具/读事件（阶段1：tools/list + list_companions + 可选 tools/call）
param([string]$MutationId, [string]$ToolName = "selfcompile_status", [string]$Companion = "")

. "$PSScriptRoot\config.ps1"

$dir = Join-Path $Script:SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }

function Invoke-McpRpc($method, $params) {
    $body = @{ jsonrpc = "2.0"; id = [guid]::NewGuid().ToString(); method = $method }
    if ($params) { $body.params = $params }
    $json = $body | ConvertTo-Json -Depth 8
    $headers = @{ "Authorization" = "Bearer $($Script:MCP_TOKEN)"; "Content-Type" = "application/json" }
    try {
        $resp = Invoke-RestMethod -Uri $Script:MCP_URL -Method Post -Headers $headers -Body $json -TimeoutSec 60
        return $resp
    } catch {
        return @{ error = @{ code = -1; message = $_.Exception.Message } }
    }
}

# 1. initialize
$init = Invoke-McpRpc "initialize" @{ protocolVersion = "2025-06-18"; capabilities = @{}; clientInfo = @{ name = "rdd-selfcompile"; version = "0.1" } }
# 2. tools/list
$tools = Invoke-McpRpc "tools/list" @{}
# 3. list_companions
$companions = Invoke-McpRpc "tools/call" @{ name = "list_companions"; arguments = @{} }

$found = $false
if ($tools.result -and $tools.result.tools) {
    $found = @($tools.result.tools | Where-Object { $_.name -eq $ToolName }).Count -gt 0
}

$callResult = $null
if ($found) {
    $args = @{}
    if ($Companion) { $args.companion = $Companion }
    $call = Invoke-McpRpc "tools/call" @{ name = $ToolName; arguments = $args }
    $callResult = $call
}

$log = @{
    success = $found
    stage = "mcp-drive"
    tool = $ToolName
    toolFound = $found
    toolsListed = @($tools.result.tools | Select-Object -First 5 | ForEach-Object { $_.name })
    companions = @($companions.result.content | ForEach-Object { $_.text })
    callResult = $callResult
    state = if ($found) { "MCP_DRIVEN" } else { "MCP_TOOL_MISSING" }
}
Write-ResultJson (Join-Path $dir "phase-mcp.json") $log
if ($found) { Write-Output "MCP_OK" } else { Write-Output "MCP_TOOL_MISSING"; exit 1 }
exit 0
