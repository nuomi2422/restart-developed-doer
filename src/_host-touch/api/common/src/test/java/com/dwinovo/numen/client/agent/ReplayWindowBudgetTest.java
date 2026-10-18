package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * replay 窗口限流的判据（2026-10-01）。
 *
 * <p><b>为什么有它</b>：实测单轮请求里 <b>history 占 78%</b>（89,996 / 115,246 字符、205 条消息），
 * 而压缩闸 {@code contextTokens >= ctx - 13,000}、{@code ctx=1,000,000} → 阈值 987,000，
 * <b>压缩永不触发</b>。{@code KEEP_RECENT_TOKENS=20_000} 这个正确的设计<b>只在压缩路径里用</b>，
 * 于是压缩之前一直是全量回灌。
 *
 * <p><b>本次改动的意义是「解耦」</b>：{@code ctx} = <b>模型能力</b>（不动）；
 * 「我们每轮发多少」= <b>策略</b>（此前被隐式绑定在能力上）。
 *
 * <p><b>不丢记忆</b>：RDD 任务链整轮都在上下文里（一级/二级目标、{@code done_when}、状态、资产账），
 * 丢掉的是<b>对话流水</b>，不是「我做了什么、下一步是什么」。
 */
class ReplayWindowBudgetTest {

    private static final int WINDOW = 20_000;

    /** 造 n 条「每条约 1000 token」的消息。 */
    private static ConvoState.Msg newAssistant(String content) {
        return new ConvoState.Msg.Assistant(new com.dwinovo.numen.agent.provider.AssistantTurn(
                content, List.of(), null, ""));
    }

    private static List<ConvoState.Msg> history(int n) {
        List<ConvoState.Msg> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(newAssistant("第" + i + "轮".repeat(400)));
        }
        return out;
    }

    private static List<ConvoState.Msg> windowOf(List<ConvoState.Msg> all) {
        var split = com.dwinovo.numen.agent.llm.CompactSplit.byRecentBudget(all, WINDOW);
        return split.toSummarize().isEmpty() ? all : split.kept();
    }

    @Test
    void shortHistoryIsNotTouched() {
        List<ConvoState.Msg> all = history(3);
        assertEquals(3, windowOf(all).size(), "未超预算时必须原样，不能无谓裁剪");
    }

    @Test
    void longHistoryGetsCutToTheWindow() {
        List<ConvoState.Msg> all = history(205);   // 复刻实测条数
        List<ConvoState.Msg> kept = windowOf(all);
        assertTrue(kept.size() < all.size(), "205 条必须被裁");
        assertTrue(com.dwinovo.numen.agent.llm.CompactSplit.estimateTokens(kept) <= WINDOW + 2000,
                "保留部分应落在窗口预算附近（含单条超限的余量），实际 "
                        + com.dwinovo.numen.agent.llm.CompactSplit.estimateTokens(kept));
    }

    @Test
    void keptTailKeepsTheMostRecentTurns() {
        // 裁剪只能丢**更早**的；最近这几轮是「当前正在干什么」，丢了就是让 AI 失忆
        List<ConvoState.Msg> all = history(205);
        List<ConvoState.Msg> kept = windowOf(all);
        assertEquals(all.get(all.size() - 1), kept.get(kept.size() - 1), "最后一条必须保留");
        assertEquals(all.get(all.size() - 2), kept.get(kept.size() - 2), "倒数第二条必须保留");
    }

    @Test
    void originalListIsNeverMutated() {
        // ★ 这是本改动最重要的性质：只是**视图**限流，源历史与落盘日志一个字不动
        // （沿用 modelContextSnapshot 原注释「源会话与落盘日志一个字不动」）
        List<ConvoState.Msg> all = history(205);
        int before = all.size();
        var snapshot = new ArrayList<>(all);
        windowOf(all);
        assertEquals(before, all.size(), "源 list 长度不能变");
        assertEquals(snapshot, all, "源 list 内容不能变（不能就地 remove）");
    }

    @Test
    void compactionStillSeesTheFullHistory() {
        // 本改动只碰 modelContextSnapshot（发给模型的那一份），
        // 压缩路径走的是 convo.snapshot() 全量 —— 两边不能被一起改掉。
        // 这里守住「压缩仍拿得到全量」：byRecentBudget 本身不修改入参。
        List<ConvoState.Msg> all = history(205);
        int before = all.size();
        com.dwinovo.numen.agent.llm.CompactSplit.byRecentBudget(all, WINDOW);
        assertEquals(before, all.size(), "压缩切分也不能就地改源 list");
    }

    @Test
    void byRecentBudgetAloneCanReturnEmptySoTheCallerMustGuard() {
        // ⚠️ 本条测的是 **CompactSplit 自己的性质**（不是 replayWindow 的兜底）：
        //   单条消息就超预算时，byRecentBudget 返回 kept=[] —— 这是**合法**的。
        // 由此得出一条**必须成对**的契约：
        //     调用方（EntityAgentLoop.replayWindow）**必须**在 kept 为空时兜底保留最后一条。
        //   否则「省 token 的手段」会变成「让 AI 失忆的手段」—— 比原来全量回灌更糟。
        List<ConvoState.Msg> all = new ArrayList<>();
        all.add(newAssistant("x".repeat(120_000)));   // 单条就 12 万字符
        var split = com.dwinovo.numen.agent.llm.CompactSplit.byRecentBudget(all, WINDOW);
        assertTrue(split.kept().isEmpty(),
                "byRecentBudget 单独用会清空 —— 这正是 replayWindow 必须兜底的原因");
        assertEquals(1, all.size(), "且它不会就地改源 list");
    }
}
