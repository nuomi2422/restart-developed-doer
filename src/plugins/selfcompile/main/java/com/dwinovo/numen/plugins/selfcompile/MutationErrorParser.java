package com.dwinovo.numen.plugins.selfcompile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 javac 编译输出解析为结构化错误列表（file/line/column/message）。
 * 自编译"自动改码"的失败侧：编译失败 → 结构化错误落盘 → 外部 AI / 生成器
 * 按 文件:行:列 精确定位修改 → 重编译（不再整段盲目重写）。
 */
public final class MutationErrorParser {

    /** 一条结构化编译错误。 */
    public record CompileError(String file, int line, int column, String message) {}

    /** 匹配 [path]file.java:LINE[:COL]: error|错误: message（javac 中英文格式）。
     *  用非贪婪 `.+?` 而不是 `[^:]+`：Windows 路径带盘符（C:\...）会切断后者。 */
    private static final Pattern WITH_LOC =
            Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:\\s*(?:error|错误)\\s*:\\s*(.*)$");
    /** 中文 javac 有时省略位置前缀：`错误: message`（file=unknown）。 */
    private static final Pattern NO_LOC =
            Pattern.compile("^\\s*(?:error|错误)\\s*:\\s*(.*)$");

    private MutationErrorParser() {}

    /** 解析 javac 输出为结构化错误列表（空输入 → 空列表）。 */
    public static List<CompileError> parse(String compilerOutput) {
        List<CompileError> out = new ArrayList<>();
        if (compilerOutput == null || compilerOutput.isBlank()) return out;
        for (String line : compilerOutput.split("\\R")) {
            Matcher m = WITH_LOC.matcher(line.trim());
            if (m.matches()) {
                out.add(new CompileError(
                        m.group(1).trim(),
                        Integer.parseInt(m.group(2)),
                        m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                        m.group(4).trim()));
                continue;
            }
            Matcher m2 = NO_LOC.matcher(line.trim());
            if (m2.matches()) {
                out.add(new CompileError("unknown", 0, 0, m2.group(1).trim()));
            }
        }
        return out;
    }
}
