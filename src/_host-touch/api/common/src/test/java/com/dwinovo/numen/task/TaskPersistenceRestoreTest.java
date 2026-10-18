package com.dwinovo.numen.task;

import com.dwinovo.numen.task.TaskPersistence.RestoreDecision;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「重启后那件活接不接得回来」这道判断的回归。
 *
 * <h2>为什么这道判断要能被测</h2>
 * {@code restore()} 整个方法要 {@code MinecraftServer} 与同伴对象，单测够不着。
 * 于是它一度只能靠读码确认——而它守的正是本类类注释写下的不变式
 * 「<b>重建失败不静默：任务自己走 FAILED，收尾事件照发</b>」。
 * 承诺测不到就只是一句注释：2026-10-04 复核时发现「工具没了」那条路发了终态事件，
 * 「参数读不了」那条路只 {@code forget()} 就走了，<b>同一个类里两条失败路两种待遇</b>。
 *
 * <h2>判据钉在哪一层</h2>
 * 钉在 {@link TaskPersistence#decideRestore} ——「会不会给一个了结」真正被决定的那一层，
 * 不是「日志有没有写」那一层。后者两条路都写日志，测它等于什么都没测。
 */
class TaskPersistenceRestoreTest {

    private static final String TOOL = "mine";
    private static final String PRESENT = "mine";

    /** 工具在不在由调用方注入，这里给一个恒真的。 */
    private static RestoreDecision replayable(String rawArgs) {
        return TaskPersistence.decideRestore(TOOL, rawArgs, PRESENT::equals);
    }

    /** ★ 本次修的洞：参数读不了以前是「记条日志 + 清记录」，不发终态事件。 */
    @Test
    void unreadableArgumentsAlsoGiveUpInsteadOfVanishingQuietly() {
        RestoreDecision plan = replayable("{ 这不是 JSON");

        RestoreDecision.GiveUp giveUp = assertInstanceOf(RestoreDecision.GiveUp.class, plan,
                "★ 参数读不动也必须走 GiveUp —— 它和「工具没了」是同一种失败："
                        + "她手上那件活没了，但调用方必须能据此发一条 task_finished");
        assertEquals(TOOL, giveUp.tool());
        assertNotNull(giveUp.reason());
    }

    @Test
    void aToolThatVanishedGivesUpAndNamesIt() {
        RestoreDecision plan = TaskPersistence.decideRestore(TOOL, "{}", name -> false);

        RestoreDecision.GiveUp giveUp = assertInstanceOf(RestoreDecision.GiveUp.class, plan);
        assertEquals(TOOL, giveUp.tool());
        assertTrue(giveUp.reason().contains(TOOL),
                "了结的话必须点名是哪件活没了，否则读的人无从判断该不该重派：" + giveUp.reason());
    }

    /**
     * ★ 防退化回静默：如果哪天有人把 reason 改成空串或纯技术描述，这一条会红。
     * 「接不回来」对主人唯一有用的信息是「接下来怎么办」。
     */
    @Test
    void everyGiveUpSaysWhatToDoNextNotOnlyThatItFailed() {
        // 只放真正读不动的样本 —— 合法参数走的是 Replay 那条路，
        // 把 "{}" 塞进来会让这条测试自己变成一个错误的期望。
        for (String raw : new String[]{"{ 这不是 JSON", "[1,2,3]", "\"字符串不是对象\"", "null"}) {
            RestoreDecision plan = replayable(raw);
            RestoreDecision.GiveUp giveUp = assertInstanceOf(RestoreDecision.GiveUp.class, plan,
                    "raw=" + raw + " 这种参数必须给一个了结");
            assertTrue(giveUp.reason().contains("重新派") || giveUp.reason().contains("看看"),
                    "了结的话要给得出下一步，不只是「失败了」：" + giveUp.reason());
        }
    }

    @Test
    void aToolThatStillExistsIsReplayedWithItsOwnArguments() {
        RestoreDecision plan = replayable("{\"block_ids\":[\"minecraft:stone\",\"minecraft:dirt\",\"minecraft:cobblestone\"],\"n\":3}");

        RestoreDecision.Replay replay = assertInstanceOf(RestoreDecision.Replay.class, plan);
        assertEquals(TOOL, replay.tool());
        assertEquals(3, replay.args().get("n").getAsInt());
        assertEquals(3, replay.args().getAsJsonArray("block_ids").size());
        assertEquals("minecraft:cobblestone",
                replay.args().getAsJsonArray("block_ids").get(2).getAsString(),
                "参数要原样带回去 —— 重放的意义就是「同一件事再来一次」");
    }

    /** 空参数 = 那个工具本来就不要参数，不是失败。 */
    @Test
    void blankArgumentsMeanAnEmptyCallNotAFailure() {
        for (String blank : new String[]{null, "", "   "}) {
            RestoreDecision plan = replayable(blank);
            RestoreDecision.Replay replay = assertInstanceOf(RestoreDecision.Replay.class, plan,
                    "blank=" + blank);
            assertEquals(0, replay.args().size(), "空参数重放出去的是一个空对象调用");
        }
    }

    @Test
    void aReplayCarriesNoFailureReason() {
        RestoreDecision plan = replayable("{}");

        assertFalse(plan instanceof RestoreDecision.GiveUp,
                "能接回来就不是接不回来 —— 两者不许有第三种中间态把两者混起来");
    }

    /**
     * ★ 工具查一次就够。判据函数在单测里数调用次数，是因为接回来之后还要拿那个工具对象
     * 去真的发起调用；如果判定里查一次、发起时再查一次，中间任何一次注册表变动都会让
     * 「判定说能接、发起时却拿不到工具」变成可能，而那时候已经无处可查。
     */
    @Test
    void theToolLookupIsOnlyAskedOnce() {
        AtomicInteger asked = new AtomicInteger();

        TaskPersistence.decideRestore(TOOL, "{}", name -> {
            asked.incrementAndGet();
            return true;
        });

        assertEquals(1, asked.get(),
                "★ 判定里查一次就够了；restore() 把查到的工具对象一起传下去，"
                        + "不在 restore() 里再查第二次 —— 两次查之间注册表可能已经变了");
    }

    /**
     * ★ 接不回来的事在收尾事件里用的 id 必须是合成值。真实任务编号的计数器每次重启归零，
     * 拿 {@code t1} 当「重启前那件事的编号」会和这一局刚起的另一个任务撞号。
     */
    @Test
    void restoredTaskIdsAreSyntheticSoTheyNeverCollideWithRealTaskNumbers() {
        String id = TaskPersistence.restoredTaskId(TOOL);

        assertTrue(id.startsWith("restored-"), id);
        assertTrue(id.contains(TOOL), id);
        assertFalse(id.matches(".*\\bt\\d+\\b"),
                "不许长得像真实任务编号（t1/t2 那种），那种编号每次重启归零会撞号：" + id);
    }
}
