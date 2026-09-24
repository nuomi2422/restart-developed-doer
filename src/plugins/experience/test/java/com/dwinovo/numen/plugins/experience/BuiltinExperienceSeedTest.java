package com.dwinovo.numen.plugins.experience;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对<b>真实内置种子文件</b>的解析回归测试。
 *
 * <p>python 侧只证明了 JSON 语法合法，这里证明 Java 侧（Gson + {@code ExperienceEntry.fromJson}）
 * 能真吃下这批条目——否则内置层会静默退化成“没有出厂知识”，而日志里什么都看不出来。
 *
 * <p>文件不存在时<b>跳过</b>而不是失败：种子是部署产物（安装在游戏 config 目录），
 * 不该把测试绑死在某台机器的绝对路径上。
 */
class BuiltinExperienceSeedTest {

    /** 与 BuiltinExperience.RELATIVE_PATH 对应的实际部署位置（可用系统属性覆盖）。 */
    private static Path seedFile() {
        String override = System.getProperty("rdd.builtin.seed");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of("E:\\", ".minecraft", "versions", "The Best of Twilight Forest",
                "config", "numen", "knowledge", "builtin-experience.jsonl");
    }

    @Test
    void 真实种子文件可被Java侧完整解析() throws IOException {
        Path file = seedFile();
        assumeTrue(Files.isRegularFile(file), "种子文件未部署，跳过：" + file);

        List<PlanningKnowledge.Item> items = BuiltinExperience.parse(
                Files.readString(file, StandardCharsets.UTF_8));

        assertTrue(items.size() >= 30, "种子条目过少，实际 " + items.size());

        Set<String> ids = new HashSet<>();
        for (PlanningKnowledge.Item item : items) {
            assertEquals(BuiltinExperience.ORIGIN, item.origin(), item.id() + " 出处必须是 builtin");
            assertTrue(ids.add(item.id()), "种子出现重复 id：" + item.id());
            assertFalse(item.response().isBlank(),
                    item.id() + " 缺少推荐处理——空壳条目不该进种子");
            assertFalse(item.title().isBlank(), item.id() + " 缺少标题");
        }
    }

    @Test
    void 种子覆盖关键阶段知识() throws IOException {
        Path file = seedFile();
        assumeTrue(Files.isRegularFile(file), "种子文件未部署，跳过：" + file);

        List<PlanningKnowledge.Item> items = BuiltinExperience.parse(
                Files.readString(file, StandardCharsets.UTF_8));
        String all = items.toString();

        // 这几条是踩过的坑，缺了说明种子内容退化
        assertTrue(all.contains("深埋矿"), "缺少深埋矿下探经验");
        assertTrue(all.contains("附魔台"), "缺少附魔台最低配置经验");
        assertTrue(all.contains("下界"), "缺少下界规则经验");
    }
}
