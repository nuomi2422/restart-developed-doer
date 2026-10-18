package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RL-19 硬约束的<b>策略层</b>测试（2026-10-01 第 4 轮）。
 *
 * <p><b>为什么测策略层而不是工具层</b>：{@code SelfCompileRequestTool.invoke}
 * 需要 {@code com.dwinovo.numen.agent.tool.ToolCall}，而该包在
 * {@code plugins:selfcompile} 的 test classpath 上不可见（既有事实）。
 * <b>工具层的「真的被拒」留到进游戏实测</b>（判据：同伴调了 → 被拒），
 * 这里钉住的是「门默认关、且只有显式开关能开」这层逻辑。
 *
 * <p><b>判据是「调了被拒」不是「没调」</b> —— 「没调」可能只是模型恰好没想起来。
 */
class SelfCompilePolicyTest {

    @Test
    void policyFileAbsentMeansClosed() throws Exception {
        Path dir = Files.createTempDirectory("cfg");
        assertFalse(SelfCompilePolicy.load(dir).allowInGameRequest(),
                "文件不存在 ⇒ 关门（配置坏了不该让门自己打开）");
    }

    @Test
    void policyFileWithTrueOpensTheGate() throws Exception {
        Path dir = Files.createTempDirectory("cfg");
        Files.writeString(dir.resolve(SelfCompilePolicy.FILE_NAME),
                "{ \"allowInGameRequest\": true }");
        assertTrue(SelfCompilePolicy.load(dir).allowInGameRequest());
    }

    @Test
    void policyFileGarbageMeansClosed() throws Exception {
        Path dir = Files.createTempDirectory("cfg");
        Files.writeString(dir.resolve(SelfCompilePolicy.FILE_NAME), "{ broken json <<<");
        assertFalse(SelfCompilePolicy.load(dir).allowInGameRequest(), "读不出来 ⇒ 关门");
    }

    @Test
    void policyFileFalseMeansClosed() throws Exception {
        Path dir = Files.createTempDirectory("cfg");
        Files.writeString(dir.resolve(SelfCompilePolicy.FILE_NAME),
                "{\"allowInGameRequest\": false}");
        assertFalse(SelfCompilePolicy.load(dir).allowInGameRequest());
    }

    @Test
    void missingDirMeansClosedRatherThanThrowing() {
        // 目录都不存在时也必须关门，而不是抛异常炸掉插件加载
        assertFalse(SelfCompilePolicy.load(Path.of("Z:/definitely-not-here-9271")).allowInGameRequest());
    }

    @Test
    void denialMessageGivesAnActionableAlternative() {
        String d = SelfCompilePolicy.denialJson();
        assertTrue(d.contains("\"success\":false"), d);
        assertTrue(d.contains("learner_note"),
                "拒绝时必须给出可执行的替代路径，不能只说不行 —— 否则模型会换个更坏的办法：" + d);
    }
}
