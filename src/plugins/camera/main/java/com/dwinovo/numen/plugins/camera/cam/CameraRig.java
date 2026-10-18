package com.dwinovo.numen.plugins.camera.cam;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 相机机位状态机：决定「镜头该站在哪、看向哪」，算法本体在 {@code MixinCamera}。
 *
 * <h2>为什么自己算而不是用原版第三人称</h2>
 * 原版只有一种行为（背后固定 4 格），而跟拍要的是能换的追踪。原版那套的算法已经整个
 * 抄进 {@code MixinCamera}（含 partial-tick 插值与 8 角点碰撞拉近），这里只管策略层 ——
 * 换模式就是换几行常数。
 *
 * <h2>模式</h2>
 * <ul>
 *   <li>{@link Mode#SHOULDER} 越肩：贴在目标背后，back/up/shoulder 三轴可调</li>
 *   <li>{@link Mode#ORBIT} 轨道：世界方位角固定，目标转身镜头不跟（适合看它干活）</li>
 *   <li>{@link Mode#LOCK} 固定机位：镜头钉在世界某点只转头（适合长镜）</li>
 *   <li>{@link Mode#CINEMATIC} 电影：越肩 + 俯角偏置 + 跨帧阻尼（不抖）</li>
 * </ul>
 *
 * <h2>为什么配置从文件读</h2>
 * 相机是纯客户端的，MCP 工具跑在服务端侧。配置文件每 20 tick（1s）重读一次，外部写文件
 * 即可改机位 —— 调试迭代不用重启游戏。游戏内指令（{@code /cam}）改的是同一份内存状态，
 * <b>并且会写回同一个文件</b>，所以两条路不会互相打架。
 *
 * <h2>目标不在客户端实体表里就退回原版</h2>
 * 客户端只持有渲染距离内的实体。目标走出范围时 {@link #resolveTarget()} 返回 null，
 * 此时 mixin 不取消，原版相机照常工作。<b>绝不能拿 null 硬算</b> —— 那会让整帧渲染崩。
 *
 * <h2>为什么 config 目录名还是 numen_api</h2>
 * 2026-10-04 从 {@code api/common} 的 client 源码集搬进本模块，但配置文件路径保持
 * {@code config/numen_api/cam.json} 不变 —— 换路径会让用户已有的机位配置当场失效，
 * 而换模块本来不该弄丢任何一份现场配置。
 */
public final class CameraRig {

    /** 本模块的 modId。 */
    public static final String MOD_ID = "numencam";

    /**
     * 配置目录名。<b>故意不是 {@link #MOD_ID}</b>：见类注释「为什么 config 目录名还是
     * numen_api」。
     */
    private static final String CONFIG_DIR = "numen_api";

    /** 追踪模式。{@link #OFF} 表示完全交还原版。 */
    public enum Mode {
        OFF, SHOULDER, ORBIT, LOCK, CINEMATIC
    }

    private static final Gson GSON = new Gson();
    private static final int RELOAD_TICKS = 20;

    // ── 策略参数（全部可被配置文件或 /cam 指令覆写）────────────────────────────
    private static volatile Mode mode = Mode.OFF;
    private static volatile String targetName = "";
    /** SHOULDER/CINEMATIC：镜头在目标背后多少格。 */
    private static volatile float distance = 4.0f;
    /** 镜头比目标眼睛高多少格（负数=俯视）。 */
    private static volatile float height = 0.0f;
    /** 肩部横移：正值把镜头推到目标右手边（经典越肩构图）。 */
    private static volatile float shoulder = 0.6f;
    /** ORBIT：绕目标的方位角（度），相对世界坐标，不跟目标转身。 */
    private static volatile float bearing = 35.0f;
    /** CINEMATIC：额外俯角偏置（度）。 */
    private static volatile float pitchBias = -8.0f;
    /** LOCK：镜头钉住的世界坐标。 */
    private static volatile double lockX, lockY, lockZ;
    /** 目标不可见时是否保持上一帧机位（不跟随）；false = 交还原版。 */
    private static volatile boolean holdWhenLost = true;

    /**
     * 指令改过内存状态之后，有多少 tick 内<b>不</b>再从文件重读。
     *
     * <p>为什么需要：{@code /cam mode ORBIT} 刚把内存设成 ORBIT，1 秒后 {@link #tick()}
     * 重读 cam.json 又把它覆写回去 —— 用户看着「指令没生效」。指令同时会
     * {@link #saveQuietly()} 写回文件，所以这段时间内跳过重读不会丢配置，只是避免
     * 两份真相打架。
     */
    private static volatile int holdFileTicks;

    // ── 跨帧状态 ──────────────────────────────────────────────────────────
    private static Entity target;
    private static Vec3 dampedPos;
    private static float dampedYaw = Float.NaN;
    private static float dampedPitch = Float.NaN;
    private static int tickCounter;
    private static long lastSeenMs;

    private CameraRig() {
    }

    public static Mode mode() {
        return mode;
    }

    public static float distance() {
        return distance;
    }

    public static float height() {
        return height;
    }

    public static float shoulder() {
        return shoulder;
    }

    public static float bearing() {
        return bearing;
    }

    public static float pitchBias() {
        return pitchBias;
    }

    public static double lockX() {
        return lockX;
    }

    public static double lockY() {
        return lockY;
    }

    public static double lockZ() {
        return lockZ;
    }

    public static boolean holdWhenLost() {
        return holdWhenLost;
    }

    public static String targetName() {
        return targetName;
    }

    /** 上一帧镜头位置，供 CINEMATIC 阻尼与调试取用。 */
    public static Vec3 dampedPosition() {
        return dampedPos;
    }

    public static boolean isActive() {
        return mode != Mode.OFF;
    }

    /**
     * 解析当前要跟拍的实体。
     *
     * <p><b>为什么不缓存</b>：实体表通常几十个，每帧线性扫一次成本可忽略，换来
     * 「实体卸载/重生立刻反映」，不会指向野实体。
     *
     * @return 目标实体；名字为空、找不到、或本地玩家自己时返回 null
     */
    public static Entity resolveTarget() {
        if (mode == Mode.OFF || targetName.isEmpty()) {
            target = null;
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            target = null;
            return null;
        }
        Entity found = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractClientPlayer p)) continue;
            if (mc.player.getUUID().equals(p.getUUID())) continue;   // 不能跟自己
            if (p.getGameProfile() != null
                    && targetName.equalsIgnoreCase(p.getGameProfile().getName())) {
                found = p;
                break;
            }
            if (targetName.equalsIgnoreCase(p.getName().getString())) {
                found = p;
                break;
            }
        }
        target = found;
        if (found != null) {
            lastSeenMs = System.currentTimeMillis();
        }
        return found;
    }

    public static Entity currentTarget() {
        return target;
    }

    /** 目标最后一次可见的时间戳（epoch ms）；从未见过返回 0。 */
    public static long lastSeenMs() {
        return lastSeenMs;
    }

    /** 每 tick 调一次：按节奏重读配置文件（指令刚改过的那一小段时间内跳过）。 */
    public static void tick() {
        if (holdFileTicks > 0) {
            holdFileTicks--;
            return;
        }
        if (++tickCounter < RELOAD_TICKS) return;
        tickCounter = 0;
        reload();
    }

    /** CINEMATIC 跨帧阻尼：朝目标机位收敛，避免逐帧硬切导致的跳变。 */
    public static void damp(Vec3 desired, float desiredYaw, float desiredPitch, float lambda) {
        if (dampedPos == null) {
            dampedPos = desired;
            dampedYaw = desiredYaw;
            dampedPitch = desiredPitch;
            return;
        }
        dampedPos = dampedPos.lerp(desired, lambda);
        dampedYaw = lerpAngle(dampedYaw, desiredYaw, lambda);
        dampedPitch = dampedPitch + (desiredPitch - dampedPitch) * lambda;
    }

    /** 最短路径角度插值（yaw 跨 ±180 时不能直接线性插，否则会绕远路甩镜头）。 */
    private static float lerpAngle(float from, float to, float t) {
        if (Float.isNaN(from)) return to;
        float diff = ((to - from + 540.0f) % 360.0f) - 180.0f;
        return from + diff * t;
    }

    /** 把当前实际机位记成阻尼起点（切模式/换目标时调，避免从旧机位飞过来）。 */
    public static void resetDamping() {
        dampedPos = null;
        dampedYaw = Float.NaN;
        dampedPitch = Float.NaN;
    }

    /** 配置文件路径。 */
    public static Path configPath() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameDirectory.toPath().resolve("config").resolve(CONFIG_DIR).resolve("cam.json");
    }

    /**
     * 重读配置文件。
     *
     * <p>解析失败<b>不抛</b> —— 配置文件是外部手写的，一次手滑不该让游戏崩。
     * 保留上一份有效配置继续跑。
     */
    public static void reload() {
        Path p = configPath();
        if (!Files.isRegularFile(p)) return;
        JsonObject o;
        try {
            o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return;
        }
        try {
            if (o.has("mode")) {
                Mode m = Mode.valueOf(o.get("mode").getAsString().trim().toUpperCase());
                if (m != mode) {
                    resetDamping();
                }
                mode = m;
            }
            if (o.has("target")) targetName = o.get("target").getAsString().trim();
            if (o.has("distance")) distance = o.get("distance").getAsFloat();
            if (o.has("height")) height = o.get("height").getAsFloat();
            if (o.has("shoulder")) shoulder = o.get("shoulder").getAsFloat();
            if (o.has("bearing")) bearing = o.get("bearing").getAsFloat();
            if (o.has("pitchBias")) pitchBias = o.get("pitchBias").getAsFloat();
            if (o.has("holdWhenLost")) holdWhenLost = o.get("holdWhenLost").getAsBoolean();
            if (o.has("lock")) {
                JsonObject l = o.getAsJsonObject("lock");
                lockX = l.get("x").getAsDouble();
                lockY = l.get("y").getAsDouble();
                lockZ = l.get("z").getAsDouble();
            }
        } catch (RuntimeException e) {
            // 字段类型不对（比如 distance 写成字符串）——同样保留旧配置，别崩。
        }
    }

    /** 写一份当前配置回去。 */
    public static void save() throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name());
        o.addProperty("target", targetName);
        o.addProperty("distance", distance);
        o.addProperty("height", height);
        o.addProperty("shoulder", shoulder);
        o.addProperty("bearing", bearing);
        o.addProperty("pitchBias", pitchBias);
        o.addProperty("holdWhenLost", holdWhenLost);
        JsonObject l = new JsonObject();
        l.addProperty("x", lockX);
        l.addProperty("y", lockY);
        l.addProperty("z", lockZ);
        o.add("lock", l);
        Path p = configPath();
        Files.createDirectories(p.getParent());
        Files.writeString(p, GSON.toJson(o), StandardCharsets.UTF_8);
    }

    /** 该实体是不是当前本地玩家（用于避免把玩家自己当跟拍目标）。 */
    public static boolean isLocalPlayer(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        return e instanceof Player && mc.player != null
                && mc.player.getUUID().equals(e.getUUID());
    }

    // ── /cam 指令用的写入口 ───────────────────────────────────────────────────
    //
    // 每个 setter 都做三件事：改内存 → 打断文件重读若干 tick → 写回 cam.json。
    // 「写回」是必须的：否则下次游戏启动时又读回旧值，用户会以为指令只是临时生效。

    private static void poke() {
        holdFileTicks = 2 * RELOAD_TICKS;
        tickCounter = 0;
    }

    /** 改完内存后落盘；落盘失败只记进返回值，不抛 —— 指令不该因为写不了文件就崩。 */
    private static String persist(String what) {
        try {
            save();
            return what;
        } catch (IOException | RuntimeException e) {
            return what + "（⚠ 写不回 cam.json：" + e.getClass().getSimpleName()
                    + " — 重启后会丢）";
        }
    }

    public static String setMode(Mode m) {
        if (m != mode) resetDamping();
        mode = m;
        return persist("机位模式 = " + m.name());
    }

    public static String setTarget(String name) {
        String n = name == null ? "" : name.trim();
        if (n.equalsIgnoreCase(targetName) && !n.isEmpty()) {
            return "已经盯着 " + n + " 了。";
        }
        targetName = n;
        resetDamping();
        // 顺带从 ORBIT 切起来：绑定了目标却停在 OFF 的话，看上去像「绑定没生效」。
        return persist(n.isEmpty() ? "解除了绑定。" : "绑定目标 = " + n
                + (mode == Mode.OFF ? "（顺手把模式从 OFF 切成 ORBIT，否则看不出效果）" : ""));
    }

    /** 绑定后如果模式还是 OFF，切成 ORBIT。 */
    public static void ensureActiveMode() {
        if (mode == Mode.OFF) {
            mode = Mode.ORBIT;
            resetDamping();
            poke();
        }
    }

    public static String setDistance(float v) {
        distance = clamp(v, 0.5f, 32.0f);
        return persist(String.format("距离 = %.2f", distance));
    }

    public static String setHeight(float v) {
        height = clamp(v, -16.0f, 32.0f);
        return persist(String.format("高度 = %.2f", height));
    }

    public static String setShoulder(float v) {
        shoulder = clamp(v, -8.0f, 8.0f);
        return persist(String.format("肩移 = %.2f", shoulder));
    }

    public static String setBearing(float v) {
        bearing = v;
        return persist(String.format("方位角 = %.1f°", bearing));
    }

    public static String setPitchBias(float v) {
        pitchBias = clamp(v, -90.0f, 90.0f);
        return persist(String.format("俯角偏置 = %.1f°", pitchBias));
    }

    public static String setHoldWhenLost(boolean v) {
        holdWhenLost = v;
        return persist("目标走丢时保持机位 = " + v);
    }

    /** 钉住机位。不传坐标就钉在本地玩家当前站的地方。 */
    public static String setLock(double x, double y, double z) {
        lockX = x;
        lockY = y;
        lockZ = z;
        if (mode != Mode.LOCK) {
            mode = Mode.LOCK;
            resetDamping();
        }
        return persist(String.format("机位已钉在 %.1f, %.1f, %.1f（模式 = LOCK）", x, y, z));
    }

    /** 立刻重读文件（外部脚本改完 cam.json 想马上生效时用）。 */
    public static String forceReload() {
        poke();
        reload();
        return "已重读 " + configPath() + "（mode=" + mode.name() + " target=" + targetName + "）";
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}