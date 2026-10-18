package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列与携带器的行为契约。
 *
 * <p>重点覆盖 2026-09-29 Codex 审出的三个真 bug 的回归：
 * ①读失败被当成空队列 → 覆盖原文件丢数据
 * ②restore 无上限
 * ③armor=none 被 contains 判成「有护甲」
 */
class MemoQueueTest {

    /** 生效链是进程级静态：每个用例先刷回 DEFAULT（否则复用别的测试类批准的规则）。 */
    @BeforeEach
    void isolateCarrierStore() {
        CarrierStoreIsolation.installEmpty();
    }

    private static Memo memo(String id, String problem) {
        return new Memo(id, problem, "stage-a", "tried something", "hp=10/20", 1L);
    }

    /** 取一批 memo 的 id 列表 —— 断言「轮到谁」要按 id 看，按对象比看不出顺序。 */
    private static List<String> idsOf(List<Memo> memos) {
        return memos.stream().map(Memo::id).toList();
    }

    @Test
    void appendAndDrainRoundTrip(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        assertTrue(q.append(memo("m-1", "could not mine iron")));
        assertTrue(q.append(memo("m-2", "creeper blew up the tunnel")));
        assertEquals(2, q.size());

        List<Memo> taken = q.drain(5);
        assertEquals(2, taken.size());
        // drain 不删：只有 commit 才删
        assertEquals(2, q.size());

        assertEquals(2, q.commitDrain(taken));
        assertEquals(0, q.size());
    }

    @Test
    void commitDrainRemovesOnlyTakenMemos(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        q.append(memo("m-1", "a"));
        q.append(memo("m-2", "b"));
        q.append(memo("m-3", "c"));

        List<Memo> taken = q.drain(2);
        assertEquals(2, q.commitDrain(taken));
        // 第三条不该被动
        assertEquals(1, q.size());
        assertEquals("m-3", q.all().get(0).id());
    }

    /** 复盘失败必须把数据放回去 —— 「清空队列」不等于「丢数据」。 */
    @Test
    void restorePutsTakenMemosBack(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        q.append(memo("m-1", "a"));
        q.append(memo("m-2", "b"));
        List<Memo> taken = q.drain(5);

        assertEquals(2, q.restore(taken));
        assertEquals(2, q.size());
    }

    @Test
    void restoreIsIdempotent(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        q.append(memo("m-1", "a"));
        List<Memo> taken = q.drain(5);
        q.restore(taken);
        q.restore(taken);
        assertEquals(1, q.size(), "restore 两次不该产生重复条目");
    }

    /**
     * ★ 钉住「{@code drain} 不删除」这条契约（2026-10-04）。
     *
     * <p>起因是我在这里连错两次：先判「restore 追加尾部 + drain 取头部 ⇒ 反复失败的
     * memo 被<b>永久饿死</b>」，自己推翻成「那是公平轮转」，实测下来<b>两次都错</b>。
     *
     * <p>真相：{@code drain()}（{@code MemoQueue.java:99-110}）<b>根本不删除</b> ——
     * {@code :102 readAll()} 之后 {@code :106 return List.copyOf(all.subList(0, …))}，
     * <b>既没改 {@code all} 也没 {@code writeAll}</b>。它只是<b>取看一眼</b>；
     * 真正把它从队列里拿走的只有 {@code commitDrain()}（{@code :113}）。
     *
     * <p>⇒ <b>「饿死」不可能发生</b>：失败的 memo 一直还在队列里，下一轮照样会被取到。
     * ⇒ 而 {@code restore()}（{@code :144}）对<b>仍在队列里</b>的 memo 是 no-op
     * （{@code :147 readAll()} 已含它们，{@code :160 if (present) continue} 直接跳过）。
     *
     * <p>★ 本测试的作用是<b>保护这条契约</b>：如果将来有人把 {@code drain}
     * 「优化」成真正删除（那样它就能与 commitDrain 合成一步，看起来很干净），
     * 下面两条会立刻红 —— 因为那时「取看一眼」这个语义就没了，
     * 而 {@code LearnerReviewTool} 依赖它做「取出 → 复盘 → 再决定 commit 还是 restore」。
     */
    @Test
    void drainOnlyLooksAtTheHeadAndDoesNotRemove(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        for (int i = 0; i < 6; i++) {
            q.append(memo("m-" + i, "p" + i));
        }

        List<Memo> first = q.drain(3);
        assertEquals(List.of("m-0", "m-1", "m-2"), idsOf(first), "第一轮取的是队首三条");
        assertEquals(6, q.size(), "★ drain 不许删除 —— 它只是取看一眼");
        assertEquals(0, q.commitDrain(List.of()), "commit 空列表也不该改变队列");

        List<Memo> again = q.drain(3);
        assertEquals(idsOf(first), idsOf(again),
                "★ 队列没被改动，所以再取一次拿到的是同一批 —— 这正是「不会饿死」的原因");
        assertEquals(6, q.size());

        // restore 对「仍在队列里」的这些必须是 no-op（否则会产生重复条目）
        assertEquals(6, q.restore(first), "★ restore 已存在的条目必须什么都不做");
        assertEquals(6, q.size(), "restore 不许产生重复");
    }

