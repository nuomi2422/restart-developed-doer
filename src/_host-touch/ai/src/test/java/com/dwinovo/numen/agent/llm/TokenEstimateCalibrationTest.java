package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.AssistantTurn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 计价口径的校准回归（2026-10-01 第 3 轮）。
 *
 * <p><b>为什么要有这个测试</b>：限额（replay 窗口、压缩闸）都靠
 * {@link CompactSplit#estimateTokens} 读数。2026-10-01 实测发现老口径
 * {@code cjk + ascii/4} <b>低估 2.6×</b>（867 对请求/响应配对，live 实例
 * monitor 目录 12 个 {@code context*.jsonl}）—— 于是「20,000 token」的窗口
 * 实际发出约 59,000（实测最大 117,379）。<b>尺子错了，限额就是装饰。</b>
 *
 * <p><b>本测试钉三件事</b>：
 * <ol>
 *   <li>结构标点不再被当成散文 4:1 折算（这是根因）</li>
 *   <li>校准后对 JSON 载荷比老口径高 2.5× 以上</li>
 *   <li>精确值回归 —— 改权重会立刻响，而不是「看起来差不多」</li>
 * </ol>
 *
 * <p>⚠️ <b>精确值是拟合产物，不是自然常数</b>。它们来自 867 对实测的网格拟合
 * （{@code prose=0.80, punct=1.0}，P95 安全系数 {@code 1.5}）。
 * <b>要改权重，先重跑实测拟合，并同步更新本文件的期望值和
 * {@link CompactSplit} 的注释</b> —— 别让注释与代码各说各话。
 */
class TokenEstimateCalibrationTest {

    /** 一条真实的工具结果形状：坐标 + 方块 id + 距离，全是结构标点与数字。 */
    private static final String JSON_TOOL_RESULT =
            "{\"matches\":[{\"x\":-919,\"y\":10,\"z\":-828,\"block\":\"minecraft:water\",\"distance\":3.6}]}";
    private static final String ENGLISH_PROSE =
            "You are Numen, a loyal companion unit in Minecraft, bound to one owner.";
    private static final String CHINESE_TEXT = "先弄清这轮的目标是谁，再决定要不要去挖矿。";
    private static final String RUNTIME_STATE =
            "<runtime_state><inventory>carrying=minecraft:iron_pickaxe x1, minecraft:torch x16</inventory></runtime_state>";

    private static ConvoState.Msg user(String s) {
        return new ConvoState.Msg.User(s);
    }

    private static ConvoState.Msg tool(String s) {
        return new ConvoState.Msg.Tool("call-1", s);
    }

    @Test
    void jsonToolResultIsNoLongerMeteredAsProse() {
        // 老口径：81 字符 → 81/4 ≈ 20 + 8 = 28 token
        assertEquals(28, CompactSplit.estimateTokensLegacyAsciiDiv4(user(JSON_TOOL_RESULT)),
                "老口径就是这个值（B21：期望值必须钉死，改了就响）");

        // 校准后：结构标点按 ~1 token/字符 + P95 安全系数 1.5 → 120
        assertEquals(120, CompactSplit.estimateTokens(user(JSON_TOOL_RESULT)));
    }

    @Test
    void calibrationRaisesJsonPayloadByAtLeast2_5x() {
        int legacy = CompactSplit.estimateTokensLegacyAsciiDiv4(user(JSON_TOOL_RESULT));
        int calibrated = CompactSplit.estimateTokens(user(JSON_TOOL_RESULT));
        assertTrue(calibrated >= legacy * 5 / 2,
                "JSON 载荷校准后至少要 2.5×：legacy=" + legacy + " calibrated=" + calibrated);
    }

    @Test
    void pureChineseIsNotOverPenalised() {
        // 纯中文的校准倍率明显低于 JSON —— CJK 本来就接近 1 token/字，
        // 结构分类不该把中文也当成标点重罚。
        assertEquals(44, CompactSplit.estimateTokens(user(CHINESE_TEXT)));
        assertEquals(29, CompactSplit.estimateTokensLegacyAsciiDiv4(user(CHINESE_TEXT)));
    }

    @Test
    void pinnedValuesAcrossPayloadShapes() {
        assertEquals(98, CompactSplit.estimateTokens(user(ENGLISH_PROSE)),
                "英文散文 71 字符");
        assertEquals(149, CompactSplit.estimateTokens(user(RUNTIME_STATE)),
                "runtime_state 109 字符");
        assertEquals(12, CompactSplit.estimateTokens(user("")),
                "空消息只算 8 token 结构开销 × 1.5");
    }

    @Test
    void toolAndUserMessagesMeterTheSame() {
        // 同一条文本，role 不同不该改变计价 —— 计价的对象是内容，不是角色。
        assertEquals(CompactSplit.estimateTokens(user(JSON_TOOL_RESULT)),
                CompactSplit.estimateTokens(tool(JSON_TOOL_RESULT)));
    }

    @Test
    void assistantToolCallArgumentsAreMetered() {
        // Assistant 的工具名 + 参数也算开销，否则「只调工具不说话」的回合会被低估成 8 token。
        ConvoState.Msg a = new ConvoState.Msg.Assistant(new AssistantTurn(
                "", List.of(new com.dwinovo.numen.agent.provider.LlmToolCall(
                        "c1", "goto", "{\"x\":1,\"y\":2,\"z\":3}")), null));
        assertTrue(CompactSplit.estimateTokens(a) > CompactSplit.MESSAGE_OVERHEAD_TOKENS,
                "带工具调用的 Assistant 必须计出参数开销");
    }

    @Test
    void safetyFactorKeepsTheMeterOnTheConservativeSide() {
        // P95 安全系数：k=1.0 时 40% 的真实样本被低估，k=1.5 降到 1%。
        // 限额低估是危险方向，所以宁可偏紧。
        assertEquals(1.5, CompactSplit.LIMIT_SAFETY_FACTOR, 1e-9);
        int single = CompactSplit.estimateTokens(user(RUNTIME_STATE));
        int unscaled = (int) Math.round(single / CompactSplit.LIMIT_SAFETY_FACTOR);
        assertTrue(single > unscaled, "校准值必须高于未乘安全系数的原始权重值");
    }

    @Test
    void wholeHistorySumsToTheSameAsPerMessage() {
        List<ConvoState.Msg> h = List.of(
                user(JSON_TOOL_RESULT), user(ENGLISH_PROSE), user(CHINESE_TEXT));
        int sum = 0;
        for (ConvoState.Msg m : h) {
            sum += CompactSplit.estimateTokens(m);
        }
        assertEquals(sum, CompactSplit.estimateTokens(h),
                "整段合计必须等于逐条之和 —— 压缩闸与窗口都依赖这个一致性");
    }
}
