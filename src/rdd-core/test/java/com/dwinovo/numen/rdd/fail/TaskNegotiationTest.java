package com.dwinovo.numen.rdd.fail;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Supervisor↔Numen 双向协商协议：ACCEPT/REJECT/COUNTER + 指挥官是否需介入。 */
class TaskNegotiationTest {

    @Test void acceptNeedsNoSupervisorAction() {
        var n = new TaskNegotiation(TaskNegotiation.Kind.ACCEPT, "s1", "", "");
        assertFalse(n.needsSupervisorAction());
        assertTrue(n.render().contains("ACCEPT"));
    }

    @Test void rejectAndCounterNeedAction() {
        var rej = new TaskNegotiation(TaskNegotiation.Kind.REJECT, "s1", "命令与当前目标冲突", "");
        assertTrue(rej.needsSupervisorAction());
        var ctr = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, "s1", "缺铁镐", "先采铁矿做铁镐");
        assertTrue(ctr.needsSupervisorAction());
        assertTrue(ctr.hasSuggestion());
        assertTrue(ctr.render().contains("先采铁矿"));
    }

  @Test void parseNormalisesButRejectsUnknownKinds() {
  assertEquals(TaskNegotiation.Kind.COUNTER, TaskNegotiation.parse("counter", "s", "r", "g").kind());
  assertEquals(TaskNegotiation.Kind.REJECT, TaskNegotiation.parse("REJECT", "s", "r", "").kind());
  assertEquals(TaskNegotiation.Kind.ACCEPT, TaskNegotiation.parse(null, "s", "", "").kind());
  // 2026-09-30 深审 R05：未知 kind 不再降级成 ACCEPT。
  // 旧行为下一次拼写错误会清掉 inbox 里之前合法的待处理回执（ACCEPT 走 remove 分支）。
  assertThrows(IllegalArgumentException.class,
        () -> TaskNegotiation.parse("garbage", "s", "", ""));
  }

    @Test void eventDataIsFlatAndComplete() {
        var n = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, "s1", "做不到", "换方案");
        Map<String, Object> d = n.toEventData("uuid-1");
        assertEquals("uuid-1", d.get("companionId"));
        assertEquals("COUNTER", d.get("kind"));
        assertEquals(true, d.get("needsSupervisorAction"));
        assertNotNull(d.get("summary"));
    }

    @Test void nullsAreTolerated() {
        var n = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, null, null, null);
        assertEquals("", n.taskId());
        assertEquals("", n.reason());
        assertEquals("", n.suggestion());
        assertFalse(n.hasSuggestion());
    }

    @Test void kindsListed() {
        // 2026-09-29 新增 PAUSE（原为 3）。这条断言的作用是"改接口必须被看见"：
        // 它真的挡下了一次漏改 —— 加了 PAUSE 却没更新 kinds() 就会红在这里。
        assertEquals(4, TaskNegotiation.kinds().size());
        assertTrue(TaskNegotiation.kinds().contains("COUNTER"));
        assertTrue(TaskNegotiation.kinds().contains("PAUSE"));
    }
}
