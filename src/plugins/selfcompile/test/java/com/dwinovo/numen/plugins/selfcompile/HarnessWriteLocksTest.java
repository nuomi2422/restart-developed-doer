package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** P5.3 多 Harness 写锁：同 Harness 独占、不同 Harness 互不阻塞、重入、释放归属、参数防护。 */
class HarnessWriteLocksTest {

    @Test
    void harnessIsExclusivePerOwner() {
        HarnessWriteLocks locks = new HarnessWriteLocks();
        assertTrue(locks.tryAcquire("game-gpt", "mutation-A"));
        assertTrue(locks.isLocked("game-gpt"));
        assertEquals("mutation-A", locks.holderOf("game-gpt").orElseThrow());

        assertFalse(locks.tryAcquire("game-gpt", "mutation-B"), "同一 Harness 不允许第二个写者");
        assertEquals("mutation-A", locks.holderOf("game-gpt").orElseThrow(), "持有者不变");
    }

    @Test
    void sameOwnerCanReenter() {
        HarnessWriteLocks locks = new HarnessWriteLocks();
        assertTrue(locks.tryAcquire("h", "m"));
        assertTrue(locks.tryAcquire("h", "m"), "同 owner 重入应成功");
        assertEquals(1, locks.activeLocks(), "重入不新增锁");
        assertTrue(locks.release("h", "m"));
        assertFalse(locks.isLocked("h"), "一次释放即解锁");
    }

    @Test
    void differentHarnessesRunConcurrently() {
        HarnessWriteLocks locks = new HarnessWriteLocks();
        assertTrue(locks.tryAcquire("game-gpt", "mutation-A"));
        assertTrue(locks.tryAcquire("game-test", "mutation-B"), "不同 Harness 互不阻塞");
        assertEquals(2, locks.activeLocks());
        assertTrue(locks.lockedHarnesses().containsAll(java.util.Set.of("game-gpt", "game-test")));
    }

    @Test
    void onlyHolderCanRelease() {
        HarnessWriteLocks locks = new HarnessWriteLocks();
        locks.tryAcquire("h", "owner-A");
        assertFalse(locks.release("h", "owner-B"), "非持有者不能释放");
        assertTrue(locks.isLocked("h"));
        assertTrue(locks.release("h", "owner-A"));
        assertFalse(locks.isLocked("h"));
    }

    @Test
    void nullOrBlankArgsAreRejected() {
        HarnessWriteLocks locks = new HarnessWriteLocks();
        assertFalse(locks.tryAcquire(null, "m"));
        assertFalse(locks.tryAcquire("h", null));
        assertFalse(locks.tryAcquire("  ", "m"));
        assertFalse(locks.tryAcquire("h", "  "));
        assertFalse(locks.release(null, "m"));
        assertFalse(locks.release("h", null));
        assertEquals(0, locks.activeLocks());
    }
}
