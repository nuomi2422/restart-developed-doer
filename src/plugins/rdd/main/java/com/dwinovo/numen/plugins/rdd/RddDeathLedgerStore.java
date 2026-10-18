package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddDeathLedger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * F2 死亡台账的磁盘持久化：{@code config/numen/rdd-ledger/<uuid>.json}。
 *
 * <p>与 {@link RddHistoryStore}/{@code RddFactStore} 同构（原子 tmp+move）。
 * 刻意<b>不</b>把这段逻辑放进 {@link RddDeathLedger}：rdd-core 是纯 JVM 模块，
 * 不该出现 {@code Path}/JSON —— core 只提供 {@code snapshot/restore} 数据契约，
 * 磁盘归宿主。
 *
 * <p>坏文件 fail-soft（回落空台账）：台账坏了最多丢一批死亡记录，
 * 绝不能把死亡路径整个炸掉（那正是「同伴死而不复生」事故的形状）。
 */
final class RddDeathLedgerStore {
    private RddDeathLedgerStore() {}

    static final int FORMAT_VERSION = 1;

    static RddDeathLedger.LedgerSnapshot load(Path directory, UUID companionId) {
        if (directory == null || companionId == null) return new RddDeathLedger.LedgerSnapshot(1, java.util.List.of());
        Path file = directory.resolve(companionId + ".json");
        if (!Files.isRegularFile(file)) return new RddDeathLedger.LedgerSnapshot(1, java.util.List.of());
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException bad) {
            // 坏文件不阻断：明确记一次，之后按"没死过"处理。
            // 这里必须兜 Throwable —— 埋点链在裁剪 classpath 下会抛 NoClassDefFoundError，
            // 那正是"fail-soft 兜底自己炸掉"的形状（已实测）。
            try {
                RddPlugin.warnLedgerStore("ledger_load_failed", Map.of(
                        "companionId", companionId.toString(),
                        "error", String.valueOf(bad)));
            } catch (Throwable ignored) {
                // 连告警通道都没有时，至少不阻断死亡路径
            }
            return new RddDeathLedger.LedgerSnapshot(1, java.util.List.of());
        }
    }

    /** 解析台账文件。缺失/畸形字段一律跳过该条，<b>不</b>让整份文件作废。 */
    static RddDeathLedger.LedgerSnapshot parse(String json) {
        if (json == null || json.isBlank()) return new RddDeathLedger.LedgerSnapshot(1, java.util.List.of());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int nextSeq = 1;
        if (root.has("nextSeq") && root.get("nextSeq").isJsonPrimitive()) {
            try {
                nextSeq = Math.max(1, root.get("nextSeq").getAsInt());
            } catch (RuntimeException ignore) {
                nextSeq = 1;
            }
        }
        java.util.List<RddDeathLedger.Death> deaths = new java.util.ArrayList<>();
        if (root.has("deaths") && root.get("deaths").isJsonArray()) {
            for (var el : root.getAsJsonArray("deaths")) {
                RddDeathLedger.Death d = parseDeath(el);
                if (d != null) deaths.add(d);
            }
        }
        return new RddDeathLedger.LedgerSnapshot(nextSeq, deaths);
    }

    private static RddDeathLedger.Death parseDeath(com.google.gson.JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        if (!o.has("seq") || !o.has("gameTime")) return null;
        try {
            int seq = o.get("seq").getAsInt();
            long gameTime = o.get("gameTime").getAsLong();
            long wallClock = o.has("wallClockMillis") ? o.get("wallClockMillis").getAsLong() : 0L;
            String deathAt = o.has("deathAt") ? o.get("deathAt").getAsString() : "?";
            int lost = o.has("lostEntries") ? o.get("lostEntries").getAsInt() : 0;
            RddDeathLedger.State state = RddDeathLedger.State.UNVERIFIED;
            if (o.has("state") && o.get("state").isJsonPrimitive()) {
                try {
                    state = RddDeathLedger.State.valueOf(o.get("state").getAsString());
                } catch (IllegalArgumentException unknownState) {
                    state = RddDeathLedger.State.UNVERIFIED;
                }
            }
            String evidence = o.has("evidence") && o.get("evidence").isJsonPrimitive()
                    ? o.get("evidence").getAsString() : "";
            Map<String, Integer> lostItems = new LinkedHashMap<>();
            if (o.has("lostItems") && o.get("lostItems").isJsonObject()) {
                for (var entry : o.getAsJsonObject("lostItems").entrySet()) {
                    try {
                        lostItems.put(entry.getKey(), entry.getValue().getAsInt());
                    } catch (RuntimeException malformed) {
                        // 单个条目坏就跳过它，不能让整条死亡记录作废
                    }
                }
            }
            return new RddDeathLedger.Death(seq, gameTime, wallClock, deathAt, Math.max(0, lost),
                    state, evidence, lostItems.isEmpty() ? null : Map.copyOf(lostItems));
        } catch (RuntimeException bad) {
            return null;   // 这条读不出来就当没有；调用方会看到记录数变少
        }
    }

    static void save(Path directory, UUID companionId, RddDeathLedger.LedgerSnapshot snapshot) throws IOException {
        if (directory == null || companionId == null || snapshot == null) return;
        Files.createDirectories(directory);
        Path target = directory.resolve(companionId + ".json");
        Path temporary = directory.resolve(companionId + ".json.tmp");
        Files.writeString(temporary, render(snapshot), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static String render(RddDeathLedger.LedgerSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("nextSeq", snapshot.nextSeq());
        JsonArray deaths = new JsonArray();
        for (RddDeathLedger.Death d : snapshot.deaths()) {
            JsonObject o = new JsonObject();
            o.addProperty("seq", d.seq());
            o.addProperty("gameTime", d.gameTime());
            o.addProperty("wallClockMillis", d.wallClockMillis());
            o.addProperty("deathAt", d.deathAt());
            o.addProperty("lostEntries", d.lostEntries());
            o.addProperty("state", d.state().name());
            o.addProperty("evidence", d.evidence());
            if (!d.lostItems().isEmpty()) {
                JsonObject items = new JsonObject();
                for (Map.Entry<String, Integer> e : d.lostItems().entrySet()) {
                    items.addProperty(e.getKey(), e.getValue());
                }
                o.add("lostItems", items);
            }
            deaths.add(o);
        }
        root.add("deaths", deaths);
        return root.toString();
    }
}