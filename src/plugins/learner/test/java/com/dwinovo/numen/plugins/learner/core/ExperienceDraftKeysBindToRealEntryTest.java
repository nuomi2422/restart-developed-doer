package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 把 {@code ExperienceDraft.ENTRY_KEYS} 与<b>真实的</b> {@code ExperienceEntry.toJson()} 绑在一起。
 *
 * <p><b>为什么必须读源文件而不是各写一份列表</b>（Codex 审核 P1-1 之后才补的）：
 * {@code plugins/learner} <b>刻意不依赖</b> {@code experience-core} —— 两个 mod
 * 各带一份同名类会出 split-package / JPMS 问题，所以两边没有编译期绑定。
 * 只靠自己抄的 {@code ENTRY_KEYS} 跟自己抄的草稿做断言 = <b>自己验自己</b>，
 * 那边改键名这边不会红。</p>
 *
 * <p>所以这里直接读 {@code ExperienceEntry.java} 的源，把 {@code toJson()} 方法体里
 * {@code addProperty("x", …)} / {@code add("x", …)} 的键抠出来，做<b>集合相等</b>断言
 * （相等，不是包含 —— 包含会让多出来的键永远查不出来）。
 * 抠不出来（方法体改名/重构）时<b>失败</b>，不跳过 —— 跳过等于这道门形同虚设。</p>
 */
class ExperienceDraftKeysBindToRealEntryTest {

    private static final Path ENTRY_SOURCE = Paths.get(
            "..", "..", "experience-core", "src", "main", "java",
            "com", "dwinovo", "numen", "experience", "api", "ExperienceEntry.java");

