package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T9 · 「可跳过」策略的值钉死（改动前已有 RddOptionalFoodTest，本类补<b>它没钉的部分</b>）。
 *
 * <h2>这条策略为什么特殊</h2>
 *
 * <p>它是全仓唯一一条<b>判定层主动放宽</b>的规则：{@code optional=true} 的二级，
 * 不管是「找铁匠」还是「去主城」，只要模型标了就能跳过。
 * 2026-09-29 有意放宽（此前的限制与判定层不同步，会造成「判层说不可跳、工具说能跳」的自相矛盾）。
 *
 * <p>放宽带来的是一个必须被盯死的平衡：
 * <b>「跳过」必须永远被记成 SKIPPED，绝不能冒充 COMPLETED。</b>
 * 一旦冒充，主人读到的 rdd_status 会说「那件事做过了」，而实际上没做。
 *
 * <h2>本类与既有 {@code RddOptionalFoodTest} 的分工</h2>
 * <ul>
 *   <li>既有类：测「哪些步骤可以被跳过」（策略面）</li>
 *   <li><b>本类：测「跳过之后被记成什么」+ 「这个判断绝不依赖背包」</b>（语义面 + 存储面）</li>
 * </ul>
 *
 * <p>零生产改动、零构建改动、零 Minecraft。
 */
class RddOptionalFoodPinTest {

    private static Subtask subtask(Object... condition) {
        Map<String, Object> cond = new LinkedHashMap<>();
        for (int i = 0; i + 1 < condition.length; i += 2) cond.put((String) condition[i], condition[i + 1]);
        return Subtask.hardCoded("s1", "do it", cond);
    }

