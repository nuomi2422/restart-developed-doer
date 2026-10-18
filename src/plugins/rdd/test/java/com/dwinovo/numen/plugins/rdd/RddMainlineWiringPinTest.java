package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T7 · 主链路接线钉死（M3 / M4 / M5 —— 本次最核心的一个类）。
 *
 * <h2>为什么必须读源码而不写行为测试</h2>
 *
 * <p>改动前的事实（2026-10-02 全仓 grep 确认）：
 * {@code RddDetector} / {@code tickRuntime} / {@code completeSubtask} / {@code conditionMatches}
 * <b>在任何测试里一次都没出现过</b>；{@code recordStageFact} 也只在主源码里被提及，
 * 存储层另有 {@code CompletedFactStoreTest}，但<b>没有任何测试证明它们被接线了</b>。
 *
 * <p>而 2026-10-01 的变异实测已经证明这类缺口有多致命：
 * 把 {@code bodySubmissionEnabled} 从 false 改成 true、把 {@code MAX_REPLAN_PER_PRIMARY}
 * 从 3 改成 3000 —— <b>全仓 1743 项测试 0 个失败</b>。
 *
 * <p>更要命的是：这些是<b>接线</b>，不是数值。
 * 数值钉不住可以改成别的数，接线被挪掉/删掉/调换顺序，
 * <b>编译照过、单测全绿、运行时不报任何错</b> —— 只是链会静默卡死、或者提前宣布完成。
 * 行为测试在这里做不到（要么进游戏，要么把 Minecraft 类全部 mock 出来），
 * 所以本类走 {@link RddToolContractSnapshotTest} 同款的路：<b>读源码文本，断言关键语句的位置关系</b>。
 *
 * <h2>它钉住的三条接线（M3 / M4 / M5）</h2>
 * <pre>
 *   M3 tickRuntime 主干顺序：协商 → 卡死监督 → 判定 → 完成 → 重试
 *   M4 completeSubtask：服务端判定回填 → 落事件 → 记事实 → 仅在等监督时拍 CONFIRM → 记阶段事实
 *   M5 finishResolvedPrimary：optional 跳过路径上的第二条 M4 接线
 * </pre>
 *
 * <h2>边界声明</h2>
 *
 * <p>本类<b>不新增能力、不改行为</b>。它只是把现状写成断言。
 * 如果哪天真的要接线 AI_ASSISTED，那必须走独立 Stage 并同时改本类 ——
 * {@code SubtaskModeContractTest} 里的死代码扫描会在那一刻变红提醒你。
 *
 * <p>零生产改动、零构建改动、零 Minecraft。
 */
class RddMainlineWiringPinTest {

    // ── 读源码 + 抽方法体 ───────────────────────────────────────────

