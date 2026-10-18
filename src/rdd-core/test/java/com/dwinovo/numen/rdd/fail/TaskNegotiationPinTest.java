package com.dwinovo.numen.rdd.fail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T8 · AI→指挥官上行协议的值钉死（防打字错误事故复发）。
 *
 * <h2>背景：一次已被抓出来的真实事故（2026-09-30 深审 R05）</h2>
 *
 * <p>旧版 {@code TaskNegotiation.parse} 对无法识别的 {@code kind} 会<b>静默降级成 ACCEPT</b>。
 * 而 ACCEPT 在 {@code RddNegotiationInbox.accept()} 里走的是 {@code PENDING.remove()}——
 * 于是模型把 {@code PAUSE} 拼成 {@code "PAUES"} 时：
 *
 * <pre>
 *   一次打字错误 → 解析成 ACCEPT → ACCEPT 被当成「无需军师介入」→ remove
 *   → 之前那条合法待处理的 PAUSE / COUNTER 被顺手清掉 → 指挥官再也收不到
 * </pre>
 *
 * <p>探针实测：{@code parse("PAUES", …).kind() == ACCEPT}。
 * 修法是「kind 非空但无法识别 → 抛 IllegalArgumentException」，
 * 由 {@code RddConcernTool} 转成一次明确的工具失败，让模型重发并看到合法值。
 *
 * <p><b>本类的意义在于：修好之后不许被「优化」回去。</b>「宽容一点，别让模型因为拼错就失败」
 * 是一种非常常见、非常致命的优化冲动。
 *
 * <p>零生产改动、零构建改动、零 Minecraft。
 */
class TaskNegotiationPinTest {

    // ── ① 四种回执的清单 ───────────────────────────────────────────

    @Test
    void kindsArePinnedToExactlyFour() {
        assertEquals(List.of("ACCEPT", "REJECT", "COUNTER", "PAUSE"), TaskNegotiation.kinds(),
                "★ 回执种类契约是 ACCEPT / REJECT / COUNTER / PAUSE。删一个会让模型的合法回执变成"
                        + "「未知值」而直接失败；加一个必须同时改 RddDetector 的消费分支");
    }

    @Test
    void eachKindRoundTripsThroughItsName() {
        for (String name : TaskNegotiation.kinds()) {
            TaskNegotiation.Kind k = TaskNegotiation.Kind.valueOf(name);
            assertEquals(k, TaskNegotiation.parse(name, "s1", "r", "s").kind(),
                    name + " 必须能被自己的名字解析回来（协议与实现不能脱节）");
        }
    }

    // ── ② 未知值必须炸，不许静默降级 ★核心★ ────────────────────────

