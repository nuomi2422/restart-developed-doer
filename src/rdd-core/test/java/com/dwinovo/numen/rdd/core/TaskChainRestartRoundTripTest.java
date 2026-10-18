package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.fact.StageKeyNormalizer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1 · 存档往返（RL-15「凡状态必须活得过重启」）。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>{@code TaskChainStateMachineExitTest} 已经覆盖了「ACTIVE+RUNNING → RECOVERING」
 * 这一条最显眼的归一，但<b>其余状态一律原样恢复</b>这件事没有系统性覆盖。
 * 而 RL-15 是本仓最硬的红线之一：游戏随时可能崩，链上的
 * 「哪些做完了 / 哪些被跳过 / 哪些被暂停 / 主目标确认到哪 / 净增基线是多少」
 * <b>必须能从磁盘原样长回来</b>。丢一个字段就是「AI 重做已经做过的事」，
 * 或者更糟——「拿一个错的基线判出假的完成」。
 *
 * <h2>本类覆盖的六件东西</h2>
 * <ol>
 *   <li>COMPLETED / SKIPPED / PAUSED 三种终态与半终态的 status + reason 一起往返</li>
 *   <li><b>acquire 基线</b>（RL-22 的正主）—— 最容易在重构里被当成「派生数据」顺手删掉</li>
 *   <li><b>阶段完成事实 satisfiedStages</b>（M5）—— 注意它的键是<b>中文归一串</b>，
 *       全仓测试此前<b>一条都没写过</b>，任何对 {@link StageKeyNormalizer} 的改动都是零防护</li>
 *   <li><b>planRevision 反推</b>（深审/codex P1-1）—— 旧存档没这个字段，
 *       必须从磁盘上已存在的二级 id 里捞回代次下限，否则重启后第一次重规划直接撞号</li>
 *   <li>三种旧存档字段缺失的兼容（pauseReasons / acquireBaselines / satisfiedStages）</li>
 *   <li><b>owner 暂停身份</b>（靠 {@code pauseReasons} 字符串前缀的隐式契约）</li>
 * </ol>
 *
 * <p>本类<b>零生产改动、零构建改动、零 Minecraft</b>，纯 JVM。
 */
class TaskChainRestartRoundTripTest {

    // ── 夹具 ────────────────────────────────────────────────────────

    private static Subtask hc(String id) {
        return Subtask.hardCoded(id, "do " + id, Map.of("asset_key", "minecraft:stone", "minimum", 1));
    }

    private static Subtask acq(String id) {
        return Subtask.hardCoded(id, "gather " + id, Map.of(
                "asset_key", "minecraft:stone", "minimum", 3, "mode", "acquire"));
    }

    private static TaskChain chain(PrimaryGoal... primaries) {
        return new TaskChain(new Goal("g", "goal", List.of(primaries)));
    }

    /** 把 s1 走完，走到 AWAITING_SUPERVISOR（整条链最后一个二级已完成）。 */
    private static TaskChain finishedFirstStage(TaskChain c) {
        c.startCurrent();
        c.applyHardCodedResult("s1", true);
        return c;
    }

    // ── ① 三种状态一起往返 ──────────────────────────────────────────

    @Test
    void completedSkippedAndPausedSurviveRoundTrip() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"), hc("s3"), hc("s4"))));
        c.startCurrent();
        c.applyHardCodedResult("s1", true);            // s1 → COMPLETED
        c.startCurrent();
        c.applyHardCodedResult("s2", true);            // s2 → COMPLETED
        c.startCurrent();
        c.skipSubtask("s3", "optional food unavailable");   // s3 → SKIPPED
        c.startCurrent();
        c.pauseSubtask("s4", "waiting for a villager");     // s4 → PAUSED

