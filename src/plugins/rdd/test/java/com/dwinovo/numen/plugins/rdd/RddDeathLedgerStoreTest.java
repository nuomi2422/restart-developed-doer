package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddDeathLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2 宿主侧：死亡台账落盘。
 *
 * <p>覆盖的核心风险：坏文件必须 fail-soft（回落空台账而不是把死亡路径炸掉），
 * 以及跨重启 seq 不撞号（seq 是批次身份，撞号等于判据串批）。
 */
class RddDeathLedgerStoreTest {

    private static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void saveThenLoadRoundTripsEverything(@TempDir Path dir) throws Exception {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "700, 64, 671", 32,
                Map.of("minecraft:iron_ingot", 20, "minecraft:cooked_beef", 12));
        RddDeathLedger.confirmRecovered(ID, 1, "picked up");

        RddDeathLedgerStore.save(dir, ID, RddDeathLedger.snapshot(ID));
        Path file = dir.resolve(ID + ".json");
        assertTrue(Files.isRegularFile(file), "必须真的落出文件");

        RddDeathLedger.clearAll();
        RddDeathLedger.restore(ID, RddDeathLedgerStore.load(dir, ID));

        var d = RddDeathLedger.latest(ID);
        assertNotNull(d);
        assertEquals(1, d.seq());
        assertEquals("700, 64, 671", d.deathAt());
        assertEquals(32, d.lostEntries());
        assertEquals(RddDeathLedger.State.RECOVERED, d.state(), "闭合状态必须活过重启");
        assertEquals(Map.of("minecraft:iron_ingot", 20, "minecraft:cooked_beef", 12), d.lostItems(),
                "批次物品清单必须活过重启，否则重启后判据退化成'任意一件'");
        assertEquals(2, RddDeathLedger.snapshot(ID).nextSeq(), "nextSeq 必须落盘");
    }

    @Test
    void writtenJsonIsUtf8AndSelfDescribing(@TempDir Path dir) throws Exception {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "1, 2, 3", 1, Map.of("minecraft:bread", 1));
        RddDeathLedgerStore.save(dir, ID, RddDeathLedger.snapshot(ID));

        String raw = Files.readString(dir.resolve(ID + ".json"), StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"version\""), "落盘格式要有版本号，将来才排得掉旧文件");
        assertTrue(raw.contains("\"nextSeq\""));
        assertTrue(raw.contains("minecraft:bread"));
    }

    @Test
    void missingFileLoadsAsEmptyLedgerNotNull(@TempDir Path dir) {
        RddDeathLedger.LedgerSnapshot s = RddDeathLedgerStore.load(dir, UUID.randomUUID());
        assertNotNull(s, "'没死过' 与 '没有落过盘' 必须能区分开");
        assertEquals(1, s.nextSeq());
        assertTrue(s.deaths().isEmpty());
    }

    @Test
    void malformedJsonFailsSoftToEmptyLedger(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ID + ".json"), "{ this is not json", StandardCharsets.UTF_8);
        RddDeathLedger.LedgerSnapshot s = RddDeathLedgerStore.load(dir, ID);
        assertNotNull(s, "坏文件必须回落空台账，绝不能抛给死亡路径");
        assertTrue(s.deaths().isEmpty());
    }

    @Test
    void halfBrokenFileKeepsTheRowsItCanRead(@TempDir Path dir) throws Exception {
        // 一条完整 + 一条缺 seq（读不出来）+ 一条状态未知
        String json = """
                {"version":1,"nextSeq":7,"deaths":[
                  {"seq":3,"gameTime":3000,"wallClockMillis":9,"deathAt":"5, 6, 7","lostEntries":2,
                   "state":"UNVERIFIED","evidence":"","lostItems":{"minecraft:stone":2}},
                  {"gameTime":4000,"deathAt":"1, 1, 1"},
                  {"seq":5,"gameTime":5000,"deathAt":"2, 2, 2","state":"WAT","lostEntries":1}
                ]}""";
        Files.writeString(dir.resolve(ID + ".json"), json, StandardCharsets.UTF_8);
        RddDeathLedger.LedgerSnapshot s = RddDeathLedgerStore.load(dir, ID);

        assertEquals(2, s.deaths().size(), "两条可解析的记录应保留，坏的那条跳过");
        assertEquals(7, s.nextSeq(), "nextSeq 不因单条损坏而回退（回退会撞号）");
        assertEquals(RddDeathLedger.State.UNVERIFIED,
                s.deaths().get(1).state(), "未知状态名回落 UNVERIFIED，不抛");
    }

    @Test
    void savedFileIsAtomicNoTmpLeftBehind(@TempDir Path dir) throws Exception {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "0, 0, 0", 1, Map.of("minecraft:bread", 1));
        RddDeathLedgerStore.save(dir, ID, RddDeathLedger.snapshot(ID));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "原子写：成功后不该留下 .tmp");
        }
    }

    @Test
    void nullDirectoryIsANoOpNotAnException(@TempDir Path dir) throws Exception {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "0, 0, 0", 1, Map.of("minecraft:bread", 1));
        RddDeathLedgerStore.save(null, ID, RddDeathLedger.snapshot(ID));
        assertEquals(1, RddDeathLedger.snapshot(ID).deaths().size(),
                "落盘失败不得影响内存台账");
        assertEquals(2, RddDeathLedger.snapshot(ID).nextSeq(), "内存 nextSeq 不受落盘失败影响");
        assertTrue(RddDeathLedgerStore.load(null, ID).deaths().isEmpty(),
                "没有目录 = 磁盘上没有账本（空），不是异常");
    }

    @Test
    void saveOverwritesPreviousLedger(@TempDir Path dir) throws Exception {
        RddDeathLedger.clearAll();
        RddDeathLedger.record(ID, 1000L, 5_000L, "0, 0, 0", 1, Map.of("minecraft:bread", 1));
        RddDeathLedgerStore.save(dir, ID, RddDeathLedger.snapshot(ID));
        RddDeathLedger.record(ID, 2000L, 6_000L, "1, 1, 1", 1, Map.of("minecraft:stone", 1));
        RddDeathLedgerStore.save(dir, ID, RddDeathLedger.snapshot(ID));

        RddDeathLedger.clearAll();
        RddDeathLedger.restore(ID, RddDeathLedgerStore.load(dir, ID));
        assertEquals(2, RddDeathLedger.all(ID).size(), "第二次保存应覆盖而不是追加出重复行");
        assertFalse(RddDeathLedger.all(ID).isEmpty());
    }

    @Test
    void renderParseRoundTripsThroughText() {
        RddDeathLedger.LedgerSnapshot snapshot = new RddDeathLedger.LedgerSnapshot(9,
                List.of(new RddDeathLedger.Death(1, 1000L, 5_000L, "10, 20, 30", 5,
                        RddDeathLedger.State.CONFIRMED_LOST, "burned in lava",
                        Map.of("minecraft:diamond", 1))));
        RddDeathLedger.LedgerSnapshot back = RddDeathLedgerStore.parse(RddDeathLedgerStore.render(snapshot));
        assertEquals(9, back.nextSeq());
        assertEquals(1, back.deaths().size());
        assertEquals(RddDeathLedger.State.CONFIRMED_LOST, back.deaths().get(0).state());
        assertEquals("burned in lava", back.deaths().get(0).evidence());
        assertEquals(Map.of("minecraft:diamond", 1), back.deaths().get(0).lostItems());
    }
}