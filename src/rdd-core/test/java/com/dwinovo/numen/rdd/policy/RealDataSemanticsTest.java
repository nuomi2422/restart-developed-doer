package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.DetectionMode;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.fact.StageKeyNormalizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三组反例的<b>半集成</b>验证：事实侧读<b>真实落盘文件</b>，而不是手搓 store。
 *
 * <p><b>为什么必须用真实文件</b>：本批抓到的最大一个错误就是
 * 「按想象中��� JSON 解析真实数据」——
 * 手写夹具的 {@code stages} 是对象映射加 status 字段，而真实文件是<b>数组、无 status</b>，
 * 于是测试全绿而实测永远读到 0 条。所以这里直接吃
 * {@code config/numen/rdd-facts/<uuid>.json}。
 *
 * <p>文件不存在时（换机器/新实例）<b>跳过而不是伪造</b>：伪造就等于回到手搓夹具的老路。
 */
class RealDataSemanticsTest {

    /** live 实例的事实目录；可用系统属性 {@code -DrddFactsDir=...} 覆盖。 */
    private static Path realFactsDir() {
        String override = System.getProperty("rddFactsDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of("E:\\.minecraft\\versions\\The Best of Twilight Forest\\config\\numen\\rdd-facts");
    }

    private static Path anyRealFactsFile() throws IOException {
        Path dir = realFactsDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> {
                        try {
                            return Files.size(p) > 50;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst().orElse(null);
        }
    }

    // ── 真实数据契约 ────────────────────────────────────────────────────────

    @Test
    void realFactsFile_parsesThroughProductionDeserializer() throws IOException {
        Path f = anyRealFactsFile();
        if (f == null) {
            return; // 没有真实文件 ⇒ 跳过，不伪造
        }
        String json = Files.readString(f, StandardCharsets.UTF_8);
        CompletedFactStore store = CompletedFactStore.fromJson(json);
        assertTrue(store.stageCount() > 0,
                "★ 真实事实文件必须能被生产反序列化器读出内容；读不出 0 条就说明形状又对不上了。文件: " + f);

        // 键形状：lineageId + U+0001 + stageKey（与 fromJson 的拼法一致）
        for (var s : store.stageFacts()) {
            assertFalse(s.stageKey() == null || s.stageKey().isBlank(), "stageKey 不能为空");
            assertEquals(StageKeyNormalizer.normalize(s.rawStage()), s.stageKey(),
                    "★ stageKey 必须等于 normalize(rawStage) —— 这是「键口径」红线；"
                            + "拿原始描述去比会一条都命中不了");
            assertTrue(s.lineageId() != null && s.lineageId().contains("#"),
                    "lineageId 形如 goal-xxx#objective: " + s.lineageId());
        }
    }

    @Test
    void realFactsFile_shadowAuditSeesThemAsComparable() throws IOException {
        Path f = anyRealFactsFile();
        if (f == null) {
            return;
        }
        String uuid = f.getFileName().toString().replace(".json", "");
        CompletedFactStore store = CompletedFactStore.fromJson(
                Files.readString(f, StandardCharsets.UTF_8));
        Path ledger = realFactsDir().getParent().resolve("usage-ledger.jsonl");
        var rep = com.dwinovo.numen.rdd.fact.FactShadowReconciler.reconcile(f, ledger);
        assertTrue(rep.comparable(),
                "★ 有真实事实记录时必须「可比」—— 否则 clean=false 会变成永远的红牌: " + rep.toMap());
        assertFalse(rep.factsBroken(), "真实文件不该被判成解析故障: " + rep.factsShape());
        assertTrue(uuid.length() > 10);
    }

    // ── 三组反例（走完整 RequirementView 管线）──────────────────────────────

    private static Goal goal(String id, String description) {
        return new Goal(id, description,
                List.of(new PrimaryGoal("p", "primary",
                        List.of(new Subtask("s1", "do", DetectionMode.HARD_CODED,
                                Map.of("k", "v"), 5L, 3, false, null)),
                        List.of(), false)));
    }

    private static RequirementManifest.Manifest manifest(String goalId, int min, String key) {
        return new RequirementManifest.Manifest(goalId,
                List.of(new RequirementManifest.Requirement(key, min, List.of())));
    }

    private static AssetRegistry registryWith(String key, int count) {
        AssetRegistry reg = new AssetRegistry();
        reg.apply(new Observation("obs-1", "inventory_scan", "test", "env-1", 1000L,
                        Map.of("count", count)),
                key, AssetScope.TASK_BOUND, "node-1");
        return reg;
    }

    @Test
    void case1_doneAndEnoughHeld_needsNoAttention() throws IOException {
        // 情况一：已完成 + 资产足够 ⇒ NONE
        Path f = anyRealFactsFile();
        if (f == null) {
            return;
        }
        CompletedFactStore store = CompletedFactStore.fromJson(
                Files.readString(f, StandardCharsets.UTF_8));
        // 拿真实文件里第一个 stageKey 当需求键 ⇒ 事实侧必然命中，资产侧给足
        String key = store.stageFacts().get(0).stageKey();
        var v = RequirementView.build(manifest(store.stageFacts().get(0).goalId(), 2, key),
                store, registryWith(key, 9), null);
        assertEquals(RequirementView.Attention.NONE, v.rows().get(0).attention(),
                "已完成且资产足够 ⇒ 不需要关注: " + v.rows());
    }

    @Test
    void case2_doneButNotEnoughHeld_isFlagged() throws IOException {
        // 情况二：事实完成，但资产不足 ⇒ DONE_BUT_NOT_HELD
        Path f = anyRealFactsFile();
        if (f == null) {
            return;
        }
        CompletedFactStore store = CompletedFactStore.fromJson(
                Files.readString(f, StandardCharsets.UTF_8));
        String key = store.stageFacts().get(0).stageKey();
        var v = RequirementView.build(manifest(store.stageFacts().get(0).goalId(), 3, key),
                store, registryWith(key, 1), null);
        assertEquals(RequirementView.Attention.DONE_BUT_NOT_HELD, v.rows().get(0).attention(),
                "★ 「做完」与「在手」对不上必须被挑出来: " + v.rows());
        assertEquals(2, v.rows().get(0).heldGap());
    }

    @Test
    void case3_noAssetRegistry_isUnknownHeld_neverZero() throws IOException {
        // ★ 情况三（最要紧）：没有资产登记 ⇒ UNKNOWN_HELD，**不是 0**
        Path f = anyRealFactsFile();
        if (f == null) {
            return;
        }
        CompletedFactStore store = CompletedFactStore.fromJson(
                Files.readString(f, StandardCharsets.UTF_8));
        String key = store.stageFacts().get(0).stageKey();
        var v = RequirementView.build(manifest(store.stageFacts().get(0).goalId(), 2, key),
                store, new AssetRegistry(), null);
        var row = v.rows().get(0);
        assertEquals("UNKNOWN_HELD", row.heldVerdict(),
                "★ 没有登记 = 不知道，不是「一个都没有」");
        assertEquals(null, row.held(), "★ 不知道时 held 必须是 null，写 0 就是把 UNKNOWN 偷换成 ZERO");
        assertTrue(String.valueOf(v.notes()).contains("判断不了"),
                "要明说「不是不用看，是判断不了」: " + v.notes());
    }
}
