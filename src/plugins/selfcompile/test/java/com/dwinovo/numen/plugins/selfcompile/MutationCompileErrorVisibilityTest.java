package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 验证：自编译闭环的**失败侧能不能看见自己的编译错误**。
 *
 * <p><b>为什么这条必须先验</b>（2026-10-01）：今天修掉了 {@code MutationCompiler} 一个致命缺陷 ——
 * 它把 javac 的诊断字节按 UTF-8 解码，而 javac 实际吐的是 GBK（本机 {@code native.encoding=GBK}）
 * → {@code errors.json} 写 <b>0 字节</b>。后果不是日志难看，而是
 * <b>外部 AI 拿不到任何 file:line:col，"自动改码"退化成整段盲目重写</b>。
 *
 * <p>所以在让 AI 写任何新工具之前，必须先证明这条链路是通的：
 * <b>写一段错码 → errors.json 里能看到「文件:行:列: 消息」</b>。
 *
 * <p>本测试走的是游戏内 MCP 工具（{@code selfcompile_request}）底层的同一条路径
 * （{@link MutationPipeline#compile}），所以结论对真实使用同样成立。
 */
class MutationCompileErrorVisibilityTest {

    /** 故意写坏：大括号不闭合。 */
    private static final String BROKEN = """
            package com.dwinovo.numen.plugins.selfcompile.generated;
            public final class Broken {
            """;

    @Test
    void compileFailureWritesUsableFileLineColMessage(@TempDir Path root) throws Exception {
        MutationPipeline pipeline =
                new MutationPipeline(new MutationWorkspace(root), 3, Duration.ofSeconds(60));
        MutationManifest manifest = pipeline.request("S1 probe");
        manifest = pipeline.recordGeneratedSource(manifest, "Broken.java", BROKEN);

        MutationManifest failed = pipeline.compile(manifest);

        assertEquals(MutationState.FAILED, failed.state(), "错码必须判 FAILED");

        Path errFile = Path.of(failed.workspace(), "reports", "errors.json");
        assertTrue(Files.exists(errFile), "errors.json 必须落盘");
        String content = Files.readString(errFile, StandardCharsets.UTF_8);

        assertFalse(content.isBlank(),
                "★ 核心判据：errors.json 不得为空。空 = 自编译失败侧是瞎的，外部 AI 无法定点改码");

        // 必须可定位：文件名 + 行号 + 消息
        assertTrue(content.contains("Broken.java"),
                "必须含出错文件名（外部 AI 要靠它定位）；实际=[" + content + "]");
        assertTrue(content.matches("(?s).*Broken\\.java:\\d+.*"),
                "必须含「文件:行号」，实际=[" + content + "]");

        System.out.println("S1_PROBE errors.json >>>");
        System.out.println(content);
        System.out.println("S1_PROBE <<<");
    }

    /**
     * 反向锁定：解析器对「英文 javac」的诊断必须能解析。
     *
     * <p>（2026-10-06 注：本测试原来写成"用 native.encoding 复现乱码形状"，实际只测了英文样本；
     * 解码口径的校准测试见 {@link MutationCompilerCharsetTest} —— 结论是跟 defaultCharset。）
     */
    @Test
    void chineseDiagnosticsStillParseOnThisMachine() {
        String raw = "C:\\tmp\\Broken.java:2: error: ';' expected\n1 error\n";
        assertFalse(MutationErrorParser.parse(raw).isEmpty(), "英文 javac 必须能解析");
    }

    /**
     * compile.log 也得能读：它是人（和 AI）看完整编译输出地方。
     * 修复前它按 UTF-8 重写一遍 GBK 字节 → 满屏乱码。
     */
    @Test
    void compileLogIsNotMangled(@TempDir Path root) throws Exception {
        MutationPipeline pipeline =
                new MutationPipeline(new MutationWorkspace(root), 3, Duration.ofSeconds(60));
        MutationManifest manifest = pipeline.request("S1 log probe");
        manifest = pipeline.recordGeneratedSource(manifest, "Broken.java", BROKEN);
        MutationManifest failed = pipeline.compile(manifest);

        Path log = Path.of(failed.workspace(), "reports", "compile.log");
        assertTrue(Files.exists(log), "compile.log 必须落盘");
        byte[] raw = Files.readAllBytes(log);
        assertTrue(raw.length > 0, "compile.log 不得为空");
        // 原始字节按 UTF-8 解若出现替换字符，说明日志被二次转码损坏了（修复前就是这个状态）
        String asUtf8 = new String(raw, StandardCharsets.UTF_8);
        assertFalse(asUtf8.isBlank(), "compile.log 解码后不得为空");
    }
}
