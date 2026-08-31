# Self-Compile 单轮闭环公共配置
$Script:SC_ROOT      = "E:\restart developing doer\rdd-selfcompile"
$Script:SC_MUTATIONS = Join-Path $Script:SC_ROOT "mutations"
$Script:SC_REPORT    = Join-Path $Script:SC_ROOT "report"

# Numen 相关
$Script:NUMMEN_REPO = "E:\restart developing doer\minecraft-numen"
$Script:PLUGIN_MODULE = "plugins:selfcompile"
$Script:PLUGIN_JAR_GLOB = "plugins\selfcompile\build\libs\numen-plugin-selfcompile-*.jar"
$Script:MC_MODS = "E:\.minecraft\versions\The Best of Twilight Forest\mods"
$Script:MC_BAT = "E:\.minecraft\versions\The Best of Twilight Forest\shaderpacks\启动 The Best of Twilight Forest.bat"
$Script:MC_SAVE = "gpt"
$Script:GRADLE = "E:\restart developing doer\gradle-9.2.0\bin\gradle.bat"
$Script:JAVA_HOME = "E:\jdk21\jdk-21.0.11+10"
$Script:GRADLE_OPTS = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"

# MCP
$Script:MCP_HOST = "127.0.0.1"
$Script:MCP_PORT = 8765
$Script:MCP_TOKEN = "numen-O6fD3ozb25h9q-fwCveIcVJ2"
$Script:MCP_URL = "http://$($Script:MCP_HOST):$($Script:MCP_PORT)/mcp"

# 监测台
$Script:MONITOR_URL = "http://127.0.0.1:8776"
$Script:MONITOR_DIR = "E:\.minecraft\versions\The Best of Twilight Forest\config\numen\monitor"

function Write-ResultJson($path, $obj) {
    $parent = Split-Path -Parent $path
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $json = $obj | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText($path, "$json`n", [Text.UTF8Encoding]::new($false))
}
