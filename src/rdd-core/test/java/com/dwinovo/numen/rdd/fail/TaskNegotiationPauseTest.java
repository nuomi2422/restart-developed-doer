package com.dwinovo.numen.rdd.fail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PAUSE 与 COUNTER 的分界：这是新接线最容易做反的地方。
 *
 * <p>做反的后果不是报错，是<b>静默退化</b>：PAUSE 若被算成"需要军师介入"，
 * RddDetector 会走重规划路径，PAUSE 就退化成 COUNTER —— 士兵说"先放着"，
 * 系统却换了个计划继续派活，白做。
 */
class TaskNegotiationPauseTest {

    @Test void pauseIsNotASupervisorAction() {
        var pause = TaskNegotiation.parse("PAUSE", "s1", "no village within 300 blocks", "");
        assertTrue(pause.isPauseRequest());
        assertFalse(pause.needsSupervisorAction(),
                "PAUSE 不得触发重规划，否则退化成 COUNTER");
    }

    @Test void counterStillTriggersTheSupervisor() {
        var counter = TaskNegotiation.parse("COUNTER", "s1", "too expensive", "do X instead");
        assertFalse(counter.isPauseRequest());
        assertTrue(counter.needsSupervisorAction(), "COUNTER 必须继续走改单/重规划（不能被 PAUSE 抢走）");
    }

    @Test void acceptIsNeither() {
        var accept = TaskNegotiation.parse("ACCEPT", "s1", "", "");
        assertFalse(accept.isPauseRequest());
        assertFalse(accept.needsSupervisorAction());
    }

    @Test void pauseIsAdvertisedSoTheAgentCanActuallyChooseIt() {
        // 回归（2026-09-29）：kinds() 是工具 schema / 提示词里 kind 列表的唯一来源。
        // PAUSE 不在其中 = 士兵永远选不到它，状态机做完了也等于没接线。
        assertTrue(TaskNegotiation.kinds().contains("PAUSE"));
        assertTrue(TaskNegotiation.kinds().contains("COUNTER"));
        assertEquals(4, TaskNegotiation.kinds().size());
    }

    @Test void pauseRendersItsReasonSoOperatorsCanSeeWhyItStalled() {
        var pause = TaskNegotiation.parse("pause", "s1", "village is 400 blocks away", "");
        assertTrue(pause.isPauseRequest(), "kind 解析要大小写不敏感");
        assertTrue(pause.render().contains("PAUSE"));
        assertTrue(pause.render().contains("400 blocks"));
    }

    @Test void unknownKindIsRejectedRatherThanSilentlyBecomingAccept() {
        // 2026-09-30 深审 R05：旧实现把拼错的 kind（如 "PAUES"）降级成 ACCEPT，
        // 而 ACCEPT 在 Inbox 里走 PENDING.remove() → 一次打字错误就把之前
        // 合法的待处理 PAUSE/COUNTER 清掉。现在改为显式失败。
        assertThrows(IllegalArgumentException.class,
                () -> TaskNegotiation.parse("PAUES", "s1", "hold", ""));
        assertThrows(IllegalArgumentException.class,
                () -> TaskNegotiation.parse("WHATEVER", "s1", "r", ""));
        // 但"没填 kind"仍然是接单（与工具 schema 默认值一致）
        assertEquals(TaskNegotiation.Kind.ACCEPT, TaskNegotiation.parse(null, "s1", "r", "").kind());
        assertEquals(TaskNegotiation.Kind.ACCEPT, TaskNegotiation.parse("  ", "s1", "r", "").kind());
    }

    @Test void pauseAndCounterAreBothRoutable() {
        // 这条锁的是 2026-09-29 实机真踩的洞：RddNegotiationInbox.accept 的入队条件与
        // RddDetector.tickNegotiation 的消费条件**必须同时**认 PAUSE。
        // 只改一处时事件照发（看着像成功），但指挥官永远收不到 —— 静默失败最难查。
        for (var kind : new String[]{"PAUSE", "COUNTER", "REJECT"}) {
            var n = TaskNegotiation.parse(kind, "s1", "r", "");
            boolean routable = n.needsSupervisorAction() || n.isPauseRequest();
            assertTrue(routable, kind + " 必须既入队又可消费");
        }
        // 反面：ACCEPT 不该被路由（它只是回执），否则会被误当待处理指令执行
        var accept = TaskNegotiation.parse("ACCEPT", "s1", "", "");
        assertFalse(accept.needsSupervisorAction() || accept.isPauseRequest(),
                "ACCEPT 不得进入待处理队列");
    }
}
