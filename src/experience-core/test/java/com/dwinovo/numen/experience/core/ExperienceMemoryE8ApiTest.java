package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ E8：<b>{@code ExperienceMemory} 这层薄转发的完整性</b>。
 *
 * <p><b>为什么单独一个文件</b>：E8 第一版<b>编译就过不了</b>，
 * {@code ExperienceMemory} 少了 {@code supersededIds()} 转发。
 * 根因是那次编译错误发生在 {@code plugins:experience}，
 * 而我当时<b>只看了 {@code :experience-core} 的测试结果</b> ⇒ 漏掉了。
 * 更麻烦的是 {@code plugins/experience} 的测试 classpath 里
 * <b>没有 gson 也没有 NumenTool</b>（都是 compileOnly），
 * 所以工具类<b>没法在那个模块里写单测</b>（写就会编译失败）。
 *
 * <p>所以分工是：<b>core 层的转发用单测钉死</b>（这里），
 * <b>工具层靠进游戏实跑 MCP 调用验</b>。这个文件的作用是
 * 「以后 core 又加了 E8 读数却忘了往 Memory 转发」时立刻红。</p>
 */
class ExperienceMemoryE8ApiTest {

    @TempDir
    Path tmp;

    private ExperienceMemory memory() {
        return ExperienceMemory.at(tmp.resolve("exp.jsonl"), new LexicalExperienceRetriever());
    }

    private static ExperienceEntry entry(String title) {
        return ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title(title).description("d").build();
    }

    @Test
    void memoryExposesEveryE8Action() {
        ExperienceMemory m = memory();
        String a = m.learn(entry("旧")).id();
        String b = m.learn(entry("新")).id();

        // ⚠️ 顺序不能反，这是**实现的设计**，不是测试凑数：
        //   supersede 必须发生在 retract 之前 —— 已撤回的条目不许再宣称取代别人。
        //   （顺序反了会拿到 null，我一度以为是转发漏了，其实是这个约束在生效。）
        assertNotNull(m.supersede(a, b), "Memory 必须转发 supersede");
        assertEquals(Set.of(a), m.supersededIds(), "Memory 必须转发 supersededIds（工具层用它列「哪些已过时」）");
        assertEquals(1, m.supersededCount());

        assertNotNull(m.retract(b, "撤回理由"), "Memory 必须转发 retract");
        assertEquals(1, m.retractedCount(), "刚撤回完就该有 1 条被撤回");
        assertNull(m.supersede(a, b), "★ 已撤回的条目不许再宣称取代别人（这是设计约束）");

        assertTrue(m.reinstate(b) != null && !b.isBlank(), "Memory 必须转发 reinstate");
        assertEquals(0, m.retractedCount(), "reinstate 之后不该还有被撤回的");
        assertNotNull(m.usable(), "Memory 必须转发 usable");
    }

    @Test
    void memoryStatsCarryTheE8Readings() {
        ExperienceMemory m = memory();
        String a = m.learn(entry("旧")).id();
        String b = m.learn(entry("新")).id();
        m.supersede(a, b);
        m.retract(b, "撤回理由");

        ExperienceStats st = m.stats();
        assertEquals(2, st.total(), "total 是全量");
        assertEquals(0, st.usable(), "两条都不可用了");
        assertEquals(1, st.retracted());
        assertEquals(1, st.superseded());
        assertEquals(2, st.unusable(), "unusable = total - usable（派生，不另设字段）");
    }

    /**
     * 反射兜底：把 E8 的读数清单钉死。
     *
     * <p>⚠️ 这条测试是<b>刻意的重复</b>：上面两条已经在实际调用了，
     * 但它们只覆盖「我这次想到的」。这个清单是「以后 core 新增了 E8 能力
     * 却在 Memory 上漏了转发」时的第一道提醒。</p>
     */
    @Test
    void memoryReflectivelyExposesTheE8Surface() {
        List<String> expected = List.of(
                "retract", "reinstate", "supersede", "supersededIds",
                "usable", "retractedCount", "supersededCount", "stats", "recall", "all", "size", "learn");
        for (String name : expected) {
            boolean found = false;
            for (Method mm : ExperienceMemory.class.getMethods()) {
                if (mm.getName().equals(name)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "ExperienceMemory 上缺 " + name
                    + " —— core 有能力而 Memory 没转发 = 工具层/注入层拿不到（E8 第一版就这么漏的）");
        }
    }
}