# AI Bridge MCP launcher.
# The token used to be hardcoded on line 1 of this file and was committed to
# git. It is no longer stored here. Resolution order:
#   1. $env:AI_BRIDGE_MCP_TOKEN already set by the caller
#   2. ai-bridge.token sitting next to this script (gitignored)
# If neither is present we stop instead of starting an unauthenticated server.
$tokenFile = Join-Path $PSScriptRoot 'ai-bridge.token'

if ($env:AI_BRIDGE_MCP_TOKEN) {
    # provided by the caller, nothing to do
} elseif (Test-Path -LiteralPath $tokenFile) {
    $env:AI_BRIDGE_MCP_TOKEN = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
} else {
    Write-Error 'AI_BRIDGE_MCP_TOKEN is not set and ai-bridge.token was not found.'
    Write-Error 'Create ai-bridge.token next to this script (it is gitignored) or set the env var.'
    exit 1
}

if (-not $env:AI_BRIDGE_MCP_TOKEN) {
    Write-Error 'ai-bridge.token is empty.'
    exit 1
}

$env:AI_BRIDGE_LOG = "C:\Users\admin\AppData\Local\Temp\opencode\ai-bridge-server.log"
& "D:\rdd\tools\node\node.exe" "D:\rdd\workspace\rdd-selfcompile\ai-bridge\mcp-server\index.js"