    @Test
    void unknownKindThrowsInsteadOfSilentlyAccepting() {
        // 这就是那一次真实事故的形状：PAUSE 拼错成 PAUES
        for (String typo : List.of("PAUES", "PAUSEED", "RECJECT", "COUNTERR", "yes", "ok", "确认")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TaskNegotiation.parse(typo, "s1", "reason", "suggestion"),
                    "★ 未知 kind '" + typo + "' 必须抛异常。静默降级成 ACCEPT 会把之前合法的"
                            + "待处理 PAUSE/COUNTER 从 PENDING 里 remove 掉（深审 R05 实测事故）。"
                            + "想「宽容一点」之前请先读这段注释。");
            assertTrue(e.getMessage().contains(typo.trim()), "报错必须回显模型给的那个错值，便于它自我纠正");
            assertTrue(e.getMessage().contains("ACCEPT"), "报错必须列出合法值全集，让模型知道能填什么");
        }
    }

    @Test
    void unknownKindDoesNotConstructAnythingUsable() {
        // 抛异常就意味着没有对象产生；这里显式确认「调用方不会拿到一个错语义的回执」
        TaskNegotiation n = null;
        try {
            n = TaskNegotiation.parse("PAUES", "s1", "r", "s");
            fail("不该走到这里");
        } catch (IllegalArgumentException expected) {
            assertNull(n, "失败路径下不得返回半成品对象");
        }
    }

    @Test
    void blankOrNullKindStillDefaultsToAccept() {
        // 缺省仍是「接单」，与工具 schema 的默认值一致 —— 这不是静默降级，是明确的缺省
        assertEquals(TaskNegotiation.Kind.ACCEPT, TaskNegotiation.parse(null, "s1", "r", "s").kind());
        assertEquals(TaskNegotiation.Kind.ACCEPT, TaskNegotiation.parse("", "s1", "r", "s").kind());
        assertEquals(TaskNegotiation.Kind.ACCEPT, TaskNegotiation.parse("   ", "s1", "r", "s").kind(),
                "纯空白等同缺省（不是「未知值」）");
    }

    @Test
    void kindIsCaseInsensitiveAndTrimmed() {
        assertEquals(TaskNegotiation.Kind.PAUSE, TaskNegotiation.parse("pause", "s1", "r", "s").kind());
        assertEquals(TaskNegotiation.Kind.PAUSE, TaskNegotiation.parse("  PaUsE  ", "s1", "r", "s").kind());
        assertEquals(TaskNegotiation.Kind.REJECT, TaskNegotiation.parse("reject", "s1", "r", "s").kind());
    }

    // ── ③ 构造期不许有 null kind ──────────────────────────────────

    @Test
    void constructorRejectsNullKind() {
        assertThrows(NullPointerException.class,
                () -> new TaskNegotiation(null, "s1", "r", "s"),
                "直接构造时 kind 必须非空（parse 里的缺省逻辑不该被绕过）");
    }

    @Test
    void nullableFieldsNormalizeToEmptyStrings() {
        var n = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, null, null, null);
        assertEquals("", n.taskId());
        assertEquals("", n.reason());
        assertEquals("", n.suggestion());
        assertFalse(n.hasSuggestion());
        // 另一侧也必须去空白，避免 taskId 对不上时被空格绊倒
        var trimmed = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, "  s1  ", "  why  ", "  try  ");
        assertEquals("s1", trimmed.taskId());
        assertEquals("why", trimmed.reason());
        assertEquals("try", trimmed.suggestion());
        assertTrue(trimmed.hasSuggestion());
    }

    // ── ④ 谁需要军师介入 / 谁是「先放着」── 语义分界 ─────────────────

    @Test
    void onlyRejectAndCounterNeedSupervisorAction() {
        assertTrue(new TaskNegotiation(TaskNegotiation.Kind.REJECT, "s1", "r", "").needsSupervisorAction());
        assertTrue(new TaskNegotiation(TaskNegotiation.Kind.COUNTER, "s1", "r", "").needsSupervisorAction());
        assertFalse(new TaskNegotiation(TaskNegotiation.Kind.ACCEPT, "s1", "r", "").needsSupervisorAction(),
                "ACCEPT 不需要军师介入（它是「照单全收」）");
        assertFalse(new TaskNegotiation(TaskNegotiation.Kind.PAUSE, "s1", "r", "").needsSupervisorAction(),
                "★ PAUSE 刻意【不】需要军师介入：它不重规划、不换计划，只把当前二级按住。"
                        + "若把它算进来，RddDetector 会走重规划路径 → PAUSE 退化成 COUNTER，白做"
                        + "（2026-09-29 实机：找村庄的二级连续 stalled→failed→retry 两轮，"
                        + "而前提（附近有村庄）根本不成立 —— 白烧重试预算正是 PAUSE 要治的病）。");
    }

    @Test
    void onlyPauseIsAPauseRequest() {
        assertTrue(new TaskNegotiation(TaskNegotiation.Kind.PAUSE, "s1", "r", "").isPauseRequest());
        assertFalse(new TaskNegotiation(TaskNegotiation.Kind.COUNTER, "s1", "r", "").isPauseRequest(),
                "COUNTER 与 PAUSE 的差别正是意图：前者换做法，后者先放着。不许混为一谈");
        assertFalse(new TaskNegotiation(TaskNegotiation.Kind.REJECT, "s1", "r", "").isPauseRequest());
        assertFalse(new TaskNegotiation(TaskNegotiation.Kind.ACCEPT, "s1", "r", "").isPauseRequest());
    }

    @Test
    void pauseRequestAndSupervisorActionAreMutuallyExclusive() {
        for (String name : TaskNegotiation.kinds()) {
            var n = TaskNegotiation.parse(name, "s1", "r", "s");
            assertFalse(n.needsSupervisorAction() && n.isPauseRequest(),
                    name + " 不得同时既是「要军师改单」又是「先放着」—— 那会让消费端二选一走错分支");
        }
    }

    // ── ⑤ 事件埋点的字段契约（监测台 / 审计读它）───────────────────

    @Test
    void eventDataCarriesTheDeclaredKeys() {
        var d = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, "s1", "太贵了", "改用别的方式")
                .toEventData("companion-1");
        assertEquals(Set.of("companionId", "kind", "taskId", "reason", "suggestion",
                        "needsSupervisorAction", "summary"),
                d.keySet(),
                "★ 事件字段集合是契约：监测台与审计脚本按 key 取值。改名 = 观测面板静默变空");
        assertEquals("companion-1", d.get("companionId"));
        assertEquals("COUNTER", d.get("kind"));
        assertEquals("s1", d.get("taskId"));
        assertEquals("太贵了", d.get("reason"));
        assertEquals("改用别的方式", d.get("suggestion"));
        assertEquals(true, d.get("needsSupervisorAction"));
        assertTrue(String.valueOf(d.get("summary")).contains("COUNTER"));
    }

    @Test
    void eventDataToleratesNullCompanionId() {
        var d = TaskNegotiation.parse("PAUSE", "s1", "r", "").toEventData(null);
        assertEquals("", d.get("companionId"), "null companionId 必须归一为空串，不许塞 null 进事件载荷");
    }

    @Test
    void renderIsReadableAndOmitsAbsentParts() {
        assertEquals("[REJECT] s1 — reason: 这单不对; suggest: 换一个",
                TaskNegotiation.parse("REJECT", "s1", "这单不对", "换一个").render());
        // TaskNegotiation.render()（:78-84）在 taskId 后无条件追加「 — 」，
        // 所以空 reason 时破折号仍在。这是既有行为，本测试按现状钉住它，不改生产代码。
        assertEquals("[ACCEPT] s1 —", TaskNegotiation.parse("ACCEPT", "s1", "", "").render(),
                "kind + taskId 固定成这个形状（破折号会留着，日志与监测台都依赖这个既有格式）");
        assertEquals("[ACCEPT]", TaskNegotiation.parse("ACCEPT", "", "", "").render(),
                "全空时只留类型标签");
        assertEquals("[ACCEPT] s1 — reason: r;", TaskNegotiation.parse("ACCEPT", "s1", "r", "").render(),
                "有 reason 无 suggestion 时不补悬空的 suggest 片段");
        assertEquals("[ACCEPT] s1 — suggest: s", TaskNegotiation.parse("ACCEPT", "s1", "", "s").render(),
                "有 suggestion 无 reason 时不补悬空的 reason 片段");
    }
}