    private static Map<String, Integer> inv(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    // ── ① 显式标注：true 放行任何类型，false 一律否决 ────────────────

    @Test
    void explicitOptionalTruePassesAnyType() {
        // 2026-09-29 有意放宽：任何类型只要标了 optional=true 就能跳。
        // 这条与判定层同步放宽，所以判层与工具层不会自相矛盾。
        for (Object[] cond : new Object[][]{
                {"optional", true, "group", "wood"},
                {"optional", true, "asset_key", "minecraft:anvil"},
                {"optional", true, "asset_key", "minecraft:stone"},
                {"optional", true, "type", "advancement", "advancement", "minecraft:story/root"}}) {
            assertTrue(RddOptionalFood.isOptionalFood(subtask(cond)),
                    "optional=true 必须放行任何类型：" + String.valueOf(cond));
        }
    }

    @Test
    void explicitOptionalFalseAlwaysDenies() {
        // 这条比上面那条更重要：owner 明确说了「这一步不许跳」，
        // 任何类型的 optional=true 都不许覆盖它。
        for (Object[] cond : new Object[][]{
                {"optional", false, "group", "food"},
                {"optional", false, "asset_key", "minecraft:bread"},
                {"optional", false, "asset_key", "minecraft:anvil"}}) {
            assertFalse(RddOptionalFood.isOptionalFood(subtask(cond)),
                    "★ optional=false 一律否决：" + String.valueOf(cond)
                            + "。这是 owner 的硬指令，任何放宽都不许覆盖它");
        }
    }

    // ── ② 未标注：只有食物类才回落放行 ──────────────────────────────

    @Test
    void unmarkedFoodGroupOrFoodAssetPasses() {
        assertTrue(RddOptionalFood.isOptionalFood(subtask("group", "food")),
                "未标注 + group=food → 放行（生存必需的『找吃的』跳不掉）");
        assertTrue(RddOptionalFood.isOptionalFood(subtask("group", "food", "minimum", 1)));
        assertTrue(RddOptionalFood.isOptionalFood(subtask("asset_key", "minecraft:bread", "minimum", 3)),
                "未标注 + 食物资产 → 放行");
        assertTrue(RddOptionalFood.isOptionalFood(subtask("asset_key", "minecraft:cooked_beef", "minimum", 5)));
    }

    @Test
    void unmarkedNonFoodIsDenied() {
        for (Object[] cond : new Object[][]{
                {"group", "wood"},
                {"group", "blocks"},
                {"asset_key", "minecraft:anvil"},
                {"asset_key", "minecraft:diamond_sword"},
                {"asset_key", "minecraft:stone"},
                {"type", "advancement", "advancement", "minecraft:story/root"}}) {
            assertFalse(RddOptionalFood.isOptionalFood(subtask(cond)),
                    "★ 未标注时只有食物能跳过；" + String.valueOf(cond)
                            + " 若被判可跳，就是「没征得同意就把主线步骤划掉了」。"
                            + "要放宽请显式标 optional=true（这样主人至少能看到标注）");
        }
    }

    @Test
    void emptyOrNullTaskIsDenied() {
        assertFalse(RddOptionalFood.isOptionalFood(null), "null 步骤不得判为可跳");
    }

    @Test
    void emptyConditionIsDenied() {
        assertFalse(RddOptionalFood.isOptionalFood(
                        Subtask.aiAssisted("s1", "ask the model", 30, 3, true)),
                "AI_ASSISTED 二级的判据为空 → 不得判为可跳（它没有可核对的事实）");
    }

    // ── ③ ★ canSkip 绝不依赖背包 ★ ──────────────────────────────────

    @Test
    void canSkipIgnoresInventoryEntirely() {
        Subtask alreadyCovered = subtask("group", "food", "minimum", 1);
        Map<String, Integer> full = inv("minecraft:bread", 99);
        assertTrue(RddOptionalFood.canSkip(alreadyCovered, full),
                "★ canSkip 只回答「这一步可不可以被跳过」，绝不回答「它是不是已经做完了」。"
                        + "后者是判定层的事。两件事混在一起就会出现：AI 明明早就吃饱了，"
                        + "却把这一步标成跳过 —— 主人在报告里看到的是「跳过」而不是「早就做完了」");
        assertTrue(RddOptionalFood.canSkip(alreadyCovered, Map.of()),
                "背包为空时判定必须完全一致（canSkip 不看背包）");
    }

    @Test
    void foodAlreadyCoveredIsASeparateQuestionFromCanSkip() {
        Subtask t = subtask("group", "food", "minimum", 3);
        assertTrue(RddOptionalFood.foodAlreadyCovered(t, inv("minecraft:bread", 5)),
                "「已经搞够了」是另一个问题，由 foodAlreadyCovered 回答");
        assertFalse(RddOptionalFood.foodAlreadyCovered(t, inv("minecraft:bread", 1)),
                "不够就是不够 —— 绝不许放宽成「差不多够了」");
        // 关键：canSkip 不因为「已经够了」而变成别的语义
        assertTrue(RddOptionalFood.canSkip(t, inv("minecraft:bread", 5)),
                "canSkip 与 foodAlreadyCovered 互不干涉：两个问题的答案必须能同时成立");
    }

    // ── ④ ★ 跳过必须被记成 SKIPPED，绝不冒充 COMPLETED ★ ─────────────

    @Test
    void skipSubtaskRecordsSkippedNeverCompleted() {
        String taskChain = readRddCoreSource("core" + java.io.File.separator + "TaskChain.java");
        String body = methodBody(taskChain, "skipSubtask", "SKIPPED");
        assertTrue(body.contains("SubtaskStatus.SKIPPED"),
                "★ skipSubtask 必须把状态置为 SKIPPED");
        assertFalse(body.contains("SubtaskStatus.COMPLETED"),
                "★ skipSubtask 绝不许碰 COMPLETED。一旦碰了：AI 从 rdd_status 读到「已完成」，"
                        + "就不会再做这件事；主人看到的链条也是「每件事都做完了」，"
                        + "而实际上有几件被悄悄划掉了。这是本仓最典型的「假完成」形态");
    }

    @Test
    void skipToolIsGatedByTheSamePolicy() {
        String plugin = readPluginSource("RddPlugin.java");
        String body = methodBody(plugin, "skipOptionalCurrent", "RddOptionalFood.canSkip");
        assertTrue(body.contains("RddOptionalFood.canSkip("),
                "★ 模型请求跳过的那条路径必须走同一个 canSkip 判定。若它自己写了一套条件，"
                        + "就会出现「策略说不能跳、工具说能跳」的自相矛盾");
        assertTrue(body.contains("expectedSubtaskId"),
                "必须校验任务号（防张冠李戴：模型拿着上一轮的 id 来跳本轮的步）");
        assertTrue(body.contains("skipSubtask("),
                "最终必须落到状态机的 skipSubtask，而不是自己改状态");
    }

    @Test
    void modelRequestedSkipIsRefusedForNonOptionalSteps() {
        String plugin = readPluginSource("RddPlugin.java");
        assertTrue(plugin.contains("refused: only an optional food step"),
                "★ 模型请求跳过非食物步骤时必须被明确拒绝并给出可读理由"
                        + "（否则模型会反复重试同一个请求，白烧 token）");
        assertTrue(plugin.contains("model_requested_optional_skip"),
                "允许的跳过必须留事件，否则「谁把哪一步划掉了」无法追溯");
    }

    // ── 读源码的基础设施 ────────────────────────────────────────────

    private static Path pluginSourceDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            Path src = cur.resolve("src/main/java/com/dwinovo/numen/plugins/rdd");
            if (Files.isDirectory(src)) return src;
        }
        return cwd.resolve("src/main/java/com/dwinovo/numen/plugins/rdd");
    }

    // rdd-core 是<b>兄弟模块</b>（<repo>/rdd-core），不是本模块的子目录，所以要两跳找：
    // 先向上找到含 settings.gradle 的仓库根，再进 rdd-core。
    // 不能只按 .../com/dwinovo/numen/rdd 找 —— plugins/rdd 自己的包根是
    // .../com/dwinovo/numen/plugins/rdd，按短路径找会先命中错的那个（或什么都不命中）。
    private static Path rddCoreSourceDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path cur = cwd; cur != null; cur = cur.getParent()) {
            if (Files.isRegularFile(cur.resolve("settings.gradle"))) {
                Path src = cur.resolve("rdd-core/src/main/java/com/dwinovo/numen/rdd");
                if (Files.isRegularFile(src.resolve("core/TaskChain.java"))) return src;
            }
        }
        fail("定位不到 rdd-core 源目录（向上找含 settings.gradle 的仓库根，再进 rdd-core/src/main/java/com/dwinovo/numen/rdd）。"
                + "当前工作目录 " + cwd);
        return cwd;
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

    private static String readPluginSource(String fileName) {
        return read(pluginSourceDir(), fileName);
    }

    private static String readRddCoreSource(String relative) {
        return read(rddCoreSourceDir(), relative);
    }

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

    private static String methodBody(String src, String methodName, String mustContain) {
        var m = java.util.regex.Pattern.compile("(\\b" + java.util.regex.Pattern.quote(methodName) + "\\s*\\()")
                .matcher(src);
        while (m.find()) {
            int nameStart = m.start(1);
            int i = nameStart - 1;
            while (i >= 0 && Character.isWhitespace(src.charAt(i))) i--;
            if (i >= 0 && (src.charAt(i) == '.' || src.charAt(i) == ')')) continue;
            if (src.substring(Math.max(0, i - 3), i + 1).endsWith("new ")) continue;
            int open = src.indexOf('{', m.end(1));
            if (open < 0) continue;
            int close = braceEnd(src, open);
            String body = src.substring(open + 1, close);
            if (mustContain == null || body.contains(mustContain)) return body;
        }
        fail("在源码里找不到方法 " + methodName);
        return "";
    }

    @Test
    void sourceReadingMechanismItselfWorks() {
        assertTrue(readPluginSource("RddPlugin.java").contains("class RddPlugin"), "自检：读到了 RddPlugin");
        assertTrue(readRddCoreSource("core" + java.io.File.separator + "TaskChain.java").contains("class TaskChain"),
                "自检：读到了 TaskChain");
        assertTrue(methodBody(readRddCoreSource("core" + java.io.File.separator + "TaskChain.java"),
                "skipSubtask", "SKIPPED").contains("SKIPPED"), "自检：方法体提取可用");
    }
}