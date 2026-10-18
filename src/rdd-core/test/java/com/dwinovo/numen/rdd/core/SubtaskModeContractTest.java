package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T3 · 两种检测模式的契约（把「AI_ASSISTED 是死代码」这件事本身钉住）。
 *
 * <h2>为什么现在写这个</h2>
 *
 * <p>本仓的 {@link Subtask} record 预留了三个专为「定时复检」设计的字段
 * （{@code detectionIntervalSeconds} / {@code maxAiChecks} / {@code recheckOnUnchanged}），
 * 外加一个 {@code AI_ASSISTED} 检测模式和一个出口 {@code TaskChain.applyAiAssistedResult}。
 * <b>它们目前全仓零生产调用者</b> —— 没有任何路径能让大模型隔一段时间回来做复检。
 *
 * <p>这本身不是 bug，是「还没接线」。但它有一个极其危险的性质：
 * <b>死代码是改坏程序时最容易被顺手「清理」或「顺手接上」的东西</b>。
 * 清理掉 → 将来想接线的人发现字段没了，无从追溯；
 * 顺手接上 → 没有任何测试拦着，会直接改动 M3 主链路。
 *
 * <p>所以本类做两件事：
 * <ol>
 *   <li><b>把现状钉住</b>：这三个字段的校验规则、两个模式的互斥关系、出口只认字面量
 *       {@code "CONFIRMED"} —— 一律不许「顺手优化」。</li>
 *   <li><b>把「还没接线」也钉住</b>：<code>aiAssistedHasNoProductionCallerYet</code> 扫全仓 main 源码，
 *       一旦真的接线了，这个测试会<b>失败并要求同步更新本类</b>。这是有意的：
 *       它是「接线进度条上的红线」，接线是好事，但必须走独立 Stage 并同时补真正的回归。</li>
 * </ol>
 *
 * <p>零生产改动、零构建改动、零 Minecraft。
 */
class SubtaskModeContractTest {

    private static Subtask hc(String id) {
        return Subtask.hardCoded(id, "do " + id, Map.of("asset_key", "minecraft:stone", "minimum", 1));
    }

    private static TaskChain chain(PrimaryGoal... primaries) {
        return new TaskChain(new Goal("g", "goal", List.of(primaries)));
    }

    // ── ① HARD_CODED 的形状 ─────────────────────────────────────────