    /** 从源码里抠 {@code toJson()} 的方法体。 */
    private static String toJsonBody(String src) {
        int sig = src.indexOf("public JsonObject toJson()");
        if (sig < 0) {
            fail("ExperienceEntry 里找不到 public JsonObject toJson() —— 源文件结构变了，"
                    + "这道门必须跟着改，不能悄悄放过");
        }
        int open = src.indexOf('{', sig);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open + 1, i);
                }
            }
        }
        return fail("toJson() 的方法体括号不配平 —— 抠不出来，这道门失效了");
    }

    /** 抠出 {@code addProperty("k"} 与 {@code add("k"} 的全部键。 */
    private static Set<String> keysInToJson() throws IOException {
        Path abs = ENTRY_SOURCE.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            return fail("找不到 ExperienceEntry 源文件：" + abs
                    + " —— 说明测试的工作目录假设变了，必须修，不能跳过");
        }
        String src = Files.readString(abs, StandardCharsets.UTF_8);
        String body = toJsonBody(src);
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("add(?:Property)?\\(\\s*\"([a-z_]+)\"").matcher(body);
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertTrue(keys.size() >= 10, "只抠到 " + keys.size() + " 个键，正则多半失效了：" + keys);
        return keys;
    }

    @Test
    void entryKeysMatchTheRealExperienceEntryToJson() throws Exception {
        Set<String> real = keysInToJson();
        // toJson() 额外写 v / id —— 那是记录层的序列化头，不属于「草稿能填的字段」
        real.remove("v");
        real.remove("id");

        List<String> declared = new ArrayList<>(ExperienceDraft.ENTRY_KEYS);
        List<String> actual = new ArrayList<>(real);

        assertEquals(actual.size(), declared.size(),
                "键数量不一致 —— 经验库加了字段而学习者没跟（或反之）。"
                        + "\n  真实 toJson(): " + actual + "\n  ENTRY_KEYS   : " + declared);
        for (String k : actual) {
            assertTrue(declared.contains(k),
                    "经验库的 toJson() 有键 " + k + "，ENTRY_KEYS 里没有 ⇒ "
                            + "学习者产的草稿可能漏填它，且没人会发现");
        }
        for (String k : declared) {
            assertTrue(actual.contains(k),
                    "ENTRY_KEYS 声明了 " + k + "，但 ExperienceEntry.toJson() 并不写它 ⇒ 写进去也读不出来");
        }
    }

    /** 反向：映射承诺会写的键，必须都是真实存在的键。 */
    @Test
    void writtenKeysAreAllRealEntryKeys() throws Exception {
        for (String k : ExperienceDraft.WRITTEN_KEYS) {
            assertTrue(ExperienceDraft.ENTRY_KEYS.contains(k),
                    "WRITTEN_KEYS 里的 " + k + " 不在 ENTRY_KEYS 里，两处自相矛盾");
        }
    }

    // ============================================================
    // 值通路：草稿写的每个键都必须能被**唯一落库口**接受（2026-10-04 补）
    // ============================================================

    /**
     * <b>草稿写的每一个键，都必须在唯一落库口的 schema 里。</b>
     *
     * <h2>这道门补的是一个已经真实发生过的数据丢失</h2>
     * <p>2026-10-04 之前，本类的 {@link #entryKeysMatchTheRealExperienceEntryToJson()} 是绿的、
     * {@code ExperienceDraftGateTest} 那十几条也是绿的、{@code ExperienceStore.merge()} 的
     * 「合并不抹七字段」也是绿的 —— 而 live 经验库里<b>没有一条七项填齐</b>。
     *
     * <p>原因不在这些测试覆盖的那一层：{@code ExperienceDraft} 把七个槽位的<b>原值</b>写进了
     * {@code experience_draft.entry}，但草稿通往 store 的<b>唯一生产通道</b>
     * {@code ExperienceLearnTool} 的 {@code parameterSchema()} 与 {@code Input} record
     * <b>都不收这七项</b>。于是 AI 把它看到的那份 JSON 原样转手调过去时，
     * {@code Gson.fromJson(args, Input.class)} <b>静默丢弃</b>多出来的字段，
     * 落库的条目七个槽位全是空串。<b>三处测试全绿，两端都不报错。</b>
     *
     * <h2>为什么放在 learner 侧（而不是 experience 侧）</h2>
     * <p>{@code ExperienceLearnTool} 依赖 {@code NumenTool}，而 {@code numen-plugin.gradle:42}
     * 只用 {@code compileOnly} 给瘦 api jar —— {@code plugins/experience} 的测试 classpath
     * 里<b>没有</b> {@code NumenTool}，写行为测试会 {@code NoClassDefFoundError}
     * （本仓已有的 {@code RedlineContractPinTest} javadoc 记录了同一结论）。
     * 而 learner 侧的测试 classpath 里<b>有</b> 那个 api jar（{@code plugins/learner/build.gradle:35}），
     * 且本仓已有读源文件绑契约的成熟范式（就是本类）⇒ 只能、也只该在这里钉。
     *
     * <h2>⚠️ 这道门有一个已知的「本地可能不重跑」窗口（2026-10-04 变异时实测到）</h2>
     * <p>本测试读的是<b>另一个模块</b>的源文件，而 gradle 不知道这件事 ——
     * 改 {@code ExperienceLearnTool.java} 只会让 {@code :plugins:experience:compileJava} 重跑，
     * {@code :plugins:learner:test} 的输入没变 ⇒ <b>它会被判 UP-TO-DATE 而根本不执行</b>。
     * 变异验证时我因此拿到过一次「改了缺陷却 EXIT=0」的假绿。
     * <b>在 CI（全新 checkout）上不存在这个问题</b>（任务必然执行），
     * 但本地只改 experience 侧就想看这道门，必须显式 {@code :plugins:learner:test --rerun}。
     * 这里写出来而不是只记在 commit 里，是因为下一次有人遇到同样的假绿时，
     * 答案就在这道门自己的 javadoc 里。
     *
     * <h2>为什么还要钉 {@code Input} record 与 builder 调用（不止 schema）</h2>
     * <p>schema 收了、{@code Input} record 没收 ⇒ 同样静默丢弃；
     * {@code Input} 收了、builder 没搬 ⇒ 也是静默丢弃（Gson 对多出来的字段从不报错）。
     * <b>三处必须同时在位，而编译期没有任何东西能保证这件事。</b>
     */
    /**
     * 草稿写 {@code maturity}、但落库口<b>刻意不收</b>它 —— 这不是缺口，是设计，
     * 而本类必须把这件事钉成契约，否则「补上它」看起来像修复。
     *
     * <p>{@code ExperienceDraft} 硬写 {@code maturity="OBSERVED"}（草稿 :258，
     * 注释引 59 号 D5「刚产出的未验证」）。若把这个键加进 {@code experience_learn} 的 schema，
     * 一条<b>刚写出来</b>的经验就能被直接指定成 {@code VERIFIED}/{@code GENERALIZED} ——
     * 那正是 69 号第 3 组红线「展示≠采用」「经验不被错误强化」要挡的事。
     *
     * <p>所以正确做法是：<b>不收</b>，靠 {@code ExperienceEntry.Builder} 的默认值
     * （{@code maturity = OBSERVED}）得到同一个结果。AI 把它看到的 {@code maturity} 转发过来时，
     * 那个字段被丢掉，条目仍然是 OBSERVED —— <b>结果一致，但一致是「被默认值兜住的」，
     * 不是「被显式接受的」</b>。
     *
     * <p>⇒ 本条同时断言两件事：schema 里<b>没有</b> {@code maturity}；
     * 且工具的 builder 调用<b>没有</b> {@code .maturity(} ——
     * 后者若被加上，就是把「不收」悄悄改成「收」的那条路，必须红。
     */
    @Test
    void theDraftsMaturityIsDeliberatelyRefusedRatherThanAccepted() throws Exception {
        assertTrue(ExperienceDraft.WRITTEN_KEYS.contains("maturity"),
                "自检：草稿仍然写 maturity（本条钉的是「落库口不收它」，不是「草稿不写它」）");
        String toolSrc = learnToolSource();
        assertFalse(keysInSchema(toolSrc).contains("maturity"),
                "★ experience_learn 不该收 maturity：一条刚写出来的经验能被直接指定成熟度，"
                        + "等于绕过质量门与验证次数（D16 默认 3 次真实成功）。"
                        + "若确实要开放，先想清楚「谁来证明这次成功」—— 那不是本工具的参数问题。");
        assertFalse(spanTo(toolSrc, "ExperienceEntry entry = ExperienceEntry.builder()", ".build();")
                        .contains(".maturity("),
                "★ builder 调用里不许出现 .maturity( —— 那条路会让 schema 不收也照样把成熟度写进去，"
                        + "比直接加进 schema 更隐蔽");
    }

    @Test
    void everyKeyTheDraftWritesIsAcceptedByTheOnlyDoorThatPersistsIt() throws Exception {
        String toolSrc = learnToolSource();
        Set<String> schemaKeys = keysInSchema(toolSrc);

        List<String> missing = new ArrayList<>();
        for (String k : ExperienceDraft.WRITTEN_KEYS) {
            // maturity 走 theDraftsMaturityIsDeliberatelyRefusedRatherThanAccepted 那条契约，
            // 它是「刻意不收」，不是「漏了」—— 混在一起报就会把设计说成缺口。
            if ("maturity".equals(k)) continue;
            if (!schemaKeys.contains(k)) missing.add(k);
        }
        assertTrue(missing.isEmpty(),
                "★ 草稿会写这些键，但 experience_learn 的 schema 一个都不收它们："
                        + missing + "\n  schema 实际收：" + schemaKeys
                        + "\n  ⇒ 学习者交来的值在 Gson.fromJson(args, Input.class) 里被**静默丢弃**，"
                        + "落库条目七槽全空，而草稿侧与合并侧的测试全都绿。"
                        + "端到端读数：learner.jsonl 的 learned 事件里的 seven_filled。");

        // schema 收了还不够：Input record 与 builder 调用必须同时搬。
        String inputBlock = parensOf(toolSrc, "private record Input(");
        String builderBlock = spanTo(toolSrc, "ExperienceEntry entry = ExperienceEntry.builder()", ".build();");
        assertFalse(inputBlock.isEmpty(), "自检：抠不到 Input record 的组件列表（record 用 () 不是 {}）");
        assertFalse(builderBlock.isEmpty(), "自检：抠不到 builder 调用链");

        List<String> notInInput = new ArrayList<>();
        List<String> notInBuilder = new ArrayList<>();
        for (String k : ExperienceDraft.WRITTEN_KEYS) {
            if ("maturity".equals(k)) continue;   // 同上：刻意不收，见那条契约测试
            if (!inputBlock.contains(k)) notInInput.add(k);
            if (!builderBlock.contains("in." + k + "()")) {
                // ★ 唯一的例外是 type：它必须先过 ExperienceType.valueOf(...) 变成局部变量
                //   再 .type(type)，所以链上出现的是 `in.type()` 之外的东西。
                //   判据改成「type 被校验过**且**真的进了 builder」两段都要在 ——
                //   只断一段的话，「校验了但忘了搬进 builder」会漏过去。
                boolean validated = toolSrc.contains("ExperienceType.valueOf(in.type()");
                boolean used = builderBlock.contains(".type(");
                if (!(validated && used)) notInBuilder.add(k + "（in." + k + "() 不在链上；"
                        + "type 的豁免要求 valueOf 校验=" + validated + " 且 .type(=" + used + "）");
            }
        }
        assertTrue(notInInput.isEmpty(),
                "★ schema 收了这几项但 Input record 没有对应组件 ⇒ Gson 静默丢弃：" + notInInput);
        assertTrue(notInBuilder.isEmpty(),
                "★ Input 收了这几项但 builder 调用没搬进去 ⇒ 值原地消失（参数合法、条目全空）："
                        + notInBuilder);
    }

    // ── 读 ExperienceLearnTool 源 ─────────────────────────────────────

    // ★ 路径相对**测试运行期的工作目录**（= plugins/learner），不是相对仓库根：
    //   本类既有的 ENTRY_SOURCE 用 ("..","..","experience-core",…) 能读到，
    //   说明 CWD = <repo>/plugins/learner ⇒ 插件源码要再多一层 ../.. 到仓库根再下 plugins/。
    private static final Path LEARN_TOOL_SOURCE = Paths.get(
            "..", "..", "plugins", "experience", "src", "main", "java",
            "com", "dwinovo", "numen", "plugins", "experience", "ExperienceLearnTool.java");

    private static String learnToolSource() throws IOException {
        Path abs = LEARN_TOOL_SOURCE.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            return fail("找不到 ExperienceLearnTool 源文件：" + abs
                    + " —— 文件被挪走/改名，这道门必须跟着改，不能跳过"
                    + "（跳过 = 这道门形同虚设，而它守的正是「值被静默丢弃」）");
        }
        return Files.readString(abs, StandardCharsets.UTF_8);
    }

    /** 抠 {@code parameterSchema()} 里所有 {@code ("key", "desc")} 形式的键。 */
    private static Set<String> keysInSchema(String src) {
        String body = blockOf(src, "public Map<String, Object> parameterSchema()");
        assertFalse(body.isEmpty(), "自检：抠不到 parameterSchema()");
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\.\\w*\\w+\\(\\s*\"([a-z_]+)\"").matcher(body);
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertTrue(keys.size() >= 8, "只抠到 " + keys.size() + " 个 schema 键，正则多半失效了：" + keys);
        return keys;
    }

    /** 从 {@code anchor} 处起括号配平，返回那对 {@code ()} 内的文本（record 的组件列表用它抠）。 */
    private static String parensOf(String src, String anchor) {
        int sig = src.indexOf(anchor);
        if (sig < 0) return "";
        int open = src.indexOf('(', sig + anchor.length() - 1);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return src.substring(open + 1, i);
            }
        }
        return "";
    }

    /**
     * 从 {@code anchor} 到 {@code terminator}（含）之间的文本。
     *
     * <p>★ 为什么要单独一个：builder 调用链里<b>没有大括号</b>，用配平大括号去抠会一路吃到
     * 方法后面去 —— 我第一版就是这么写的，结果把 {@code stored.maturity().name()}
     * （回执里合法的读数）也吃进了「builder 调用块」，
     * 于是 {@code assertFalse(块里不许出现 ".maturity(")} 变成恒假的检查。
     * <b>抠错边界的断言不是断言，是随机数</b>（有时红有时绿，取决于后面碰巧有没有那个词）。
     */
    private static String spanTo(String src, String anchor, String terminator) {
        int sig = src.indexOf(anchor);
        if (sig < 0) return "";
        int end = src.indexOf(terminator, sig + anchor.length());
        if (end < 0) return "";
        return src.substring(sig, end + terminator.length());
    }

    /** 从 {@code anchor} 处起找第一个 '{' 并括号配平，返回块内文本。 */
    private static String blockOf(String src, String anchor) {
        int sig = src.indexOf(anchor);
        if (sig < 0) {
            return "";
        }
        int open = src.indexOf('{', sig);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open + 1, i);
                }
            }
        }
        return "";
    }
}
