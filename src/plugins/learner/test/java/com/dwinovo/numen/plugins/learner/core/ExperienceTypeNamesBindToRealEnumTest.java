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
 * ★ E4：{@code Experience.EXPERIENCE_TYPES} 与真源 {@code ExperienceType} <b>集合相等</b>。
 *
 * <p><b>为什么必须钉</b>：{@code plugins/learner} <b>刻意不依赖</b>
 * {@code experience-core}（B8 类注释里那条 JPMS 约束 —— 嵌套会出 ResolutionException，
 * 且两个 mod 各带一份同名类 = 运行时炸）。所以分类名在 learner 侧只能是字符串。
 * 代价是<b>两边可能悄悄漂移</b>：真源加了第六个分类，learner 这边不知道，
 * 于是学习者交出来的那个分类会被 {@code typeName()} 判成非法，
 * 表现为「莫名其妙全都不合格」—— 极难查。</p>
 *
 * <p>所以这个测试<b>直接读 experience-core 的源文件</b>，从
 * {@code public enum ExperienceType} 的枚举体里提取常量名，
 * 与 {@code EXPERIENCE_TYPES} 做集合相等断言。真源那边改，这边立刻红。
 * 手抄一份枚举值在这里是本项目已知的失败模式（见
 * {@code ExperienceDraftKeysBindToRealEntryTest} 对 {@code ENTRY_KEYS} 的同款处理）。</p>
 */
class ExperienceTypeNamesBindToRealEnumTest {

    private static final Path TYPE_SOURCE = Paths.get(
            "..", "..", "experience-core", "src", "main", "java",
            "com", "dwinovo", "numen", "experience", "api", "ExperienceType.java");

    /** 从真源 {@code ExperienceType.java} 的枚举体里提出常量名（{@code A, B, C;}）。 */
    private static Set<String> realEnumNames() throws IOException {
        Path abs = TYPE_SOURCE.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            return fail("找不到 ExperienceType 源文件：" + abs
                    + " —— 说明本脚本的工作目录/相对路径变了，先修路径再谈测试");
        }
        String src = Files.readString(abs, StandardCharsets.UTF_8);
        int sig = src.indexOf("public enum ExperienceType");
        if (sig < 0) {
            return fail("ExperienceType 里找不到 public enum ExperienceType —— "
                    + "若它被改成别的形状（class/接口），这个绑定测试要一起改，不是删掉");
        }
        int open = src.indexOf('{', sig);
        int close = src.indexOf('}', open);
        if (open < 0 || close < 0) {
            return fail("ExperienceType 枚举体解析失败：open=" + open + " close=" + close
                    + " —— 本脚本按「{...} 里只有 A, B, C;」的形状写，源结构变了要一起改");
        }
        String body = src.substring(open + 1, close);
        // 只认「整行的标识符 + 可选的逗号/分号」，避免把 javadoc 里的词吃进来。
        // ⚠️ 分隔符**必须可选**：Java 允许最后一个枚举常量不带分号，
        //    而真源 ExperienceType 的 POLICY 恰好就没有（2026-10-02 实测——
        //    这个测试第一次跑就是红的，红在提取逻辑而不是真源有 bug，值得记着）。
        Matcher m = Pattern.compile("(?m)^\\s*([A-Z][A-Z0-9_]*)\\s*[,;]?\\s*$").matcher(body);
        Set<String> names = new LinkedHashSet<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        assertFalse(names.isEmpty(), "从 " + abs + " 一个枚举常量都没提到，提取逻辑坏了");
        return names;
    }

    @Test
    void declaredTypeNamesMatchTheRealEnumExactly() throws Exception {
        Set<String> real = realEnumNames();
        List<String> declared = new ArrayList<>(Experience.EXPERIENCE_TYPES);

        assertEquals(new ArrayList<>(real), declared,
                "分类名两边漂移了 ⇒ 学习者交的真源里合法的分类，在 learner 侧会被判非法，"
                        + "表现是「莫名其妙全都不合格」\n  真源 ExperienceType: " + real
                        + "\n  learner EXPERIENCE_TYPES: " + declared);
        assertEquals(declared.size(), new LinkedHashSet<>(declared).size(),
                "EXPERIENCE_TYPES 里有重复项");
    }

    @Test
    void typeRulesCoverEveryDeclaredName() {
        // promptSpec() 会把判据逐条打出来；少一条就等于让它在某个分类上凭空猜。
        String rules = Experience.typeRules();
        for (String t : Experience.EXPERIENCE_TYPES) {
            assertTrue(rules.contains(t), "判据里少了 " + t + "：\n" + rules);
        }
    }
}