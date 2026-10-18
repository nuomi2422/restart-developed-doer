package com.dwinovo.numen.plugins.selfcompile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>RL-19 硬约束：游戏内 AI 不得直接请求代码变更</b>（2026-10-01 第 4 轮）
 *
 * <p><b>为什么第 1 轮的软约束不够</b>：第 1 轮（{@code 92168ea4}）改的是
 * <b>工具描述与文案</b>——它防的是「模型被文档教唆去调」，
 * <b>防不住「模型自己决定调」</b>。软约束依赖模型听话，硬约束不依赖。
 *
 * <p><b>为什么不用 per-caller 可见性</b>：那要改 {@code NumenApi} 门面
 * （跨插件公开契约）或给 {@code ToolCall} 加身份（{@code api:common} 公开契约），
 * 两个都是<b>架构改动</b>。而本轮查明：<b>这个工具当前没有任何合法调用方</b> ——
 * 外层 {@code run-mutation.ps1} 直接跑 gradle + 部署，<b>不经过游戏内 MCP 工具</b>。
 * <b>没有合法调用方，就不需要识别「谁合法」，只需要默认关门。</b>
 *
 * <p><b>逃生口</b>：{@code config/numen/selfcompile_policy.json} 里
 * {@code {"allowInGameRequest": true}} 可显式开门。<b>默认关</b>。
 */
public final class SelfCompilePolicy {

    /** 策略文件名（放实例 {@code config/numen/} 下）。 */
    public static final String FILE_NAME = "selfcompile_policy.json";

    private final boolean allowInGameRequest;

    private SelfCompilePolicy(boolean allowInGameRequest) {
        this.allowInGameRequest = allowInGameRequest;
    }

    /**
     * 读实例目录下的策略文件；<b>文件不存在或读不出来 ⇒ 一律关门</b>。
     *
     * <p>⚠️ 「读不出来就关门」是刻意的：配置坏了不该让门自己打开。
     * 这与 {@code ProviderRegistry.replayWindowTokens} 里
     * 「{@code <= 0} 回落到默认」的取法<b>刻意相反</b> ——
     * 那里配错会让限额变得过紧（安全的副作用），这里配错会让权限变大（危险的副作用）。
     * <b>两处方向不同，是因为两处失效的代价方向不同。</b>
     */
    public static SelfCompilePolicy load(Path numenConfigDir) {
        try {
            Path f = numenConfigDir.resolve(FILE_NAME);
            if (!Files.isRegularFile(f)) {
                return new SelfCompilePolicy(false);
            }
            String body = Files.readString(f);
            // 故意不做完整 JSON 解析：只认这个开关，认不出就关门。
            return new SelfCompilePolicy(
                    body.replaceAll("\\s+", "").contains("\"allowInGameRequest\":true"));
        } catch (IOException | RuntimeException e) {
            return new SelfCompilePolicy(false);
        }
    }

    /** 测试与缺省构造用。 */
    public static SelfCompilePolicy of(boolean allowInGameRequest) {
        return new SelfCompilePolicy(allowInGameRequest);
    }

    public boolean allowInGameRequest() {
        return allowInGameRequest;
    }

    /**
     * 拒绝时给模型的<b>可执行</b>替代路径 —— 不能只说「不行」，
     * 否则模型会换一个更坏的办法（这正是 RL-19 最初想防的）。
     */
    public static String denialJson() {
        return "{\"success\":false,\"error\":\"internal-channel:this tool is reserved for the outer "
                + "harness and is not callable from inside the game\",\"what_to_do_instead\":\"write a "
                + "learner_note describing what is needed, what you already tried, and what the missing "
                + "piece is; the outer actor reads those notes and does the code change\"}";
    }
}