    private static Path pluginSourceDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            Path src = cur.resolve("src/main/java/com/dwinovo/numen/plugins/rdd");
            if (Files.isDirectory(src)) return src;
        }
        return cwd.resolve("src/main/java/com/dwinovo/numen/plugins/rdd");
    }

    private static Path rddCoreSourceDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            Path src = cur.resolve("src/main/java/com/dwinovo/numen/rdd");
            if (Files.isDirectory(src)) return src;
        }
        return cwd.resolve("src/main/java/com/dwinovo/numen/rdd");
    }

    private static String read(Path dir, String fileName) {
        Path p = dir.resolve(fileName);
        assertTrue(Files.isRegularFile(p), "读不到源文件：" + p);
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("读源文件失败：" + p + " → " + e);
            return "";
        }
    }

    private static String detector() {
        return read(pluginSourceDir(), "RddDetector.java");
    }

    private static String plugin() {
        return read(pluginSourceDir(), "RddPlugin.java");
    }

    /** 从 {@code from '{'} 起做括号配平，返回配对 '}' 之后的下标。 */
    private static int braceEnd(String src, int fromIndex) {
        int depth = 0;
        for (int i = fromIndex; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        fail("括号没配平（从 " + fromIndex + " 起）");
        return -1;
    }

    /**
     * 抽出某个方法的完整方法体（不含签名）。
     *
     * <p>定位方式：找形如 {@code <Type> name(} 的<b>声明</b>位置 ——
     * 排除前面是 {@code .}（调用点）与 {@code new }（构造点）的匹配，
     * 再从签名后的第一个 {@code &#123;} 做括号配平。
     *
     * @param mustContain 抽到的这个方法体必须含有它；用来在有重载时锁定正确的那个
     */
    private static String methodBody(String src, String methodName, String mustContain) {
        Matcher m = Pattern.compile("(\\b" + Pattern.quote(methodName) + "\\s*\\()").matcher(src);
        while (m.find()) {
            int nameStart = m.start(1);
            // 排除调用点 / 构造点
            int i = nameStart - 1;
            while (i >= 0 && Character.isWhitespace(src.charAt(i))) i--;
            if (i >= 0 && (src.charAt(i) == '.' || src.charAt(i) == ')')) continue;
            String before = src.substring(Math.max(0, i - 6), i + 1);
            if (before.endsWith("new ")) continue;

            // 从 '(' 之后找到第一个 '{'
            int open = src.indexOf('{', m.end(1));
            if (open < 0) continue;
            int close = braceEnd(src, open);
            String body = src.substring(open + 1, close);
            if (mustContain == null || body.contains(mustContain)) {
                return body;
            }
        }
        fail("在源码里找不到方法 " + methodName
                + (mustContain == null ? "" : "（含 '" + mustContain + "' 的那个重载）"));
        return "";
    }

    /** 断言 methodBody 的先后顺序；任一 needle 缺失时给出点名报错。 */
    private static void assertOrder(String body, String... needlesInOrder) {
        int prev = -1;
        String prevName = null;
        for (String needle : needlesInOrder) {
            int at = body.indexOf(needle, prev + 1);
            assertTrue(at >= 0, "方法体里找不到 '" + needle + "'（在 '" + prevName + "' 之后）—— "
                    + "这一段接线被挪走或删掉了");
            assertTrue(at > prev, "'" + needle + "' 必须出现在 '" + prevName + "' 之后");
            prev = at;
            prevName = needle;
        }
    }

    private static void assertContains(String body, String needle, String why) {
        assertTrue(body.contains(needle), why + " —— 找不到 '" + needle + "'");
    }

    private static void assertNotContains(String body, String needle, String why) {
        assertFalse(body.contains(needle), why + " —— 不该出现 '" + needle + "'");
    }

    /** 正则首次命中的下标；未命中返 -1。用于对写法差异（如是否带 {@code DetectionMode.} 前缀）保持宽容。 */
    private static int regexFirst(String body, String regex) {
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.start() : -1;
    }

    // ── 机制自检（放在最前，先证明这套读法有效）───────────────────────

    @Test
    void extractionMechanismItselfWorks() {
        assertTrue(detector().contains("class RddDetector"), "自检：必须真的读到 RddDetector 源码");
        String tick = methodBody(detector(), "tickRuntime", "tickNegotiation");
        assertTrue(tick.length() > 500, "自检：tickRuntime 方法体长度应远超 500 字符，实际 " + tick.length());
        // onServerTick 只有一个重载，用它验括号配平
        String onTick = methodBody(detector(), "onServerTick", "RddCarryHint.refresh");
        assertTrue(onTick.contains("saveRuntimes") && onTick.contains("restoreRuntimes"),
                "自检：onServerTick 方法体必须同时含 restoreRuntimes 与 saveRuntimes（说明抓对了方法）");
        // 排除调用点：从 body 里看，tickRuntime 内部不该出现「.tickRuntime(」
        assertNotContains(tick, ".tickRuntime(", "自检：不应把调用点当成声明点");
    }

    // ═══════════════════════════════════════════════════════════════
    //  M3 · tickRuntime 主干顺序
    // ═══════════════════════════════════════════════════════════════

    @Test
    void m3_negotiationIsHeardBeforeAnyVerdict() {
        String tick = methodBody(detector(), "tickRuntime", "tickNegotiation");
        assertOrder(tick,
                "tickNegotiation(ap, rt, chain)",
                "conditionMatches(");
        // 更强的形式：协商命中时必须立刻 return，后面的一切判定本轮都不该跑
        int neg = tick.indexOf("tickNegotiation(ap, rt, chain)");
        int guard = tick.indexOf("return;", neg);
        assertTrue(guard > neg && guard < tick.indexOf("conditionMatches("),
                "★ tickNegotiation 命中后必须立刻 return（否则 AI 的 REJECT/PAUSE 会被同一 tick 的"
                        + "服务端判定盖掉 —— 模型刚说「这单做不了」，链就把它判完成了）。"
                        + "现状顺序：协商后第一个 return 在 " + guard
                        + "，conditionMatches 在 " + tick.indexOf("conditionMatches("));
    }

    @Test
    void m3_stallSupervisionRunsBeforeVerdict() {
        String tick = methodBody(detector(), "tickRuntime", "stallWatcher.track");
        assertOrder(tick,
                "stallWatcher.track",
                "conditionMatches(",
                "completeSubtask(");
        assertOrder(tick, "conditionMatches(", "completeSubtask(", "maybeRetryOrFail(");
    }

    @Test
    void m3_aiAssistedIsStillHandedBackToTheModel() {
        String tick = methodBody(detector(), "tickRuntime", "tickNegotiation");
        // 必须连着 return 一起匹配，否则会误抓 EarlyAchievement 循环里那两个 break（:440/:449）。
        Matcher m = Pattern.compile(
                "detectionMode\\(\\)\\s*!=\\s*(?:DetectionMode\\.)?HARD_CODED\\s*\\)\\s*\\{\\s*return;").matcher(tick);
        assertTrue(m.find(),
                "★ AI_ASSISTED 二级必须继续「交还 AI、不做卡死监督」。找的是形如 "
                        + "`if (current.detectionMode() != HARD_CODED) { return; }` 的那段。删掉它的后果是："
                        + "将来接上 AI_ASSISTED 后，服务端会对一条本该等模型回执的二级"
                        + "跑一遍硬编码的卡死监督 → 15 秒后判停滞 → 逼模型交卷");
        int handoff = m.start();
        // 它必须 return 而不是 break —— 交还意味着本轮后面的调度全部不跑（这已由上面的正则保证）。
        // 且必须早于本轮真正的服务端判定与卡死监督 —— 否则「交还」只是一句注释。
        // ⚠️ 这里必须用 lastIndexOf：tickRuntime 里 FAILED 分支（:420）也会调 conditionMatches，
        //    它是「失败后重新验一次」，不是本轮的判定出口。
        assertTrue(handoff < tick.indexOf("stallWatcher.track"),
                "★ 「交还 AI」必须早于卡死监督。否则 AI_ASSISTED 二级会被服务端按硬编码节奏判停滞");
        assertTrue(handoff < tick.lastIndexOf("conditionMatches("),
                "★ 「交还 AI」必须早于本轮的服务端判定（" + handoff + " vs " + tick.lastIndexOf("conditionMatches(")
                        + "）。否则它形同虚设：tick 照跑后面的调度，等于没交还");
    }

    @Test
    void m3_rddRuntimeIsRefreshedBeforeTheNullCheck() {
        String onTick = methodBody(detector(), "onServerTick", "RddCarryHint.refresh");
        assertOrder(onTick,
                "RddPlugin.restoreRuntimes()",
                "RddPlugin.cacheInventory(",
                "RddCarryHint.refresh(",
                "RddPlugin.runtime(",
                "if (rt == null)");
        assertContains(onTick, "saveRuntimes", "onServerTick 收尾必须落盘（RL-15：状态必须活得过重启）");
    }

    // ═══════════════════════════════════════════════════════════════
    //  M4 · completeSubtask —— 判定回流 + 事实记录 + 阶段收口
    // ═══════════════════════════════════════════════════════════════

    private static String completeSubtaskBody() {
        return methodBody(detector(), "completeSubtask", "recordStageFact");
    }

    @Test
    void m4_serverVerdictIsWrittenBackFirst() {
        String body = completeSubtaskBody();
        assertOrder(body,
                "surplus.hold(",
                "rt.applyHardCoded(current.id(), true)",
                "!completed");
        assertContains(body, "if (!completed",
                "★ 必须检查 applyHardCoded 的返回值：为 false 说明这次结果没被状态机接受"
                        + "（陈旧 id / 模式不符），此时必须就此打住，不许继续记事件与推进阶段");
    }

    @Test
    void m4_eventIsPublishedWithARealEvidenceSource() {
        String body = completeSubtaskBody();
        assertOrder(body, "!completed", "RddMonitor.publish(\"subtask_completed\"");
        assertContains(body, "\"evidenceSource\"",
                "★ 事件必须带证据来源（RL-13：不做假承诺）。没有来源字段 = 观测端无法区分"
                        + "「服务端读世界」与「读背包」，也就无法判断这次判定可不可信");
        assertContains(body, "WorldFactConditions.knownType(",
                "★ 证据来源必须由判据类型分流：世界事实走 server_world_fact，背包走 server_inventory。"
                        + "写死成一个常量等于宣称「所有完成都是服务端判定的」——那就是假承诺");
    }

    @Test
    void m4_factIsRecordedBeforeTheStageIsConfirmed() {
        String body = completeSubtaskBody();
        assertOrder(body,
                "RddPlugin.recordSubtaskFact(",
                "PrimaryGoalStatus.AWAITING_SUPERVISOR",
                "rt.applySupervisor(",
                "RddPlugin.recordStageFact(",
                "RddMonitor.publish(\"goal_completed\"");
    }

    @Test
    void m4_stageConfirmationIsGuardedByAwaitingSupervisor() {
        String body = completeSubtaskBody();
        assertContains(body, "if (chain.primaryStatus() == PrimaryGoalStatus.AWAITING_SUPERVISOR)",
                "★ 只有当一级已经「所有二级都满足、正等监督拍板」时，才允许在这里直接 CONFIRM。"
                        + "去掉这个守卫的后果：链还在跑中间步骤时，每完成一个二级就抢跑一次拍板，"
                        + "链会跳过后面的步骤直接进下一个阶段 —— 这是无声的任务丢失");
    }

    @Test
    void m4_completedEventIsPublishedOnlyAfterRealConfirmation() {
        String body = completeSubtaskBody();
        int confirmed = body.indexOf("rt.applySupervisor(");
        int goalDone = body.indexOf("RddMonitor.publish(\"goal_completed\"");
        int guard = body.indexOf("PrimaryGoalStatus.AWAITING_SUPERVISOR");
        assertTrue(guard < confirmed && confirmed < goalDone,
                "★ 「goal_completed」事件必须发在「真的确认了」之后。顺序反了 = 监测台会显示"
                        + "阶段已完成而链其实还在跑（观测面板撒谎比链卡死更难查）");
    }

    @Test
    void m4_bodyIsClearedOnBothExitPaths() {
        String body = completeSubtaskBody();
        int cleared = body.indexOf("RddPlugin.clearBody");
        assertTrue(cleared >= 0, "完成路径必须清掉派活状态（否则下一轮还会重复派同一个活）");
        assertTrue(body.lastIndexOf("RddPlugin.clearBody") > body.indexOf("RddMonitor.publish(\"goal_completed\""),
                "收口之后还要再清一次（连续跳级时第一轮的清理会漏掉新派出去的活）");
    }

    // ═══════════════════════════════════════════════════════════════
    //  M5 · finishResolvedPrimary —— optional 跳过路径上的第二条接线
    // ═══════════════════════════════════════════════════════════════

    @Test
    void m5_optionalSkipPathAlsoConfirmsAndRecords() {
        String body = methodBody(plugin(), "finishResolvedPrimary", "recordStageFact");
        assertOrder(body,
                "PrimaryGoalStatus.AWAITING_SUPERVISOR",
                "applySupervisor(",
                "recordStageFact(",
                "\"primary_resolved\"");
        assertContains(body, "verified required steps; optional steps may be skipped",
                "★ 这条 reason 文案是「哪些必做步骤已被验证、哪些可选项被跳过」的对外声明。"
                        + "改掉它 = 主人读到的是一句无法判断真伪的话（RL-13）");
    }

    @Test
    void m5_recordStageFactPersistsToDisk() {
        String body = methodBody(plugin(), "recordStageFact", "RddFactStore.save");
        assertContains(body, "recordStage(", "必须真的写一条阶段记录");
        assertContains(body, "RddFactStore.save(",
                "★ 必须落盘。改动前 recordStageFact 只在内存里 recordSubtaskFact 而不 save —— "
                        + "阶段完成事实一重启就全丢（RL-15：凡状态必须活得过重启）");
    }

    @Test
    void m5_recordSubtaskFactIsExplicitlyInMemoryOnly() {
        // 这不是缺陷，是刻意分层（子目标事实量大、一轮多次写盘不值）。
        // 但它必须被显式记下来，否则下一个人会「顺手补上 save」而写爆磁盘。
        String body = methodBody(plugin(), "recordSubtaskFact", null);
        assertNotContains(body, "RddFactStore.save(",
                "recordSubtaskFact 刻意不落盘（每轮可能写十几次）。若这里出现 save，"
                        + "说明有人「顺手统一」了 —— 请先确认写入频率");
        assertContains(body, "recordSubtask(",
                "但它必须真的写进内存的事实存储，否则这一层的记录等于没有");
    }

    // ═══════════════════════════════════════════════════════════════
    //  判定真身分流（扩判据时的三处同步检查点）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void conditionMatchesRoutesToBothRealSourcesAndNoOther() {
        String body = methodBody(detector(), "conditionMatches", "HardCodedEvaluator");
        assertContains(body, "WorldFactConditions.valid(",
                "世界事实类条件必须先过 schema 校验");
        assertContains(body, "RddWorldFacts.matches(",
                "世界事实类条件必须落到 RddWorldFacts 这个真身（读世界的那一份）");
        assertContains(body, "HardCodedEvaluator.matches(",
                "背包类条件必须落到 HardCodedEvaluator（读背包的那一份）");
        assertNotContains(body, "DetectionMode.AI_ASSISTED",
                "★ 判定真身里目前<b>没有</b> AI 分支 —— 这是「AI_ASSISTED 未接线」的现状。"
                        + "变红 = 有人接上了 AI 判定，那必须走独立 Stage 并同时改"
                        + "SubtaskModeContractTest（它会先变红提醒你）");
        assertNotContains(body, "applyAiAssistedResult(",
                "conditionMatches 是<b>服务端读世界的真身</b>，绝不允许把模型的回执当成判定结果"
                        + "（RL-11：不得把估计当确认）");
    }

    // ═══════════════════════════════════════════════════════════════
    //  暂停与陈旧回执（两条已被实机抓到过的坑）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void ownerPauseNeverExpires() {
        String body = methodBody(detector(), "handlePaused", "isOwnerPause");
        int ownerCheck = body.indexOf("isOwnerPause(");
        int recheck = body.indexOf("PAUSE_RECHECK_TICKS");
        assertTrue(ownerCheck >= 0 && ownerCheck < recheck,
                "★ 主人暂停必须<b>先</b>判定并直接 return，早于 60 秒复评。否则主人在游戏里说的"
                        + "「原地待命」会在 60 秒后被当成士兵自己的 PAUSE 而强行拉去干活"
                        + "（深审 R04 的定论：主人指令不是故障）");
    }

    @Test
    void pauseRecheckAlwaysClearsThePausedMark() {
        String body = methodBody(detector(), "handlePaused", "PAUSE_RECHECK_TICKS");
        assertContains(body, "clearPaused",
                "复评后必须清掉暂停起点。不能借暂停起点当催促冷却，否则同一个 PAUSE 会"
                        + "在第二次之后永远不再触发复评（深审 R03 已修正过一次，别退回去）");
    }

    @Test
    void staleNegotiationIsDiscardedWithAnEvent() {
        String body = methodBody(detector(), "tickNegotiation", "negotiation_discarded");
        assertContains(body, "negotiation_discarded",
                "★ 任务号与当前二级不符的回执必须<b>连同事件一起丢弃</b>。静默丢弃 = 观测端看不到"
                        + "「有一条回执被扔了」，于是问题变成无法解释的「AI 明明说了却不生效」");
        assertContains(body, "stale subtask id",
                "丢弃理由必须写明是过期任务号（模型能据此自我纠正）");
        assertContains(body, "takePending",
                "★ 丢弃时必须 takePending 消费掉，否则这条脏回执会一直留在 PENDING 里，"
                        + "下一轮又被判一次过期 —— 变成一个每 tick 都发的噪声事件");
    }

    // ═══════════════════════════════════════════════════════════════
    //  兜底闸：三个开关的默认值不在这两个文件里，但监督闸必须还在
    // ═══════════════════════════════════════════════════════════════

    @Test
    void supervisionGateStillShortCircuitsTheNudge() {
        String body = methodBody(plugin(), "nudge", "supervisionEnabled");
        int gate = body.indexOf("supervisionEnabled");
        int enqueue = body.indexOf("numenApi.enqueue");
        assertTrue(gate >= 0 && gate < enqueue,
                "★ supervisionEnabled 闸必须在 enqueue 之前判。这是「关掉监督就不再打扰同伴」"
                        + "的唯一保证；把它挪到 enqueue 之后 = 开关形同虚设（关了照样催）");
    }

    @Test
    void theWholePinSetStillFindsTheRealFiles() {
        List<String> found = new ArrayList<>();
        for (String f : List.of("RddDetector.java", "RddPlugin.java")) {
            if (Files.isRegularFile(pluginSourceDir().resolve(f))) found.add(f);
        }
        assertEquals(List.of("RddDetector.java", "RddPlugin.java"), found,
                "★ 本类依赖的两个源文件必须都在原位。它们若被重命名/移动，本类会大面积 vacuous "
                        + "fail —— 这时应该更新本类的定位方式，而不是删掉本类");
        assertTrue(Files.isDirectory(rddCoreSourceDir()) || true,
                "rdd-core 源目录定位（当前不用于断言，保留以便将来扩展）");
    }
}