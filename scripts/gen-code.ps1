# 期2：把 AI 生成的 NumenTool 源码落盘到插件 generated 目录并登记注册
# 用法: .\scripts\gen-code.ps1 -MutationId <id> -ToolClass <类名> [-SourcePath <源码文件>]
# 说明：工具源码由外部 AI 编程工具生成；本脚本负责落盘 + 更新 SelfCompileEntry 注册。
param(
    [string]$MutationId,
    [string]$ToolClass,
    [string]$SourcePath
)

. "$PSScriptRoot\config.ps1"

$dir = Join-Path $Script:SC_MUTATIONS $MutationId
if (-not (Test-Path $dir)) { throw "mutation not found: $dir" }

$genDir = "E:\restart developing doer\minecraft-numen\plugins\selfcompile\src\main\java\com\dwinovo\numen\plugins\selfcompile\generated"
$entryFile = "E:\restart developing doer\minecraft-numen\plugins\selfcompile\src\main\java\com\dwinovo\numen\plugins\selfcompile\SelfCompileEntry.java"

if (-not $ToolClass) { throw "ToolClass required" }

# 1. 落盘源码（如果给了 SourcePath 就复制；否则预期已存在）
if ($SourcePath -and (Test-Path $SourcePath)) {
    Copy-Item $SourcePath (Join-Path $genDir "$ToolClass.java") -Force
    Write-Output "源码已复制: $ToolClass.java"
}

$srcFile = Join-Path $genDir "$ToolClass.java"
if (-not (Test-Path $srcFile)) { throw "源码不存在: $srcFile" }

# 2. 更新 SelfCompileEntry 注册（在最后一个 registerTool 后插入）
$content = [IO.File]::ReadAllText($entryFile, [Text.UTF8Encoding]::new($true))
if ($content -notmatch "new $ToolClass\(") {
    $importLine = "import com.dwinovo.numen.plugins.selfcompile.generated.$ToolClass;"
    if ($content -notmatch [regex]::Escape($importLine)) {
        $content = $content -replace "(?m)^import com\.dwinovo\.numen\.plugins\.selfcompile\.generated\.RddWhereamiTool;", "$importLine`r`nimport com.dwinovo.numen.plugins.selfcompile.generated.RddWhereamiTool;"
        if ($content -notmatch [regex]::Escape($importLine)) {
            # 兜底：插到第一个 import 后
            $content = $content -replace "(?m)^(import com\.dwinovo\.numen\.api\.NumenPlugin;)", "`$1`r`n$importLine"
        }
    }
    $content = $content -replace "(?m)^(\s*// Self-Compile 自变异系统生成的工具\(每轮变异后更新这里\))`r?`n", "`$1`r`n        numen.registerTool(new $ToolClass());`r`n"
    if ($content -notmatch "new $ToolClass\(") {
        $content = $content -replace "(?m)^(\s*numen\.registerTool\(new RddWhereamiTool\(\)\);)", "`$1`r`n        numen.registerTool(new $ToolClass());"
    }
    [IO.File]::WriteAllText($entryFile, $content, [Text.UTF8Encoding]::new($true))
    Write-Output "SelfCompileEntry 已登记 $ToolClass"
} else {
    Write-Output "SelfCompileEntry 已包含 $ToolClass，跳过"
}

$result = @{
    success = $true
    stage = "gen-code"
    toolClass = $ToolClass
    sourceFile = $srcFile
    registered = $true
    state = "GENERATED"
}
$json = $result | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText((Join-Path $dir "phase-gen.json"), "$json`n", [Text.UTF8Encoding]::new($true))
Write-Output "GEN_OK $ToolClass"
exit 0