    @Test
    void queueRespectsCapacity(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        // ★ 下面的 64 是【字面量】，刻意不写 MemoQueue.MAX_QUEUE。
        //   变异测试实测：写成常量时把 MAX_QUEUE 改成 63，本测试【不会红】——
        //   期望值和被测值一起变，断言成了「队列遵守它自己的容量」而不是「容量是 64」。
        //   要改容量：先改这里（让它红，确认你知道在改契约），再改常量。
        for (int i = 0; i < 64; i++) {
            assertTrue(q.append(memo("m-" + i, "p" + i)));
        }
        assertFalse(q.append(memo("overflow", "one too many")), "超出上限必须拒收，不能静默丢");
        assertEquals(64, q.size());
    }

    /** Codex 审出的 P1：restore 原本无上限，可能突破 MAX_QUEUE。 */
    @Test
    void restoreDoesNotExceedCapacity(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        for (int i = 0; i < 64; i++) {
            q.append(memo("m-" + i, "p" + i));
        }
        List<Memo> all = q.all();
        int depth = q.restore(all);
        assertTrue(depth <= 64, "restore 后深度不得超过上限，实际 " + depth);
    }

    /**
     * ★ 契约钉死点：把「容量常量应该是多少」写成一条独立断言。
     *
     * <p>有了这条，任何人改 {@link MemoQueue#MAX_QUEUE} 都会立刻红，
     * 逼他 consciously 确认「64 是不是该改」，而不是悄悄改掉、
     * 让上面两个行为测试的期望值跟着一起漂移。
     */
    @Test
    void capacityConstantIsPinnedTo64() {
        assertEquals(64, MemoQueue.MAX_QUEUE, "队列容量契约是 64。要改就改这里并同步改行为测试里的字面量");
        assertEquals(4000, MemoQueue.MAX_FIELD_CHARS, "单条最大长度契约是 4000");
    }

