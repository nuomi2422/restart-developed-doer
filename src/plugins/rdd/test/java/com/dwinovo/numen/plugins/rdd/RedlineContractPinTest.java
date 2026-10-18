package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ★ 红线值钉死点 —— 把 {@code 30-主要功能不回退清单.md} 从「人工对照清单」变成「机器强制」。
 *
 * <h2>为什么这个类存在（2026-10-01 变异测试实测）</h2>
 *
 * <p>清单第 9 条要求「重构 / 拆解 / 搬迁之前先对照本清单」，但清单本身是给人读的，
 * <b>没有任何机器校验</b>。实测两个最要命的红线值在<b>全仓 1743 项测试里 0 次被断言</b>：
 *
 * <pre>
 *   变异 ①  RddPlugin.bodySubmissionEnabled  false → true
 *           语义 = 监控层可以主动提交身体任务，直接跟执行方抢方向盘
 *           结果 = gradlew test（全仓 12 模块）BUILD SUCCESSFUL，0 失败
 *
 *   变异 ②  RddPlugin.MAX_REPLAN_PER_PRIMARY  3 → 3000
 *           语义 = 重规划预算放大 1000 倍，按清单属于「无限重规划」破线
 *           结果 = :plugins:rdd:test  BUILD SUCCESSFUL，0 失败
 * </pre>
 *
 * <p><b>RL-1 / RL-9 是单驾驶员红线本体</b>（清单原文：「绝对不能跟执行方抢方向盘」），
 * 实现就是一个 boolean；<b>RL-5 是预算上限</b>，实现就是一个 int。
 * 改错了不会有异常、不会有日志、不会有任何测试变红——
 * <b>只有进游戏实机才可能发现，而实机回归脚本 {@code redline-regression.js} 还不自动化
 * （要 MC 在跑 + MCP 8765 + 同伴在线），且它的输出路径指向已废弃的 {@code rdd架构\特殊模式\}</b>。
 *
 * <h2>为什么 RddPlugin 的字段是「读源码」而不是「反射读字段」</h2>
 *
 * <p>{@code RddPlugin implements NumenPlugin}，而 {@code numen-plugin.gradle:42}
 * <b>故意</b>用 {@code compileOnly files(...apiJar)} 提供 api——那是「封死跨插件 import」
 * 的设计（见该文件 15~21 行注释）。{@code compileOnly} <b>不传播到测试运行期</b>，
 * 于是：
 *
 * <pre>
 *   写 RddPlugin.bodySubmissionEnabled()  → 编译期就报「无法访问 NumenPlugin」
 *   Class.forName("...RddPlugin")           → 运行期 NoClassDefFoundError
 * </pre>
 *
 * <p>也就是说<b>这个类的红线值在本模块测试里结构性地不可达</b>。
 * 干净解法是给 test 加 api jar，但那要改 {@code build.gradle}——
 * 而工程规矩禁止手写 gradle（唯一改码入口是 {@code run-mutation.ps1}）。
 * 所以这里退一步<b>读源码文本</b>：零构建改动、零生产改动，且对「钉一个常量」这个目的
 * 反而更直接（源码里写的就是契约本身）。
 *
 * <p>⚠️ 更干净的长期解法见 commit message：给 {@code plugins/rdd/build.gradle} 加
 * {@code testImplementation files(project(':api:neoforge').tasks.named('apiJar'))}，
 * 然后本类改成正常反射。走 {@code run-mutation.ps1 -Module plugins:rdd}。
 */
class RedlineContractPinTest {

    private static final String FQ_PATH = "com/dwinovo/numen/plugins/rdd/RddPlugin.java";

    // ── 定位 RddPlugin.java ────────────────────────────────────────
    // Gradle 的测试工作目录默认 = 插件项目目录（plugins/rdd）。
    // 保险起见逐级向上找，任何一级存在就返回。

