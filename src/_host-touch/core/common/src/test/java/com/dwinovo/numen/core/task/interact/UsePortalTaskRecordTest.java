package com.dwinovo.numen.core.task.interact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code use_portal} 的记录层契约。
 *
 * <p>这些断言锁的是 2026-09-29 实机死循环的根因：同伴在门旁反复 goto 却从不进门。
 * {@code goto} 的「走到旁边」契约不能改（place_block / break_block / mine 一整串
 * 任务依赖它），所以跨维度必须是<b>另一个</b>工具；这里锁它的可寻址性与描述，
 * 让它不会被悄悄改回「又一个 goto」。
 */
class UsePortalTaskRecordTest {

    @Test void defaultsToNetherPortal() {
        var r = new UsePortalTaskRecord("c1", 1000L, null, 0);
        assertEquals("nether", r.kind);
        assertEquals("minecraft:nether_portal", r.targetBlock());
    }

    @Test void kindIsCaseAndSpaceInsensitive() {
        // 模型的 kind 常带空格/大小写混杂（" Nether " / "NETHER"）；不接受就是一次无谓的失败回合。
        assertEquals("nether", new UsePortalTaskRecord("c", 1L, "  NETHER ", 0).kind);
    }

    @Test void settleTicksIsClampedToSomethingThePortalCanHonour() {
        // 门被点燃后有 16 tick 冷却；负数没有意义，上限避免"站在门前十分钟"这种占位任务。
        assertEquals(0, new UsePortalTaskRecord("c", 1L, "nether", -50).settleTicks);
        assertEquals(20, new UsePortalTaskRecord("c", 1L, "nether", 20).settleTicks);
        assertEquals(200, new UsePortalTaskRecord("c", 1L, "nether", 99999).settleTicks);
    }

    @Test void describeNamesTheIntentForTheOwnerFacingBubble() {
        // describe() 会印在头顶气泡 / task_status 上，是主人唯一能看到的东西：
        // 它必须说清"走门"而不是"走过去"，否则主人会以为它已经进去了。
        assertTrue(new UsePortalTaskRecord("c", 1L, "nether", 0).describe().contains("下界门"));
        assertTrue(new UsePortalTaskRecord("c", 1L, "nether", 30).describe().contains("等"));
    }

    @Test void endKindIsRejectedInsteadOfSilentlyFailing() {
        // 2026-09-30 深审 R02/codex P1-2：end 门**从契约移除**。
        // 本任务只认 NetherPortalBlock，末地门是 EndPortalBlock —— 留着 kind=end
        // 会让模型以为能用，真跑起来却在「门消失→重扫」里耗尽预算（必败且烧时间）。
        // 诚实拒绝 > 挂一个看起来能用、实际必败的能力。
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new UsePortalTaskRecord("c", 1L, "end", 0));
        assertTrue(ex.getMessage().contains("eye_of_ender"),
                "拒绝话术要告诉模型正确做法：" + ex.getMessage());
    }

    @Test void netherKindAlwaysTargetsANetherPortal() {
        assertEquals("minecraft:nether_portal", new UsePortalTaskRecord("c", 1L, "nether", 0).targetBlock());
        assertEquals("minecraft:nether_portal", new UsePortalTaskRecord("c", 1L, null, 0).targetBlock());
    }

    @Test void toolNameIsDistinctFromGoto() {
        // 回归：若两者同名/同义，死循环会原样回来。assertNotEquals 把这条钉住。
        assertEquals("use_portal", UsePortalTaskRecord.TOOL_NAME);
        assertNotEquals("goto", UsePortalTaskRecord.TOOL_NAME);
    }

    @Test void describeMustNotPromiseAnActionWeNoLongerTake() {
        // 2026-09-29 实机：第一版是"走到门旁 + USE"。实测 useItemOn 门返回 PASS、
        // 同伴满血站在门边仍在下界 —— 原版下界门不能被 USE，传送只发生在**走进门块**时
        // (NetherPortalBlock.entityInside → PortalShape)。describe() 是主人和模型都看得见的
        // 字面承诺，不能再暗示"按一下就进去"。
        String d = new UsePortalTaskRecord("c", 1L, "nether", 0).describe();
        assertTrue(d.contains("门"), d);
        assertFalse(d.contains("使用"), "不该再描述成『使用传送门』：" + d);
    }
}