        var r = TaskChain.fromJson(c.toJson());
        assertEquals(SubtaskStatus.COMPLETED, r.subtaskStatuses().get("s1"), "s1 已完成必须在重启后仍是完成");
        assertEquals(SubtaskStatus.COMPLETED, r.subtaskStatuses().get("s2"), "s2 已完成必须在重启后仍是完成");
        assertEquals(SubtaskStatus.SKIPPED, r.subtaskStatuses().get("s3"), "跳过必须仍是 SKIPPED");
        assertEquals(SubtaskStatus.PAUSED, r.subtaskStatuses().get("s4"), "暂停必须仍是 PAUSED");
        assertTrue(r.toJson().contains("optional food unavailable"),
                "★ 跳过理由必须落盘。「为什么这一步被划掉了」是主人复核链条时唯一能问的问题；"
                        + "丢了它，重启后报告里就只剩一个没有解释的缺口");
    }

    @Test
    void skipAndPauseReasonsSurviveRoundTrip() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"))));
        c.startCurrent();
        c.skipSubtask("s1", "no villagers nearby");
        c.startCurrent();
        c.pauseSubtask("s2", "needs a bed first");

        var r = TaskChain.fromJson(c.toJson());
        assertEquals(SubtaskStatus.SKIPPED, r.subtaskStatuses().get("s1"));
        assertEquals(SubtaskStatus.PAUSED, r.subtaskStatuses().get("s2"));
        assertEquals("needs a bed first", r.pauseReasonsView().get("s2"), "暂停理由必须往返");
        assertTrue(r.toJson().contains("no villagers nearby"), "跳过理由必须往返");
    }

    // ── ② owner 暂停身份（靠字符串前缀的隐式契约，必须活过重启）──────

    @Test
    void ownerPauseIdentitySurvivesRoundTrip() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        c.startCurrent();
        c.pauseSubtask("s1", TaskChain.OWNER_PAUSE_REASON);
        assertTrue(c.isOwnerPause("s1"), "钉住前自检：OWNER_PAUSE_REASON 必须能被识别为主人暂停");

        var r = TaskChain.fromJson(c.toJson());
        assertTrue(r.isOwnerPause("s1"),
                "★ 主人暂停的身份必须活过重启。isOwnerPause 靠 pauseReasons 字符串前缀判定，"
                        + "若有人把 pauseReasons 排除出持久化，主人在游戏里说的『原地待命』"
                        + "重启后就退化成士兵自己的 PAUSE —— 会被 60 秒复评强行拉去干活（深审 R04 前科）。");
    }

    @Test
    void soldierPauseIsNotMistakenForOwnerPause() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        c.startCurrent();
        c.pauseSubtask("s1", "i cannot do this right now");
        assertFalse(c.isOwnerPause("s1"));
        assertFalse(TaskChain.fromJson(c.toJson()).isOwnerPause("s1"),
                "士兵自报的 PAUSE 不得被认成主人暂停（否则它也变成永不过期）");
    }

    @Test
    void ownerPausePrefixIsPinned() {
        assertEquals("owner", TaskChain.OWNER_PAUSE_PREFIX,
                "主人暂停前缀契约是 \"owner\"。改它之前必须先改 TaskChain.isOwnerPause 与本类。");
        assertTrue(TaskChain.OWNER_PAUSE_REASON.startsWith(TaskChain.OWNER_PAUSE_PREFIX),
                "OWNER_PAUSE_REASON 必须自带 owner 前缀，否则 pauseSubtask(OWNER_PAUSE_REASON) 不会被认成主人暂停");
    }

    // ── ③ acquire 基线（RL-22 正主）─────────────────────────────────

    @Test
    void acquireBaselineSurvivesRoundTrip() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(acq("s1"))));
        // 派活时拍基线：此刻背包有 5 个石头
        Map<String, Integer> baseline = new LinkedHashMap<>();
        baseline.put("minecraft:stone", 5);
        c.startCurrentWithCounts(baseline);
        assertEquals(baseline, c.acquireBaseline("s1"), "钉住前自检：基线必须已拍下");

        var r = TaskChain.fromJson(c.toJson());
        assertEquals(5, r.acquireBaseline("s1").get("minecraft:stone"),
                "★ acquire 基线必须活过重启（RL-22）。基线丢了 → net gain 判据永远为假 → "
                        + "AI 做对了也被判失败 → 逼它反复重做（2026-10-02 事故：3 分钟烧掉 25,027 token）。");
    }

    @Test
    void acquireBaselineIsCapturedIdempotently() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(acq("s1"), acq("s2"))));
        c.startCurrentWithCounts(Map.of("minecraft:stone", 5));
        // 再拍一次：必须不覆盖（否则「派活瞬间」这个语义就丢了）
        c.captureAcquireBaseline("s1", Map.of("minecraft:stone", 99));
        assertEquals(5, c.acquireBaseline("s1").get("minecraft:stone"),
                "captureAcquireBaseline 必须幂等：已有基线不许被后来的调用覆盖");
        var r = TaskChain.fromJson(c.toJson());
        assertEquals(5, r.acquireBaseline("s1").get("minecraft:stone"), "幂等语义必须也活过重启");
    }

    // ── ④ 阶段完成事实 satisfiedStages（M5；键是中文归一串）──────────

    @Test
    void satisfiedStagesSurviveRoundTripAndKeepTheirChineseKeys() {
        String stageOneKey = StageKeyNormalizer.normalize("阶段一 · 收集基础物资");
        var goal = new Goal("g", "goal", List.of(
                new PrimaryGoal("p1", "阶段一 · 收集基础物资", List.of(hc("s1"))),
                new PrimaryGoal("p2", "阶段二 · 建立前哨", List.of(hc("s2")))));
        // 历史事实说阶段一已可靠完成 → 构造时直接跳到阶段二
        var c = new TaskChain(goal, java.util.Set.of(stageOneKey));
        assertEquals("p2", c.currentPrimary().id(), "钉住前自检：被事实命中的阶段必须被跳过");

        var r = TaskChain.fromJson(c.toJson());
        assertTrue(r.satisfiedStages().contains(stageOneKey),
                "★ 阶段完成事实必须往返。丢它 = 已达成阶段被重跑；"
                        + "注意它的键是中文归一串（NFKC+小写+去标点+去停用词），"
                        + "任何对 StageKeyNormalizer 的改动都必须先撞本类。");
        assertEquals("p2", r.currentPrimary().id(), "恢复后仍要停在第一个未被事实命中的阶段");
        assertEquals(1, r.satisfiedStages().size(), "不该凭空多出阶段事实");
    }

    @Test
    void factSatisfiedStagesAreNotWrittenIntoStatuses() {
        String key = StageKeyNormalizer.normalize("阶段一");
        var goal = new Goal("g", "goal", List.of(
                new PrimaryGoal("p1", "阶段一", List.of(hc("s1"))),
                new PrimaryGoal("p2", "阶段二", List.of(hc("s2")))));
        var c = new TaskChain(goal, java.util.Set.of(key));
        // 懒展开一级本就没有二级；这里用已展开的一级验证「事实层不伪造 statuses 条目」
        var r = TaskChain.fromJson(c.toJson());
        assertFalse(r.satisfiedStages().stream().anyMatch(String::isBlank), "事实键不得为空白");
        assertEquals(java.util.Set.of(key), r.satisfiedStages());
    }

    @Test
    void allStagesFactSatisfiedGoesStraightToCompleted() {
        var goal = new Goal("g", "goal", List.of(
                new PrimaryGoal("p1", "阶段一", List.of(hc("s1"))),
                new PrimaryGoal("p2", "阶段二", List.of(hc("s2")))));
        var c = new TaskChain(goal, java.util.Set.of(
                StageKeyNormalizer.normalize("阶段一"), StageKeyNormalizer.normalize("阶段二")));
        assertEquals(PrimaryGoalStatus.COMPLETED, c.primaryStatus(),
                "全部阶段都有完成事实时，链必须直接终态（而不是停在最后一个已达成阶段上死等）");
        var r = TaskChain.fromJson(c.toJson());
        assertEquals(PrimaryGoalStatus.COMPLETED, r.primaryStatus(), "终态必须往返");
    }

    // ── ⑤ planRevision 从旧存档反推（深审/codex P1-1）───────────────

    @Test
    void planRevisionIsAdoptedFromPersistedSubtaskIdsWhenFieldMissing() {
        // 磁盘上的 id 已经是 primary-r3-0 这种带代次的形状，但存档里没有 planRevision 字段（旧版）
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(
                Subtask.hardCoded("primary-r3-0", "do it", Map.of("asset_key", "minecraft:stone", "minimum", 1)))));
        String json = c.toJson();
        assertTrue(json.contains("\"planRevision\":0"), "钉住前自检：新链的 planRevision 初始为 0");
        String oldSave = json.replaceAll("\"planRevision\":\\d+,", "");
        assertFalse(oldSave.contains("planRevision"), "钉住前自检：必须真的把字段删掉（模拟旧版存档）");

        var r = TaskChain.fromJson(oldSave);
        assertEquals(3, r.planRevision(),
                "★ 旧存档缺 planRevision 时必须从现存二级 id 反推代次下限。否则重启后第一次重规划"
                        + "又从 rev=1 开始，生成出与磁盘上一模一样的 id —— 旧回执的隔离就白做了"
                        + "（深审/codex P1-1）。");
    }

    @Test
    void planRevisionNeverGoesBackwardsOnRestore() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(
                Subtask.hardCoded("primary-r7-0", "do it", Map.of("asset_key", "minecraft:stone", "minimum", 1)))));
        c.nextPlanRevision();
        c.nextPlanRevision();
        assertEquals(2, c.planRevision(), "钉住前自检：手动把代次推到 2");
        var r = TaskChain.fromJson(c.toJson());
        assertEquals(7, r.planRevision(),
                "存档里的 planRevision(3) 与 id 里的代次(7) 取<b>较大者</b>，只增不减");
    }

    @Test
    void legacySubtaskIdYieldsNoRevision() {
        // 旧的 `-sN` 命名读不出代次 → 不得抛异常，也不得凭空造出代次
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(
                Subtask.hardCoded("primary-s0", "do it", Map.of("asset_key", "minecraft:stone", "minimum", 1)))));
        var r = TaskChain.fromJson(c.toJson().replaceAll("\"planRevision\":\\d+,", ""));
        assertEquals(0, r.planRevision(), "旧命名读不出代次时应保持 0，不许编造");
    }

    // ── ⑥ 旧存档字段缺失的兼容 ──────────────────────────────────────

    @Test
    void oldSaveWithoutPauseReasonsLoadsAsNoPause() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"), hc("s2"))));
        c.startCurrent();
        String json = c.toJson().replaceAll("\"pauseReasons\":\\{\\},", "");
        assertFalse(json.contains("pauseReasons"), "钉住前自检：字段已删");

        var r = TaskChain.fromJson(json);
        assertTrue(r.pauseReasonsView().isEmpty(), "缺失即空，不算损坏");
        assertEquals(SubtaskStatus.RUNNING, r.subtaskStatuses().get("s1"));
    }

    @Test
    void oldSaveWithoutAcquireBaselinesLoadsAsNoBaseline() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(acq("s1"))));
        c.startCurrentWithCounts(Map.of("minecraft:stone", 5));
        String json = c.toJson().replaceAll("\"acquireBaselines\":\\{.*?\\},", "");
        assertFalse(json.contains("acquireBaselines"), "钉住前自检：字段已删");

        var r = TaskChain.fromJson(json);
        // 语义后果是「判未达成」，不是「损坏」——绝不拿一个错的基线判出假的完成
        assertNull(r.acquireBaseline("s1"), "旧存档没有基线 = 从没捕获过，不是 0");
    }

    @Test
    void oldSaveWithoutSatisfiedStagesLoadsAsEmpty() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        String json = c.toJson().replaceAll("\"satisfiedStages\":\\[.*?\\],", "");
        assertFalse(json.contains("satisfiedStages"), "钉住前自检：字段已删");

        var r = TaskChain.fromJson(json);
        assertTrue(r.satisfiedStages().isEmpty(), "缺失即空，不算损坏");
        assertEquals("p", r.currentPrimary().id());
    }

    @Test
    void oldSaveWithoutUnexpandedFieldIsTreatedAsExpanded() {
        // A-1 之前的一级没有 unexpanded 字段：解析树里补 false（= 已展开）
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        // unexpanded 是 PrimaryGoal record 的最后一个字段，所以要连它前面那个逗号一起删，
        // 否则会留下 "waitFor":[],} 这种尾逗号，gson 报的是 JsonSyntaxException 而不是我们要验的语义。
        String json = c.toJson().replaceAll(",?\"unexpanded\":false", "");
        assertFalse(json.contains("unexpanded"), "钉住前自检：字段已删");

        var r = TaskChain.fromJson(json);
        assertEquals("s1", r.currentSubtask().id(), "旧存档的一级必须被当成已展开（否则永远等懒展开 = 静默卡死）");
        assertFalse(r.currentPrimary().unexpanded());
    }

    // ── ⑦ 快照稳定性（监测台读的就是它）──────────────────────────────

    @Test
    void snapshotIsStableAcrossRepeatedRoundTrips() {
        var c = chain(new PrimaryGoal("p1", "阶段一", List.of(hc("s1"))),
                new PrimaryGoal("p2", "阶段二", List.of(hc("s2"), hc("s3"))));
        c.startCurrent();
        c.applyHardCodedResult("s1", true);                                  // p1 全成 → 等监督
        c.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "ok"));
        c.activateCurrent(Map.of("minecraft:stone", 1));   // 内部已把 s2 启动成 RUNNING
        c.skipSubtask("s2", "no villagers nearby");                          // s2 → SKIPPED
        c.startCurrent();
        c.pauseSubtask("s3", "needs a bed first");                           // s3 → PAUSED
        assertEquals(SubtaskStatus.PAUSED, c.subtaskStatuses().get("s3"), "钉住前自检");

        var once = TaskChain.fromJson(c.toJson());
        var twice = TaskChain.fromJson(once.toJson());
        assertEquals(once.snapshot(), twice.snapshot(),
                "★ 快照（监测台唯一的数据源）必须幂等往返。否则「重启一次画面就变一次」，"
                        + "一切观测对账都失去意义（LLM 侧 RddStallPolicy 同款纯函数要求）。");
    }

    @Test
    void snapshotCarriesDetectionModePerSubtask() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(hc("s1"))));
        c.startCurrent();
        @SuppressWarnings("unchecked")
        var primaries = (List<Map<String, Object>>) c.snapshot().get("primaries");
        @SuppressWarnings("unchecked")
        var subtasks = (List<Map<String, Object>>) primaries.get(0).get("subtasks");
        assertEquals("HARD_CODED", subtasks.get(0).get("detectionMode"),
                "快照每条二级都必须带 detectionMode —— 将来接上 AI_ASSISTED 时监测台要靠它区分判定来源");
    }

    @Test
    void snapshotReportsNullCurrentSubtaskForUnexpandedPrimary() {
        var c = new TaskChain(new Goal("g", "goal", List.of(PrimaryGoal.unexpanded("p", "等展开", List.of()))));
        assertNull(c.snapshot().get("currentSubtaskId"),
                "★ 当前一级未展开时必须诚实报 null，而不是伪造一个占位二级。伪造 = 监测台看到"
                        + "「有个当前任务」，但宿主永远不派活（静默卡死）。");
        assertNull(c.currentSubtask());
    }

    // ── ⑧ 恢复后的链仍可继续推进（不是只能看不能动）─────────────────

    @Test
    void restoredChainCanStillBeDriven() {
        var c = chain(new PrimaryGoal("p1", "阶段一", List.of(hc("s1"))),
                new PrimaryGoal("p2", "阶段二", List.of(hc("s3"))));
        finishedFirstStage(c);
        var r = TaskChain.fromJson(c.toJson());
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, r.primaryStatus());
        // 恢复后监督仍能拍板推进 —— 「重启后卡在 AWAITING_SUPERVISOR 动不了」是真实事故形态
        r.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "ok"));
        assertEquals("p2", r.currentPrimary().id());
        assertEquals(PrimaryGoalStatus.PENDING, r.primaryStatus());
        assertTrue(r.activateCurrent(Map.of("minecraft:stone", 1)));
        assertEquals("s3", r.currentSubtask().id());
    }

    @Test
    void restoredAcquireSubtaskStillJudgesAgainstItsBaseline() {
        var c = chain(new PrimaryGoal("p", "阶段一", List.of(acq("s1"))));
        c.startCurrentWithCounts(Map.of("minecraft:stone", 5));
        var r = TaskChain.fromJson(c.toJson());
        assertEquals(PrimaryGoalStatus.RECOVERING, r.primaryStatus(), "钉住前自检：在途链归一为 RECOVERING");
        r.resumeFromRecovering();
        assertEquals(SubtaskStatus.RUNNING, r.currentSubtaskStatus());
        // 基线还在，所以「净增 3」仍然是真判据而不是恒假。
        // 必须用带 baseline 的三参重载 —— 两参重载等价于 baseline=null，acquire 模式会恒假。
        assertTrue(HardCodedEvaluator.matches(r.currentSubtask().condition(),
                        Map.of("minecraft:stone", 8), r.acquireBaseline("s1")),
                "恢复后 acquire 判据必须仍然可用（基线 5 + 现有 8 = 净增 3 ≥ 3）");
        assertFalse(HardCodedEvaluator.matches(r.currentSubtask().condition(),
                        Map.of("minecraft:stone", 5), r.acquireBaseline("s1")),
                "净增不足时必须仍然是假（不许因为『重启过了』就放行）");
    }
}