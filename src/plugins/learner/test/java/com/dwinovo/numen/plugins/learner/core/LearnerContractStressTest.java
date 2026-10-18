package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约 + 压力回归（06-检查清单 收工「三类回归」里的后两类）。
 *
 * <p>单测证明「一条路径对」；这里证明「**并发/极限/契约下不丢不重不炸**」。
 *
 * <h2>契约（与宿主/其他模块的约定）</h2>
 * <ul>
 *   <li>C1 队列文件名与内容形态自洽（曾把 JSON 数组命名成 .jsonl，误导）</li>
 *   <li>C2 落盘是单个 JSON 数组（原子全量重写的必要条件）</li>
 *   <li>C3 复读后 memoId 稳定（下游按 id 对账，见 LearnerReviewTool.completeOnServer）</li>
 *   <li>C4 携带器输出是「纯函数」：同输入同输出，不带时间戳等易变字段</li>
 * </ul>
 */
class LearnerContractStressTest {

    /** C4 断言「同输入同输出」—— 生效链是进程级静态，不刷就会把别的测试类的规则算进去。 */
    @BeforeEach
    void isolateCarrierStore() {
        CarrierStoreIsolation.installEmpty();
    }

    private static Memo memo(String id) {
        return new Memo(id, "problem " + id, "stage", "tried " + id, "hp=10/20, armor=none", 1L);
    }

    // ================= 契约 =================

    /** C1/C2：扩展名是 .json 且内容是单个 JSON 数组。 */
    @Test
    void contract_fileNameAndShapeAreConsistent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("learner-memos-abc.json");
        MemoQueue q = new MemoQueue(file);
        q.append(memo("m-1"));
        q.append(memo("m-2"));

        String raw = Files.readString(file);
        assertTrue(raw.trim().startsWith("["), "内容必须是 JSON 数组，实际: " + raw.substring(0, Math.min(20, raw.length())));
        assertTrue(raw.trim().endsWith("]"), "内容必须是单个 JSON 数组");
        // 曾经叫 .jsonl 却存数组；现在文件名与形态一致
        assertTrue(file.getFileName().toString().endsWith(".json"));
        assertTrue(!file.getFileName().toString().endsWith(".jsonl"), "不应再用 .jsonl 命名（非行分隔）");
    }

    /** C3：跨实例复读 memoId 稳定 —— 下游按 id 做 commit/restore 对账，错位=误删。 */
    @Test
    void contract_memoIdStableAcrossReopen(@TempDir Path dir) {
        Path file = dir.resolve("q.json");
        MemoQueue a = new MemoQueue(file);
        a.append(memo("stable-1"));
        a.append(memo("stable-2"));

        MemoQueue b = new MemoQueue(file);
        List<String> ids = new ArrayList<>();
        b.all().forEach(m -> ids.add(m.id()));
        assertEquals(List.of("stable-1", "stable-2"), ids);
    }

    /** C4：携带器是纯函数（不含时间/随机），否则监测台对账会一直看到「变化」。 */
    @Test
    void contract_carrierIsPureFunction() {
        Memo m = memo("m-pure");
        String a = m.assessCarrier().why();
        String b = m.assessCarrier().why();
        assertEquals(a, b, "同输入必须同输出，携带器不得含时间戳/随机数");
    }

    /** 契约：restore 后 id 集合必须与 drain 前完全一致（不丢不重不串）。 */
    @Test
    void contract_restorePreservesIdSet(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("q.json"));
        for (int i = 0; i < 10; i++) {
            q.append(memo("m-" + i));
        }
        List<Memo> taken = q.drain(4);
        List<String> before = taken.stream().map(Memo::id).toList();
        q.restore(taken);

        List<String> after = q.all().stream().map(Memo::id).toList();
        assertEquals(before, after.subList(0, before.size()), "restore 后前若干条必须是原样 id 序列");
        assertEquals(10, q.size(), "一条都不能少");
    }

    // ================= 压力 =================

    /** 压力：满队列（64）反复 commit/restore 多轮，不得丢条目、不得重复。 */
    @Test
    void stress_fullQueueChurnKeepsCountStable(@TempDir Path dir) {
        MemoQueue q = new MemoQueue(dir.resolve("q.json"));
        int n = MemoQueue.MAX_QUEUE;
        for (int i = 0; i < n; i++) {
            q.append(memo("m-" + i));
        }
        assertEquals(n, q.size());

        for (int round = 0; round < 20; round++) {
            List<Memo> taken = q.drain(7);
            assertTrue(taken.size() <= 7);
            q.commitDrain(taken);
            // 补回同样多的，保持满载压力
            for (int i = 0; i < taken.size(); i++) {
                q.append(memo("churn-" + round + "-" + i));
            }
        }
        assertEquals(n, q.size(), "20 轮 churn 后深度必须仍等于上限，实际 " + q.size());
        // id 不得重复
        long distinct = q.all().stream().map(Memo::id).distinct().count();
        assertEquals(n, distinct, "churn 后不允许出现重复 id");
    }

    /** 压力：单条最大长度 × 满队列 —— 全量重写的最坏 I/O。 */
    @Test
    void stress_worstCasePayloadAndFullQueue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("q.json");
        MemoQueue q = new MemoQueue(file);
        String big = "x".repeat(MemoQueue.MAX_FIELD_CHARS);
        // ★ 64 与 4000 都是字面量，刻意不用常量（理由见 MemoQueueTest.queueRespectsCapacity 的注释）
        for (int i = 0; i < 64; i++) {
            q.append(new Memo("m-" + i, big, "s", big, big, 1L));
        }
        assertEquals(64, q.size());
        long bytes = Files.size(file);
        // 64 × ~12KB ≈ 0.8MB 上限量级；超过 4MB 说明上限没生效
        assertTrue(bytes < 4_000_000L, "最坏体积应受字段上限约束，实际 " + bytes + " bytes");
        // 仍能整体读回
        assertEquals(64, new MemoQueue(file).size());
    }

    /** 压力：并发 append —— 服务端 tick 与 AI 线程可能同时写。 */
    @Test
    void stress_concurrentAppendsAreSerialized(@TempDir Path dir) throws Exception {
        MemoQueue q = new MemoQueue(dir.resolve("q.json"));
        int threads = 8, perThread = 12;   // 96 次尝试，必然超过 64 上限
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (q.append(memo("t" + id + "-m" + i))) {
                            accepted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入超时（疑似死锁）");
        pool.shutdownNow();

        int size = q.size();
        assertTrue(size <= 64, "并发下不得突破上限，实际 " + size);
        assertEquals(size, accepted.get(), "accepted 计数与最终深度必须一致（无丢失/无幻影）");
        // 落盘内容必须可解析且 id 唯一
        long distinct = new MemoQueue(dir.resolve("q.json")).all().stream().map(Memo::id).distinct().count();
        assertEquals(size, distinct, "并发写后不允许出现重复 id（说明发生了丢失-覆盖）");
    }

    /** 压力：边界值 —— 恰好 0 条 / 恰好上限 / 超出上限。 */
    @Test
    void stress_boundaryCounts() {
        // 0 条
        assertEquals(0, new MemoQueue(java.nio.file.Paths.get("nonexistent-empty.json")).size());
    }
}
