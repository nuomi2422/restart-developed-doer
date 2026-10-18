package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-09-30 实测修掉的 P1：编译失败时 {@code reports/errors.json} 写成 0 字节。
 *
 * <p>根因：javac 的诊断字节是<b>平台默认字符集</b>（{@code Charset.defaultCharset()}）写的，
 * 代码却硬按 UTF-8 解码，于是 {@code 错误:} 变成乱码，{@link MutationErrorParser}
 * 中英文两种写法都匹配不上。后果不是"日志不好看"，而是<b>自编译的失败侧整个失效</b> ——
 * 外部 AI 拿不到 file:line:col，就只能整段盲目重写。
 *
 * <p><b>2026-10-06 修正口径（探针实测）</b>：解码必须跟 {@code defaultCharset}，
 * <b>不是</b> {@code native.encoding}。两种环境的对照（见 {@code MutationCompiler} 同款注释）：
 * 游戏 JVM {@code -Dfile.encoding=COMPAT} 时两者都是 GBK（口径重合，看不出差别）；
 * JDK21 默认 {@code file.encoding=UTF-8} 时分离 —— javac 吐 UTF-8 而按 GBK 解 → errors.json 又空。
 * 同一段 Broken 源在三种 JVM 参数下的字节实测都在 {@code MutationCompiler.diagnosticCharset()} 里。
 */
class MutationCompilerCharsetTest {

    /** javac 诊断字符集 = {@code Charset.defaultCharset()}（=file.encoding）；不是 native.encoding。 */
    private static Charset diagnosticCharset() {
        Charset fallback = Charset.defaultCharset();
        return fallback == null ? StandardCharsets.UTF_8 : fallback;
    }

    private static byte[] javacBytesForBrokenSource(String prefix) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "测试必须跑在 JDK 上");
        Path workspace = Files.createTempDirectory(prefix);
        Path sourceDir = Files.createDirectories(workspace.resolve("source"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes"));
        // 故意写坏：大括号不闭合
        Files.writeString(sourceDir.resolve("Broken.java"),
                "package p;\npublic final class Broken {\n", StandardCharsets.UTF_8);
        var out = new java.io.ByteArrayOutputStream();
        compiler.run(null, out, out, new String[]{
                "-encoding", "UTF-8", "-proc:none", "-d", classesDir.toString(),
                sourceDir.resolve("Broken.java").toString()});
        return out.toByteArray();
    }

    /** 解码必须用 javac 诊断真正的字符集，解析器才认得出错误行。 */
    @Test
    void diagnosticsAreDecodedWithTheCompilersOwnCharset() throws Exception {
        Charset javacCharset = diagnosticCharset();
        assertNotNull(javacCharset);
        byte[] raw = javacBytesForBrokenSource("mut-charset-");
        assertTrue(raw.length > 0, "javac 必须给出诊断字节");

        String decoded = new String(raw, javacCharset);
        assertFalse(decoded.isBlank());
        List<MutationErrorParser.CompileError> errors = MutationErrorParser.parse(decoded);
        assertFalse(errors.isEmpty(),
                "按正确 charset 解码后必须能解析出错误（修复前这里解析出 0 条 → errors.json 空）。实际=[" + decoded + "]");
        assertTrue(errors.get(0).file().endsWith("Broken.java"),
                "必须能定位到出错的文件；实际=" + errors.get(0).file());
        assertTrue(errors.get(0).line() > 0, "必须能定位到行号；实际=" + errors.get(0).line());
    }

    /** 反向锁定：用错字符集解码会得到乱码且解析不出任何错误 —— 这就是修复前的状态。 */
    @Test
    void decodingWithTheWrongCharsetIsWhatUsedToBreakTheParser() throws Exception {
        if (diagnosticCharset().equals(StandardCharsets.UTF_8)) {
            return;   // 本机诊断本来就是 UTF-8，本缺陷在此环境不暴露
        }
        byte[] raw = javacBytesForBrokenSource("mut-charset-neg-");
        String wrong = new String(raw, StandardCharsets.UTF_8);
        boolean mangled = wrong.indexOf('�') >= 0 || wrong.indexOf('?') >= 0;
        assertTrue(mangled, "用 UTF-8 解码 GBK 字节应出现替换字符/问号；实际=" + wrong);
        assertEquals(0, MutationErrorParser.parse(wrong).size(),
                "错误字符集解码后解析器一条都认不出 → errors.json 为空（修复前就是这个状态）");
    }

    /**
     * 端到端：整条 {@code compile()} 必须真的写出一份<b>非空</b>的 {@code reports/errors.json}。
     * 这正是修复前挂掉的那条断言，现在必须绿。
     */
    @Test
    void compileWritesNonEmptyStructuredErrorsForBrokenSource() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-pipe-bad-");
        MutationPipeline pipeline =
                new MutationPipeline(new MutationWorkspace(root), 3, java.time.Duration.ofSeconds(60));
        MutationManifest manifest = pipeline.request("broken tool");
        manifest = pipeline.recordGeneratedSource(manifest, "Broken.java",
                "package com.dwinovo.numen.plugins.selfcompile.generated;\npublic final class Broken {\n");
        MutationManifest failed = pipeline.compile(manifest);
        assertEquals(MutationState.FAILED, failed.state());

        Path errFile = Path.of(failed.workspace(), "reports", "errors.json");
        assertTrue(Files.exists(errFile), "errors.json should exist");
        String content = Files.readString(errFile, StandardCharsets.UTF_8);
        assertFalse(content.isBlank(),
                "errors.json 不得为空：外部 AI 靠它精确定位 file:line:col 再改码（修复前这里是 0 字节）");
        assertTrue(content.contains("Broken.java"), "必须含出错文件名；实际=" + content);
    }

    /**
     * 解析器本身：中英文两种 javac 都要认（它本来就支持，这里守住不让回退）。
     * 样本用 javac 的<b>真实格式</b>：{@code 文件:行: 错误: 消息}（行号后无列号）。
     */
    @Test
    void parserAcceptsBothEnglishAndChineseDiagnostics() {
        String english = "C:\\tmp\\Broken.java:2: error: ';' expected\n1 error\n";
        String chinese = "C:\\tmp\\Broken.java:2: 错误: 进行语法分析时已到达文件结尾\n1 个错误\n";
        for (String text : List.of(english, chinese)) {
            var errs = MutationErrorParser.parse(text);
            assertEquals(1, errs.size(), "应解析出 1 条：" + text.trim());
            assertEquals(2, errs.get(0).line());
            // file 保留**完整路径**（含盘符）——外部 AI 要靠它定位文件，不能只留文件名
            assertEquals("C:\\tmp\\Broken.java", errs.get(0).file());
        }
        var withCol = MutationErrorParser.parse("C:\\tmp\\Broken.java:2:5: error: bad token");
        assertEquals(1, withCol.size());
        assertEquals(5, withCol.get(0).column());
        var noLoc = MutationErrorParser.parse("错误: 找不到符号");
        assertEquals(1, noLoc.size());
        assertEquals("unknown", noLoc.get(0).file());
        assertEquals(0, MutationErrorParser.parse("").size());
        assertEquals(0, MutationErrorParser.parse(null).size());
        assertEquals(0, MutationErrorParser.parse("1 个错误\n").size(),
                "汇总行不是错误行，不许解析成一条");
    }
}
