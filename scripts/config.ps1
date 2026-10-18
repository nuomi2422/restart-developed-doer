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
    # 2026-10-18: proxy is opt-in now (host switched to direct outbound). Set
    # RDD_GRADLE_PROXY=<port> to inject the local proxy again; empty = direct.
    GRADLE_OPTS     = $(if ($env:RDD_GRADLE_PROXY) { "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$($env:RDD_GRADLE_PROXY) -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$($env:RDD_GRADLE_PROXY)" } else { "" })

    # ── 知识库位置（2026-10-01 新增）────────────────────────────────
    # 为什么放这里：文档原先散在 6+ 处硬编码，知识库一搬家（rdd架构 → rdd-docs\rdd架构）
    # 全部失配 —— loop-gate 读不到测试日志、stager ctx 指向不存在的目录。
    # 现在 PowerShell 侧一律从这里取；node 侧读 stager/core/stages.json 的 docsRoot。
    # 两边同源，改位置只改这两行。
    DOCS_ROOT    = "E:\新建文件夹\rdd-docs\rdd架构"
    DOCS_UNIFIED = "E:\新建文件夹\rdd-docs\rdd架构\自编译统一版"
    DOCS_OUT     = "E:\新建文件夹\rdd-docs\rdd存放"
}

function Select-RddSelfCompileInstance {
    param([ValidateSet('test','gpt')][string]$Instance = $env:RDD_SELF_COMPILE_INSTANCE)
    # 2026-09-30 用户指示：默认实例改为 **gpt**（原来默认 test，导致验证跑错存档、
    # 结论作废——2026-09-30 那次「进游戏 AI 没动」的验证就是这么错的）。
    # test 实例仍然显式可用（-Instance test），但**不再是不传参时的默认**。
    if ([string]::IsNullOrWhiteSpace($Instance)) { $Instance = 'gpt' }
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
        # 2026-10-01 用户指示：存档换成 live（新建的），gpt 存档保留但不再是默认。
        # ⚠️ 这里只是**脚本侧**记录；真正决定启动器进哪个存档的是该 bat 里
        #    --quickPlaySingleplayer "<存档名>"，两处必须一致（只改一处 = 脚本说 live、实际进 rdd）。
        $global:RDD_SC | Add-Member -NotePropertyName MC_SAVE -NotePropertyValue 'live' -Force
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

# ---------------------------------------------------------------------------
# Build-target selection (2026-09-29).
#
# Why this exists: AGENTS.md and the handoff doc both tell you to put
# "moduleOverride" in phase-build.json when you touch a module other than
# plugins:selfcompile.  But build-jar.ps1 used to hardcode PLUGIN_MODULE, so
# that instruction could never actually be obeyed -- the only way left was a
# hand-written gradle call, which permission.bash now denies.  This function
# makes the override real: every script resolves the gradle module path and the
# produced jar glob from ONE declared module name.
#
# Rules (ASCII on purpose -- this file is BOM-less UTF-8 and PS 5.1 mangles
# non-ASCII bytes here):
#   plugins:<name> -> plugins\<name>\build\libs\numen-plugin-<name>-*.jar
#   <group>:<name> -> <group>\<name>\build\libs\numen-<name>-*.jar
# ---------------------------------------------------------------------------
function Set-RddSelfCompileModule {
    param([string]$Module)

    if ([string]::IsNullOrWhiteSpace($Module)) { $Module = $env:RDD_SELF_COMPILE_MODULE }
    if ([string]::IsNullOrWhiteSpace($Module)) { $Module = 'plugins:selfcompile' }

    $parts = $Module -split ':'
    if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0]) -or [string]::IsNullOrWhiteSpace($parts[1])) {
        throw "Bad build module '$Module' (expected 'group:name', e.g. plugins:learner)"
    }
    $group = $parts[0]
    $name  = $parts[1]

    if ($group -eq 'plugins') {
        $libDir = "plugins\$name\build\libs"
        $jarPat = "numen-plugin-$name-*.jar"
    } elseif ($group -eq 'api') {
        # api 组产物带 numen_api- 前缀（numen_api-neoforge-*.jar），不是 numen-$name-*
        # 2026-09-29 实测：api:neoforge 构建成功但 glob 推成 numen-neoforge-* → 报
        # "no plugin jar produced"，看起来像构建失败，其实是找错文件名。
        $libDir = "$group\$name\build\libs"
        # 必须用 $() 包起来：PowerShell 把 "$group-$name" 里的连字符当 scope 语法，
        # 整串并入变量名 "group-" → 取到 null → Join-Path 抛错 → glob 静默保持旧值
        # （本项目已栽过两次同类坑：":" 与 "-"）
        $jarPat = "numen_$($group)-$($name)-*.jar"
    } else {
        $libDir = "$group\$name\build\libs"
        $jarPat = "numen-$name-*.jar"
    }

    $env:RDD_SELF_COMPILE_MODULE = $Module
    $global:RDD_SC | Add-Member -NotePropertyName PLUGIN_MODULE   -NotePropertyValue $Module -Force
    $global:RDD_SC | Add-Member -NotePropertyName PLUGIN_JAR_GLOB -NotePropertyValue (Join-Path $libDir $jarPat) -Force
    return $Module
}

Set-RddSelfCompileModule $env:RDD_SELF_COMPILE_MODULE | Out-Null

function Write-ResultJson($path, $obj) {
    $parent = Split-Path -Parent $path
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $json = $obj | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText($path, "$json`n", [Text.UTF8Encoding]::new($false))
}