    private static Path sourceOfRddPlugin() {
        String rel = "src/main/java/" + FQ_PATH;
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            Path p = cur.resolve(rel);
            if (Files.isRegularFile(p)) return p;
            // 跨到兄弟模块名（万一工作目录是仓库根而不是插件目录）
            Path viaPlugins = cur.resolve("plugins/rdd/" + rel);
            if (Files.isRegularFile(viaPlugins)) return viaPlugins;
        }
        return cwd.resolve(rel);
    }

    private static String readRddPluginSource() {
        Path p = sourceOfRddPlugin();
        assertTrue(Files.isRegularFile(p), "找不到 RddPlugin.java（试过 " + p.toAbsolutePath()
                + "）。如果模块挪了位置，请同步修本类的路径定位");
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读不了 " + p, e);
        }
    }

    /** 取出形如 {@code <decl> <name> = <value>;} 的那一行文本（用于报错时展示） */
    private static String declarationLine(String src, String name) {
        for (String line : src.split("\n")) {
            if (line.contains(name) && line.contains("=") && !line.trim().startsWith("//")
                    && !line.trim().startsWith("*") && !line.trim().startsWith("/*")) {
                return line.trim();
            }
        }
        return "(找不到声明)";
    }

    private static String literalOf(String src, String name) {
        Matcher m = Pattern.compile(
                "\\b" + Pattern.quote(name) + "\\s*=\\s*([A-Za-z0-9_.fFdDlL]+)\\s*;").matcher(src);
        if (!m.find()) {
            fail("找不到红线锚点 RddPlugin." + name + "（被改名或删除了？30-主要功能不回退清单.md 必须同步更新）");
            return null;
        }
        return m.group(1);
    }

    private static int intLiteralOf(String src, String name) {
        String lit = literalOf(src, name);
        if (lit == null) return 0;
        try {
            // 支持 3 / 3_000 / 180L * 20L 这类：先算纯算术表达式
            if (lit.matches("[0-9_]+[lL]?")) {
                return Integer.parseInt(lit.replace("_", "").replaceAll("[lL]$", ""));
            }
        } catch (NumberFormatException ignored) {
            // 落到表达式求值
        }
        fail("RddPlugin." + name + " = " + lit + " 不是纯整数字面量，本类只会钉纯数值；"
                + "若它是表达式，请改用可断言的形式或补一条表达式求值断言");
        return 0;
    }

    // ── RL-1 / RL-9 · 单驾驶员 ────────────────────────────────────
    // 清单原文：「监控 ≠ 规划 ≠ 执行」「绝对不能跟执行方抢方向盘」
    // 破线判据：日志出现「已提交身体任务」/ rdd-bodydispatch.flag 存在

    @Test
    void redline_singleDriver_bodySubmissionIsOffByDefault() {
        String src = readRddPluginSource();
        assertEquals("false", literalOf(src, "bodySubmissionEnabled"),
                "RL-1/RL-9 单驾驶员：监控层默认【不得】提交身体任务。"
                        + "要放开必须先在 30-主要功能不回退清单.md 里留下依据并同步改本行。"
                        + " 当前声明：" + declarationLine(src, "bodySubmissionEnabled"));
    }

    /** 跨线程读写（游戏主线程 / AI 线程），必须是 {@code volatile static}——这条本身也是契约。 */
    @Test
    void redline_singleDriver_switchIsRuntimeMutableNotCompileTimeConstant() {
        String src = readRddPluginSource();
        String decl = declarationLine(src, "bodySubmissionEnabled");
        assertTrue(decl.contains("volatile"),
                "bodySubmissionEnabled 必须 volatile（跨游戏主线程与 AI 线程），当前：" + decl);
        assertTrue(decl.contains("static"),
                "bodySubmissionEnabled 必须 static（全局单开关），当前：" + decl);
    }

    // ── RL-5 · 重规划预算 ────────────────────────────────────────

    @Test
    void redline_replanBudget_isThree() {
        String src = readRddPluginSource();
        assertEquals(3, intLiteralOf(src, "MAX_REPLAN_PER_PRIMARY"),
                "RL-5 重规划预算上限契约是每 primary 3 次。改它就是在改红线。"
                        + " 当前声明：" + declarationLine(src, "MAX_REPLAN_PER_PRIMARY"));
        assertEquals(3, intLiteralOf(src, "MAX_NEGOTIATION_REPLANS_PER_PRIMARY"),
                "协商重规划预算契约同样是 3 次。当前声明："
                        + declarationLine(src, "MAX_NEGOTIATION_REPLANS_PER_PRIMARY"));
    }

    // ── RL-16 · 同点连死 ─────────────────────────────────────────
    // 直接决定「先撤离补给再回收」这个 RL-7 分支会不会被触发

    @Test
    void redline_repeatDeathWindow_isPinned() {
        String src = readRddPluginSource();
        // 声明是 180L * 20L（表达式），本类只钉纯字面量，故只核对其首因子与形状
        assertTrue(declarationLine(src, "REPEAT_DEATH_WINDOW_TICKS").contains("180"),
                "重复死亡判定窗口契约是 180 秒。当前声明："
                        + declarationLine(src, "REPEAT_DEATH_WINDOW_TICKS"));
        assertEquals("8.0d", literalOf(src, "REPEAT_DEATH_RADIUS_BLOCKS"),
                "重复死亡判定半径契约是 8 格。当前声明："
                        + declarationLine(src, "REPEAT_DEATH_RADIUS_BLOCKS"));
    }

    // ── 守护默认开关（RL-10 / RL-17 的前提）──────────────────────

    @Test
    void supervisionAndPause_defaultsAreOn() {
        String src = readRddPluginSource();
        assertEquals("true", literalOf(src, "supervisionEnabled"),
                "监督默认开启；关掉它等于让 RL-10「主人待命不可被系统推动」失去执行者。"
                        + " 当前声明：" + declarationLine(src, "supervisionEnabled"));
        assertEquals("true", literalOf(src, "pauseEnabled"),
                "PAUSE 默认开启（RL-17：PAUSE 可运行时关闭，关闭时不得按住）。"
                        + " 当前声明：" + declarationLine(src, "pauseEnabled"));
    }

    // ── M2 · 懒展开预算（Stage-B 不得无限展开）─────────────────────
    // 这两个类不实现 NumenPlugin，可直接引用，可读性优先于读源码

    @Test
    void redline_lazyExpansionBudget_isPinned() {
        assertEquals(3, RddExpansionPolicy.MAX_EXPAND_ATTEMPTS, "懒展开尝试次数契约是 3");
        assertEquals(5_000L, RddExpansionPolicy.EXPAND_COOLDOWN_MS, "懒展开冷却契约是 5000ms");
    }

    // ── M3 · 空转判定阈值 ────────────────────────────────────────
    // 36 号文档实测：卡住 35.6s < 有效 44.0s —— 任何纯时间阈值都区分不了「卡住」与「慢」。
    // 所以这几个数是「宽限上限」而不是「精准判据」，收紧需重新实测。

    @Test
    void redline_stallThresholds_arePinned() {
        assertEquals(15, RddStallPolicy.IDLE_GRACE_CHECKS, "空闲宽限契约是 15 次检查");
        assertEquals(120, RddStallPolicy.WORK_GRACE_CHECKS, "作业中宽限契约是 120 次检查");
        assertEquals(10, RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS, "LLM 空闲轻推契约是 10 次");
        assertEquals(3, RddStallPolicy.LLM_NO_TOOLCALL_RESPONSES_BEFORE_NUDGE,
                "连续无 tool_call 响应几次后轻推，契约是 3");
    }

    /** 护栏：轻推点必须早于宽限上限，否则两条规则自相矛盾（等宽限到了才轻推）。 */
    @Test
    void stallThresholds_keepNudgeAfterGrace() {
        assertTrue(RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS < RddStallPolicy.IDLE_GRACE_CHECKS,
                "轻推点必须早于空闲宽限上限。当前 nudge="
                        + RddStallPolicy.LLM_IDLE_NUDGE_AFTER_CHECKS
                        + " idleGrace=" + RddStallPolicy.IDLE_GRACE_CHECKS);
    }

    /** 本类自身可用性自检：万一路径定位失效，至少要报「找不到文件」而不是静默放过。 */
    @Test
    void redlinePinMechanismItselfWorks() {
        String src = readRddPluginSource();
        assertNotNull(src);
        assertTrue(src.length() > 1000, "读到的 RddPlugin.java 明显不完整（" + src.length() + " 字符）");
        List<String> lines = List.of(src.split("\n"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("bodySubmissionEnabled")),
                "源码里竟找不到 bodySubmissionEnabled —— 钉死机制本身可能已失效");
    }
}