    @Test
    void hardCodedRequiresANonEmptyCondition() {
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.hardCoded("s1", "do it", Map.of()),
                "★ 硬编码二级必须有非空判据。空判据 = 永远判不了 = 链永远不动且不报错。");
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.hardCoded("s1", "do it", null),
                "null 判据等同空判据，必须在建链时就拒（不能拖到判定层静默当 hold = 假完成）");
    }

    @Test
    void hardCodedZeroesTheAiOnlyFields() {
        var s = hc("s1");
        assertEquals(DetectionMode.HARD_CODED, s.detectionMode());
        assertEquals(0L, s.detectionIntervalSeconds(), "硬编码二级没有复检间隔");
        assertEquals(0, s.maxAiChecks(), "硬编码二级没有复检次数上限");
        assertFalse(s.recheckOnUnchanged(), "硬编码二级不适用『没变化也复检』");
        assertNull(s.body(), "三参构造不携带身体指令");
    }

    @Test
    void hardCodedWithBodyKeepsAiFieldsZeroed() {
        var s = Subtask.hardCoded("s1", "mine it",
                Map.of("asset_key", "minecraft:stone", "minimum", 1),
                new BodyInstruction("mine", Map.of("target", "minecraft:stone")));
        assertEquals(0L, s.detectionIntervalSeconds());
        assertEquals(0, s.maxAiChecks());
        assertFalse(s.recheckOnUnchanged());
        assertNotNull(s.body());
    }

    // ── ② AI_ASSISTED 的形状 ─────────────────────────────────────────

    @Test
    void aiAssistedForcesAnEmptyCondition() {
        var s = Subtask.aiAssisted("s1", "ask the model", 30, 3, true);
        assertEquals(DetectionMode.AI_ASSISTED, s.detectionMode());
        assertTrue(s.condition().isEmpty(),
                "★ AI_ASSISTED 的判据由模型回执给出，构造期必须强制空 Map —— "
                        + "有判据却走 AI 判定 = 两个真身打架");
    }

    @Test
    void aiAssistedRequiresPositiveIntervalAndMaxChecks() {
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.aiAssisted("s1", "ask", 0, 3, true), "间隔必须 > 0");
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.aiAssisted("s1", "ask", -5, 3, true), "间隔必须 > 0（负数同理）");
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.aiAssisted("s1", "ask", 30, 0, true), "复检次数上限必须 > 0");
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.aiAssisted("s1", "ask", 30, -1, true), "复检次数上限必须 > 0（负数同理）");
    }

    @Test
    void aiAssistedIntervalIsCarriedInSecondsNotTicks() {
        // 30 秒 = 600 tick。这条钉住的是「单位是秒不是 tick」——量纲错误会让复检周期变成 1/20。
        assertEquals(30L, Subtask.aiAssisted("s1", "ask", 30, 3, true).detectionIntervalSeconds());
    }

    @Test
    void recheckOnUnchangedIsCarriedAsAGivenFlag() {
        assertTrue(Subtask.aiAssisted("s1", "ask", 30, 3, true).recheckOnUnchanged());
        assertFalse(Subtask.aiAssisted("s1", "ask", 30, 3, false).recheckOnUnchanged());
    }

    @Test
    void bothModesRejectMissingIdentityFields() {
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.hardCoded(null, "do it", Map.of("asset_key", "minecraft:stone")), "id 必填");
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.hardCoded("  ", "do it", Map.of("asset_key", "minecraft:stone")), "id 不得为空白");
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.hardCoded("s1", " ", Map.of("asset_key", "minecraft:stone")), "描述必填");
        assertThrows(IllegalArgumentException.class,
                () -> Subtask.aiAssisted("s1", "", 30, 3, true), "描述必填（AI 模式同样）");
    }

    @Test
    void nullDetectionModeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Subtask("s1", "do it", null, Map.of("asset_key", "minecraft:stone"), 0, 0, false, null),
                "检测模式必填：null 会让下游的 != HARD_CODED 判断全部成立 = 交还 AI 但没人接线");
    }

    @Test
    void conditionMapIsDefensivelyCopied() {
        var mutable = new java.util.LinkedHashMap<String, Object>();
        mutable.put("asset_key", "minecraft:stone");
        mutable.put("minimum", 1);
        var s = Subtask.hardCoded("s1", "do it", mutable);
        mutable.put("asset_key", "minecraft:dirt");
        assertEquals("minecraft:stone", s.condition().get("asset_key"),
                "判据必须在构造时防御性拷贝：否则调用方事后改 Map 会让已建链的判据漂移（静默改判）");
    }

    // ── ③ 出口 applyAiAssistedResult 的语义 ─────────────────────────

    /** 建一条只含 AI_ASSISTED 二级的链。 */
    private static TaskChain aiChain(Subtask s) {
        var c = chain(new PrimaryGoal("p", "让模型自己看", List.of(s)));
        c.startCurrent();
        return c;
    }

    @Test
    void applyAiAssistedResultOnlyAcceptsTheExactWordConfirmed() {
        var c = aiChain(Subtask.aiAssisted("s1", "ask", 30, 3, true));
        // 大小写不同 / 前后空白 / 换一种说法 —— 全部必须判「未确认」，绝不猜。
        for (String notConfirmed : List.of("confirmed", "CONFIRMED ", " CONFIRMED", "yes", "done", "完成")) {
            assertFalse(c.applyAiAssistedResult("s1", notConfirmed),
                    "只认字面量 \"CONFIRMED\"；'" + notConfirmed + "' 必须判未确认（宁可漏判，绝不假完成 = RL-11）");
        }
        assertEquals(SubtaskStatus.RUNNING, c.currentSubtaskStatus(), "未确认时状态不许被推进");
        assertTrue(c.applyAiAssistedResult("s1", "CONFIRMED"), "字面量 CONFIRMED 才算确认");
    }

    @Test
    void applyAiAssistedResultRejectsHardCodedSubtasks() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        c.startCurrent();
        assertThrows(IllegalStateException.class, () -> c.applyAiAssistedResult("s1", "CONFIRMED"),
                "★ 硬编码二级的结果只许由服务端判定回填（applyHardCodedResult）。"
                        + "开这个口子 = 模型可以自己宣布「服务端判据已经满足」（RL-11「不得把估计当确认」）。");
    }

    @Test
    void applyAiAssistedResultRejectsWhenNotRunning() {
        var c = chain(new PrimaryGoal("p", "让模型自己看", List.of(Subtask.aiAssisted("s1", "ask", 30, 3, true))));
        // 未 startCurrent → PENDING
        assertThrows(IllegalStateException.class, () -> c.applyAiAssistedResult("s1", "CONFIRMED"),
                "非 RUNNING 状态下的回执必须拒绝（否则重启恢复前的老回执能把新链直接推完）");
    }

    @Test
    void applyAiAssistedResultRejectsStaleSubtaskId() {
        var c = chain(new PrimaryGoal("p", "让模型自己看",
                List.of(Subtask.aiAssisted("s1", "ask", 30, 3, true), Subtask.aiAssisted("s2", "ask", 30, 3, true))));
        c.startCurrent();
        assertThrows(IllegalArgumentException.class, () -> c.applyAiAssistedResult("s2", "CONFIRMED"),
                "陈旧 id 必须拒绝（模型回放旧回执就能凭空完成一整串步骤）");
    }

    @Test
    void applyAiAssistedResultRejectsNullResult() {
        var c = aiChain(Subtask.aiAssisted("s1", "ask", 30, 3, true));
        assertThrows(NullPointerException.class, () -> c.applyAiAssistedResult("s1", null),
                "null 回执必须当场炸掉：null 会被当成「不确定」而静默 return false，"
                        + "调用方就永远等不到明确的失败原因");
    }

    @Test
    void aiAssistedConfirmationAdvancesLikeHardCodedDoes() {
        var c = aiChain(Subtask.aiAssisted("s1", "ask", 30, 3, true));
        c.applyAiAssistedResult("s1", "CONFIRMED");
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, c.primaryStatus(),
                "最后一个二级确认后必须停在等监督（与硬编码路径同一条出口）");
    }

    // ── ④ 死代码扫描：把「还没接线」也钉住 ──────────────────────────

    /** 逐级向上找到含 settings.gradle 的仓库根。 */
    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            if (Files.isRegularFile(cur.resolve("settings.gradle"))
                    || Files.isRegularFile(cur.resolve("settings.gradle.kts"))) {
                return cur;
            }
        }
        return cwd;
    }

    /** 扫全仓 main 源码，返回含有 needle 的 (相对路径, 行号) 列表；跳过注释行。 */
    private static List<String> productionHits(String needle) {
        Path root = repoRoot();
        List<String> hits = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().replace('\\', '/').endsWith(".java"))
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return s.contains("/src/main/java/");
                    })
                    .forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        try {
                            for (String line : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n")) {
                                String t = line.trim();
                                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
                                if (t.contains(needle)) hits.add(rel + " :: " + t);
                            }
                        } catch (IOException ignored) {
                            // 读不到就当没命中；本测试是「有调用就红」，漏读会漏红，
                            // 因此另配 scanMechanismItselfWorks 验证扫描确实跑到了文件。
                        }
                    });
        } catch (IOException ignored) {
            fail("扫不到仓库根（工作目录 " + root + "）。请修 repoRoot()");
        }
        return hits;
    }

    @Test
    void scanMechanismItselfWorks() {
        // 自检：确保扫描确实读到了 TaskChain 本体，而不是因为路径写错而「零命中」。
        var self = productionHits("applyAiAssistedResult");
        assertTrue(self.stream().anyMatch(h -> h.startsWith("rdd-core/")),
                "扫描必须真的读到 rdd-core 的生产源码。实际读到：" + self);
        assertTrue(self.size() >= 1, "applyAiAssistedResult 至少要读到它自己的声明。实际 " + self.size());
    }

    @Test
    void aiAssistedHasNoProductionCallerYet() {
        List<String> hits = productionHits("Subtask.aiAssisted(");
        assertTrue(hits.isEmpty(),
                "★ Subtask.aiAssisted(...) 现在<b>没有任何生产调用者</b>（这是「还没接线」的现状，不是 bug）。"
                        + "若这里变红，说明有人接上了 AI_ASSISTED —— 那必须走独立 Stage："
                        + "① 需要新增 AI→指挥官的判定回执工具（nudge 是单向的，没有回流通道）；"
                        + "② 三个字段的语义（谁记上次检查时刻 / 超限走 SKIPPED 还是 FAILED / "
                        + "资产变化要不要重置计数）尚未定义；③ 计时必须落盘（照抄 RddRuntime.pausedAt 范式）。"
                        + "接完请回来更新本测试与 61 号文档。命中处：" + hits);
    }

    @Test
    void applyAiAssistedResultHasNoProductionCallerYet() {
        List<String> hits = productionHits(".applyAiAssistedResult(");
        assertTrue(hits.isEmpty(),
                "★ TaskChain.applyAiAssistedResult(...) 现在没有任何生产调用者。"
                        + "变红 = 有人接上了；接线必须补真正的回归（静态测试测不了「模型回来复检」这件事）。"
                        + "命中处：" + hits);
    }

    @Test
    void aiOnlyFieldsHaveNoProductionReaderYet() {
        // 注意：只扫「以点开头的调用点」。字段在 record 内部可被 accessor 读到，
        // 但主链路不得消费它们（消费即接线）。
        assertTrue(productionHits(".detectionIntervalSeconds()").isEmpty(),
                "detectionIntervalSeconds() 尚无生产读取点。变红 = 开始接线了，同上处理。");
        assertTrue(productionHits(".maxAiChecks()").isEmpty(),
                "maxAiChecks() 尚无生产读取点。变红 = 开始接线了，同上处理。");
        assertTrue(productionHintsOnRecheckOnUnchanged().isEmpty(),
                "recheckOnUnchanged() 尚无生产读取点。变红 = 开始接线了，同上处理。");
    }

    /** recheckOnUnchanged 会被 isXxx() 之类的前缀命中，这里精确取「点 + 名字」。 */
    private static List<String> productionHintsOnRecheckOnUnchanged() {
        List<String> out = new ArrayList<>();
        for (String h : productionHits("recheckOnUnchanged")) {
            if (h.contains(".recheckOnUnchanged()")) out.add(h);
        }
        return out;
    }

    @Test
    void hardCodedIsTheOnlyModeReachableFromThePlanner() {
        // SubtaskSpec 强制 condition 非空，与 aiAssisted 的空 condition 互斥 —— 规划器结构上产不出 AI_ASSISTED
        List<String> hits = productionHits("Subtask.hardCoded(");
        assertFalse(hits.isEmpty(), "钉住前自检：主链路必须仍在建硬编码二级");
        for (String h : hits) {
            assertFalse(h.contains("RddChainFactory")
                            && h.contains("detectionMode"),
                    "RddChainFactory 不该出现按模式分支的建链写法（当前应一律 hardCoded）。命中：" + h);
        }
    }
}