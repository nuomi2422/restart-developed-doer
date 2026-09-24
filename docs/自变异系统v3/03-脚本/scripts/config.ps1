# Self-Compile 公共配置。默认只操作隔离 test；gpt 必须显式指定。
# 子脚本通过环境变量 RDD_SELF_COMPILE_INSTANCE 继承同一实例选择。
# This file is dot-sourced by independent scripts.  `$Script:` is local to
# config.ps1, so callers used to receive null paths and could print a mutation
# id after a failed workspace creation.  Keep one explicit shared config object
# instead; it is overwritten on every invocation and never serialized.
$global:RDD_SC = [pscustomobject]@{
    SC_ROOT         = "E:\restart developing doer\rdd-selfcompile"
    SC_MUTATIONS    = "E:\restart developing doer\rdd-selfcompile\mutations"
    SC_REPORT       = "E:\restart developing doer\rdd-selfcompile\report"
    NUMMEN_REPO     = "E:\restart developing doer\minecraft-numen"
    PLUGIN_MODULE   = "plugins:selfcompile"
    PLUGIN_JAR_GLOB = "plugins\selfcompile\build\libs\numen-plugin-selfcompile-*.jar"
    GRADLE          = "E:\restart developing doer\gradle-9.2.0\bin\gradle.bat"
    JAVA_HOME       = "E:\jdk21\jdk-21.0.11+10"
    GRADLE_OPTS     = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"
}

function Select-RddSelfCompileInstance {
    param([ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE)
    if ([string]::IsNullOrWhiteSpace($Instance)) { $Instance = 'test' }
    $env:RDD_SELF_COMPILE_INSTANCE = $Instance
    $global:RDD_SC | Add-Member -NotePropertyName SC_INSTANCE -NotePropertyValue $Instance -Force

    if ($Instance -eq 'test') {
        $global:RDD_SC | Add-Member -NotePropertyName MC_GAME_DIR -NotePropertyValue "E:\restart developing doer\rdd-selfcompile\instances\test" -Force
        $global:RDD_SC | Add-Member -NotePropertyName MC_BAT -NotePropertyValue (Join-Path $global:RDD_SC.MC_GAME_DIR "launch-test.cmd") -Force
        $global:RDD_SC | Add-Member -NotePropertyName MC_SAVE -NotePropertyValue 'test' -Force
        $global:RDD_SC | Add-Member -NotePropertyName MONITOR_URL -NotePropertyValue $(if ($env:RDD_MONITOR_URL) { $env:RDD_MONITOR_URL } else { 'http://127.0.0.1:8777' }) -Force
    } else {
        # Production is opt-in only. This preserves the old gpt layout but does
        # not make it a default mutation target.
        $global:RDD_SC | Add-Member -NotePropertyName MC_GAME_DIR -NotePropertyValue "E:\.minecraft\versions\The Best of Twilight Forest" -Force
        # Avoid hardcoding the Chinese-named launcher bat. This file is BOM-less UTF-8
        # and PS5.1 misreads Chinese bytes; worse, a trailing Chinese char before a
        # line break can even SWALLOW the newline and comment out the next line.
        # Discover the bat via glob instead, keep this section ASCII-only.
        $gptBat = @(Get-ChildItem -LiteralPath (Join-Path $global:RDD_SC.MC_GAME_DIR 'shaderpacks') -Filter '*.bat' -ErrorAction Stop | Where-Object { $_.Name -notlike '*-test.bat' })[0].FullName
        $global:RDD_SC | Add-Member -NotePropertyName MC_BAT -NotePropertyValue $gptBat -Force
        $global:RDD_SC | Add-Member -NotePropertyName MC_SAVE -NotePropertyValue 'gpt' -Force
        $global:RDD_SC | Add-Member -NotePropertyName MONITOR_URL -NotePropertyValue $(if ($env:RDD_MONITOR_URL) { $env:RDD_MONITOR_URL } else { 'http://127.0.0.1:8776' }) -Force
    }
    $global:RDD_SC | Add-Member -NotePropertyName MC_MODS -NotePropertyValue (Join-Path $global:RDD_SC.MC_GAME_DIR 'mods') -Force
    $global:RDD_SC | Add-Member -NotePropertyName MONITOR_DIR -NotePropertyValue (Join-Path $global:RDD_SC.MC_GAME_DIR 'config\numen\monitor') -Force
    $global:RDD_SC | Add-Member -NotePropertyName MCP_CONFIG -NotePropertyValue (Join-Path $global:RDD_SC.MC_GAME_DIR 'config\numen\mcp_server.json') -Force
    if (-not (Test-Path -LiteralPath $global:RDD_SC.MCP_CONFIG)) { throw "MCP config missing for ${Instance}: $($global:RDD_SC.MCP_CONFIG)" }
    $mcp = Get-Content -LiteralPath $global:RDD_SC.MCP_CONFIG -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($mcp.host -notin @('127.0.0.1','localhost','::1') -or $mcp.port -lt 1 -or $mcp.port -gt 65535) { throw "Invalid local MCP address for $Instance" }
    $global:RDD_SC | Add-Member -NotePropertyName MCP_ENABLED -NotePropertyValue ([bool]$mcp.enabled) -Force
    $global:RDD_SC | Add-Member -NotePropertyName MCP_HOST -NotePropertyValue $mcp.host -Force
    $global:RDD_SC | Add-Member -NotePropertyName MCP_PORT -NotePropertyValue ([int]$mcp.port) -Force
    $global:RDD_SC | Add-Member -NotePropertyName MCP_TOKEN -NotePropertyValue $mcp.token -Force
    $hostForUrl = if ($global:RDD_SC.MCP_HOST -eq '::1') { '[::1]' } else { $global:RDD_SC.MCP_HOST }
    $global:RDD_SC | Add-Member -NotePropertyName MCP_URL -NotePropertyValue "http://${hostForUrl}:$($global:RDD_SC.MCP_PORT)/mcp" -Force
}

Select-RddSelfCompileInstance

function Write-ResultJson($path, $obj) {
    $parent = Split-Path -Parent $path
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $json = $obj | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText($path, "$json`n", [Text.UTF8Encoding]::new($false))
}