    @Test
    void oversizedMemoIsRejected(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("m.json"));
        String huge = "x".repeat(MemoQueue.MAX_FIELD_CHARS + 1);
        assertFalse(q.append(new Memo("m-big", huge, "s", "t", "", 1L)));
        assertEquals(0, q.size());
    }

    /**
     * Codex 审出的 P1（最危险的一条）：读失败曾被当成空队列，
     * 紧接着 append 的全量重写会覆盖原文件 → 全部备忘录静默丢失。
     * 修复后读失败必须抛，且原文件内容一字不改。
     */
    @Test
    void corruptQueueThrowsAndDoesNotOverwrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("m.json");
        Files.writeString(file, "{ this is not valid json");
        MemoQueue q = new MemoQueue(file);

        assertThrows(IllegalStateException.class, q::size);
        assertThrows(IllegalStateException.class, () -> q.append(memo("m-1", "new")));

        assertEquals("{ this is not valid json", Files.readString(file),
                "读失败后原文件必须原样保留，绝不能被空队列覆盖");
    }

    @Test
    void stateSurvivesReopen(@TempDir Path dir) {
        Path file = dir.resolve("m.json");
        MemoQueue first = new MemoQueue(file);
        first.append(memo("m-1", "persisted"));
        // 换一个实例（新会话/重启）读同一文件
        MemoQueue second = new MemoQueue(file);
        assertEquals(1, second.size());
        assertEquals("persisted", second.all().get(0).problem());
    }

    @Test
    void emptyFileIsTreatedAsEmptyQueue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("m.json");
        Files.writeString(file, "");
        MemoQueue q = new MemoQueue(file);
        assertEquals(0, q.size());
        assertTrue(q.append(memo("m-1", "ok")));
    }

    // ---------- 携带器三段分级 ----------

    /**
     * Codex 审出的 P1：{@code contains("armor")} 会把 "armor=none" 判成有护甲。
     * 携带器给出的清单会正好反过来 —— 假事实。
     */
    @Test
    void carrierDoesNotTreatArmorNoneAsEquipped(@TempDir Path unused) {
        Memo m = new Memo("m-1", "p", "s", "t", "hp=6/20, armor=none, nearby=zombie", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertEquals("HOSTILE_NEARBY", a.target());
        assertTrue(a.why().contains("护甲=无"), "armor=none 必须判为无护甲，实际: " + a.why());
        assertTrue(a.carryList().contains("护甲获取类工具"));
    }

    @Test
    void carrierRecognisesRealArmor(@TempDir Path unused) {
        Memo m = new Memo("m-1", "p", "s", "t", "hp=20/20, armor=iron_chestplate, weapon=iron_sword", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertTrue(a.why().contains("护甲=有"), a.why());
        assertTrue(a.why().contains("武器=有"), a.why());
        assertEquals("HIGH", a.why().split("血量=")[1].split("\\(")[0]);
    }

    @Test
    void carrierGradesHealth(@TempDir Path unused) {
        assertEquals("CRITICAL", bandOf("hp=3/20"));
        assertEquals("LOW", bandOf("hp=8/20"));
        assertEquals("MID", bandOf("hp=12/20"));
        assertEquals("HIGH", bandOf("hp=18/20"));
    }

    @Test
    void carrierHandlesJsonStyleSnapshot(@TempDir Path unused) {
        // 修过的解析要吃 '"hp": 7' 这种带分隔符的写法（Codex 指出原实现在 ':' 处解析失败）
        Memo m = new Memo("m-1", "p", "s", "t", "{\"hp\": 7, \"armor\": \"none\"}", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertEquals(7, a.hp(), "应能从 JSON 风格快照抓出血量");
        assertTrue(a.why().contains("护甲=无"), a.why());
    }

    @Test
    void carrierReportsUnknownWhenNoSnapshot(@TempDir Path unused) {
        Memo m = new Memo("m-1", "p", "s", "t", "", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertEquals("UNKNOWN", a.target());
        assertEquals(-1, a.hp());
        assertNotNull(a.carryList());
    }

    private static String bandOf(String snapshot) {
        Memo m = new Memo("m-1", "p", "s", "t", snapshot, 1L);
        String why = m.assessCarrier().why();
        return why.split("血量=")[1].split("\\(")[0];
    }

    // ---------- 类别（2026-10-03，架构 owner 拍板「同入口但加类别字段」） ----------

    /**
     * ★ 不给类别与显式给 {@code learning} 必须落在<b>同一个值</b>上。
     *
     * <p>这条是给下游省事的：否则每个读 {@code category} 的地方都得先判空再判值，
     * 而两段判断必有一段会漏。六个分量那个老构造器就是「调用方没这概念」的入口。</p>
     */
    @Test
    void anAbsentCategoryIsLearningRatherThanBlank() {
        assertEquals(Memo.CATEGORY_LEARNING, new Memo("a", "p", "s", "t", "hp=1/20", 1L).category());
        assertEquals(Memo.CATEGORY_LEARNING,
                new Memo("b", "p", "s", "t", "hp=1/20", 1L, null).category());
        assertEquals(Memo.CATEGORY_LEARNING,
                new Memo("c", "p", "s", "t", "hp=1/20", 1L, "   ").category());
        assertEquals(Memo.CATEGORY_LEARNING,
                new Memo("d", "p", "s", "t", "hp=1/20", 1L, "learning").category());
    }

    /**
     * ★ 不认识的值原样留着，<b>不静默改成 learning</b>。
     *
     * <p>「它说了个我没听过的类别」是要让人看见的事实；悄悄改掉，下次查「为什么这条
     * 没按脚本走」就没线索了。</p>
     */
    @Test
    void anUnknownCategoryIsKeptSoItCanBeLookedAt() {
        Memo m = new Memo("m", "p", "s", "t", "hp=1/20", 1L, "ac_carrier_v2");
        assertEquals("ac_carrier_v2", m.category());
    }

    /**
     * ★ 类别必须<b>穿过队列</b>落到复盘看到的文本里。
     *
     * <p>这一条钉的是「时序类该出 AC 脚本而不是经验」这条意图的最后一环：
     * 类别只存在 Memo 对象里、没进 {@code toPromptBlock} 的话，
     * 复盘的 LLM 根本看不见，时序待办就会照样被写成一条经验。</p>
     */
    @Test
    void theTimingCategoryReachesTheReviewersPrompt() {
        String block = new Memo("m", "p", "s", "t", "hp=1/20", 1L, Memo.CATEGORY_TIMING)
                .toPromptBlock();
        assertTrue(block.contains(Memo.CATEGORY_TIMING),
                "★ 时序类必须出现在复盘文本里，否则学习者看不出它该写成 AC 脚本");
        assertTrue(block.contains("AC"),
                "要顺带告诉复盘方「这类该出脚本」，只印一个英文类别名它是猜不到的");
    }

    /**
     * 反证：缺省类别<b>不许</b>印进复盘文本。
     *
     * <p>learning 是绝大多数；每条都印一行「类别: learning」是纯噪音，
     * 而且会把「显式声明」与「缺省」在文本上混成一样。</p>
     */
    @Test
    void theDefaultCategoryStaysOutOfTheReviewersPrompt() {
        String block = new Memo("m", "p", "s", "t", "hp=1/20", 1L).toPromptBlock();
        assertFalse(block.contains("类别"), "缺省类别每条都印就是噪音：" + block);
    }

    /** 类别要跟着 memo 一起进队列、再一起读回来（不许只在内存里活着）。 */
    @Test
    void theCategorySurvivesTheQueueRoundTrip(@TempDir Path dir) throws Exception {
        MemoQueue q = new MemoQueue(dir.resolve("learner-memos-x.json"));
        assertTrue(q.append(new Memo("timing-1", "先挖三格再回头看", "s", "t", "hp=1/20", 1L,
                Memo.CATEGORY_TIMING)));
        Memo back = q.all().stream().filter(m -> "timing-1".equals(m.id())).findFirst()
                .orElseThrow(() -> new AssertionError("memo lost in the queue"));
        assertEquals(Memo.CATEGORY_TIMING, back.category(),
                "★ 类别只活在内存里的话，重启后它就没了 —— 而队列本来就要落盘");
    }
}
