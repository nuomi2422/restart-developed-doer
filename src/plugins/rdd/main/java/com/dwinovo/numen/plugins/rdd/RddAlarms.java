package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.carrier.CarrierAlarms;
import com.dwinovo.numen.api.carrier.CarrierChain;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 携带器闹钟的<b>每一条独立开关</b> + 宿主自加的自理闹钟（换装提醒）。
 *
 * <p><b>为什么在宿主（plugins/rdd）而不在 {@link CarrierAlarms}</b>（2026-10-08）：
 * {@code CarrierAlarms} 正被另一个会话改（新增 {@code hungry_soft}），往同一文件塞新闹钟+开关
 * 有被覆盖的风险。本类只<b>复用</b> {@code CarrierAlarms} 的公开 {@code Alarm}/{@code Hit}/{@code Prio}
 * 记录，把"开关过滤"和"宿主闹钟"合并在渲染出口（{@code RddCarryHint}），不动那个文件。
 *
 * <p><b>形态：软提醒</b>。本类只多算一条闹钟、并按开关过滤内置闹钟，<b>不改任何判定</b>、
 * 不注册工具、不冻结主线 —— 与既有 {@code <alarms>} 同一种"提醒不是指令"。
 *
 * <p><b>开关文件</b>：{@code <configDir>/rdd-alarms.json}，形如 {@code {"hungry":true,"gear_upgrade":false}}。
 * 文件缺失时按 {@link #DEFAULTS} 生成一份（可见、可改）。<b>未列出的条目取默认</b>；
 * 新加的自理闹钟默认<b>关</b>（用户要求"写了先关，进游戏逐个验证"）。改文件即热加载（按 mtime）。
 */
final class RddAlarms {

    private RddAlarms() {
    }

    /**
     * 默认开关：既有闹钟默认<b>开</b>（保持现状）；新加的自理闹钟默认<b>关</b>。
     *
     * <p>{@code backpack_full} 不是 {@link CarrierAlarms} 里的闹钟（它是 RddDetector 另接的一条
     * nudge），但同样纳入这张表，做到"这一层的提醒都能单独关"。
     */
    private static final Map<String, Boolean> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("hungry", true);
        DEFAULTS.put("hungry_soft", true);
        DEFAULTS.put("low_hp", true);
        DEFAULTS.put("night", true);
        DEFAULTS.put("creeper", true);
        DEFAULTS.put("backpack_full", true);
        DEFAULTS.put("hp_topup", false);
        DEFAULTS.put("gear_upgrade", false);
    }

    private static volatile Map<String, Boolean> toggles = new LinkedHashMap<>(DEFAULTS);
    private static volatile Path file;
    private static volatile long lastMtime = -1L;

    /** 插件 setup 时调一次：记下开关文件路径、缺失则生成默认、读一次。 */
    static synchronized void install(Path configDir) {
        file = configDir == null ? null : configDir.resolve("rdd-alarms.json");
        writeDefaultsIfMissing();
        lastMtime = -1L;
        reload();
    }

    /** 缺失时写一份默认开关文件（供人直接改）。写失败不影响运行（用默认）。 */
    private static void writeDefaultsIfMissing() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            if (Files.isRegularFile(f)) {
                return;
            }
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            JsonObject o = new JsonObject();
            o.addProperty("_note", "携带器提醒开关：true=开 / false=关。改完即时生效（按文件 mtime 热加载）。"
                    + " gear_upgrade（换装提醒）默认关。");
            for (Map.Entry<String, Boolean> e : DEFAULTS.entrySet()) {
                o.addProperty(e.getKey(), e.getValue());
            }
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // 写不出就直接用默认，不影响提醒
        }
    }

    /**
     * 读开关文件并合并到默认之上。<b>只在 mtime 变化时重读</b>（热加载，避免每次渲染都读盘）。
     * 读失败保持上一份 —— 不因为文件坏了就把所有提醒关掉。
     */
    static synchronized void reload() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            long mt = Files.getLastModifiedTime(f).toMillis();
            if (mt == lastMtime) {
                return;
            }
            lastMtime = mt;
            Map<String, Boolean> merged = new LinkedHashMap<>(DEFAULTS);
            if (Files.isRegularFile(f)) {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isJsonNull() && e.getValue().isJsonPrimitive()) {
                        merged.put(e.getKey(), e.getValue().getAsBoolean());
                    }
                }
            }
            toggles = merged;
        } catch (IOException | RuntimeException ignored) {
            // 读失败：保留上一份 toggles，不静默全关
        }
    }

    /** 某条提醒当前是否开。未知条目取默认（有则默认，无则 true）。 */
    static boolean enabled(String rule) {
        Map<String, Boolean> m = toggles;
        Boolean b = m == null ? null : m.get(rule);
        if (b != null) {
            return b;
        }
        return DEFAULTS.getOrDefault(rule, true);
    }

    // ── 宿主自加闹钟：背包里有更好的装备没穿 ────────────────────────────────

    /** 生命不满提醒：血量没满但未到低血线（低血/危急由内置 low_hp 接手，本条不重复报）。P2 仅提示。 */
    private static final CarrierAlarms.Alarm HP_TOPUP = new CarrierAlarms.Alarm(
            "hp_topup", 1,
            f -> CarrierAlarms.Prio.P2,
            f -> f.hasHp() && f.hp() > CarrierAlarms.LOW_HP && "0".equals(f.get("hp_full")),
            f -> "hp=" + f.hp() + " hp_full=" + f.get("hp_full"),
            f -> "血量没满（" + f.hp() + "）：吃点东西/找机会回血把状态拉满，别带着残血硬上。");

    /** 换装提醒：快照端算出 {@code gear_gap}/{@code gear_best} 就触发（P2，仅提示）。 */
    private static final CarrierAlarms.Alarm GEAR_UPGRADE = new CarrierAlarms.Alarm(
            "gear_upgrade", 1,
            f -> CarrierAlarms.Prio.P2,
            f -> !f.get("gear_gap").isBlank(),
            f -> "gear_gap=" + f.get("gear_gap"),
            f -> "背包里有更好的装备没穿（" + f.get("gear_best")
                    + "）：方便时换上，别拿差装备硬扛。");

    /**
     * 合并求值：内置闹钟（按开关过滤）+ 宿主闹钟。
     *
     * <p>调用点与 {@link CarrierAlarms#evaluate} 完全一致（都是渲染 {@code <alarms>} 前），
     * 所以边沿去抖 / 主动唤醒不需要改 —— 它们看的是这份合并后的命中列表。
     */
    static List<CarrierAlarms.Hit> evaluate(CarrierChain.Facts facts) {
        reload();
        List<CarrierAlarms.Hit> out = new ArrayList<>();
        if (facts == null) {
            return out;
        }
        for (CarrierAlarms.Hit h : CarrierAlarms.evaluate(facts)) {
            if (enabled(h.rule())) {
                out.add(h);
            }
        }
        for (CarrierAlarms.Alarm extra : List.of(HP_TOPUP, GEAR_UPGRADE)) {
            CarrierAlarms.Hit h = evalOne(extra, facts);
            if (h != null && enabled(h.rule())) {
                out.add(h);
            }
        }
        return out;
    }

    /** 单条闹钟求值（与 {@code CarrierAlarms.evaluate} 内层同语义：出错就说出来，不静默）。 */
    private static CarrierAlarms.Hit evalOne(CarrierAlarms.Alarm a, CarrierChain.Facts f) {
        try {
            if (!a.active().test(f)) {
                return null;
            }
            String advice = a.advice().apply(f);
            if (advice == null || advice.isBlank()) {
                return null;
            }
            return new CarrierAlarms.Hit(a.rule(), a.version(), a.prio().apply(f).name(),
                    a.factsRef().apply(f), advice);
        } catch (RuntimeException ex) {
            return new CarrierAlarms.Hit(a.rule(), a.version(), CarrierAlarms.Prio.P2.name(),
                    "rule-error", "闹钟判定出错（" + ex.getClass().getSimpleName() + "），本条跳过");
        }
    }
}
