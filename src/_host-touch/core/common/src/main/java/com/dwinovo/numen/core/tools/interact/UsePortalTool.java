package com.dwinovo.numen.core.tools.interact;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.interact.UsePortalTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

import static com.dwinovo.numen.task.TaskDispatch.runSync;

/**
 * {@code use_portal}：找到最近的传送门，走过去，并真的走进去。
 *
 * <p><b>为什么需要它</b>（2026-09-29 实机）：在被传送门困住时，同伴会反复
 * {@code goto(block=minecraft:nether_portal)} —— 每次都"成功走到门旁"，
 * 然后发现自己还在原地维度、再来一次，无限循环。
 * {@code goto} 的契约是「走到旁边」，跨维度只有 USE 才触发。
 * 本工具把「找到 → 走过去 → 进去」合成一步。
 *
 * <p><b>用它的时机</b>：你<b>想换维度</b>时。在下界要回上界、上界要去下界、
 * 末地门同理。门是双向的，用当前维度的门就回另一维度。
 *
 * <p><b>它不做什么</b>：不搭门。要门就得先自己用黑曜石 + 打火石搭好并点燃
 * （{@code place_block} / {@code goto} + {@code interact_at}），然后本工具才找得到。
 */
public final class UsePortalTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /**
     * 总任务预算（游戏 tick，2 分钟）。
     *
     * <p>取 2400 tick 是因为「走 96 格 + 开门 + 等传送」的总和必须落在同一个预算里；
     * 传送本身的等待只有 ~90 tick，多出来的全是走路。
     * <b>必须是 tick</b>：{@code TaskSlot} 用 {@code level().getGameTime()} 比 deadline（见 onServerCall 注释）。
     */
    private static final long DEADLINE_TICKS = 20L * 120L;

    private record Args(String kind, Integer settle_ticks) {}

    @Override
    public String name() {
        return "use_portal";
    }

    @Override
    public String description() {
        return "GO THROUGH A NETHER PORTAL — find the nearest one in the dimension you are in,"
                + " walk to it, and step INTO it. This is the way to change between the overworld"
                + " and the nether: a nether portal is two-way, so call it when you are in the"
                + " nether and want home, or in the overworld and want the nether."
                + "\nDo NOT use goto for this: goto stops BESIDE a block, and a portal only moves"
                + " you when you physically stand inside it."
                + "\nNot supported: END portals (they must be activated with an eye_of_ender first)."
                + " It does NOT build a portal — if none exists within range it says so; build one"
                + " with 10 obsidian + flint and steel first (place_block, then interact_at to"
                + " light it), then call this again. The search reaches 96 blocks, but the"
                + " walking budget is capped (3 minutes); if the nearest portal is farther, walk"
                + " closer first and call this again from there.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("kind", "Only 'nether' is supported. A nether portal takes you between"
                        + " the overworld and the nether (two-way). End portals are NOT handled"
                        + " here (they must be activated with an eye_of_ender first).",
                        "nether")
                .nullableInteger("settle_ticks", "Ticks to stand beside the portal before"
                        + " entering. Use 20-40 right after lighting a NEW portal — it cannot be"
                        + " used for its first second. Null/0 = enter as soon as you arrive"
                        + " (right for an already-burning portal).")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args == null ? new JsonObject() : args, Args.class);
        String kind = a == null || a.kind() == null || a.kind().isBlank() ? "nether" : a.kind();
        int settle = a == null || a.settle_ticks() == null ? 0 : a.settle_ticks();
        // 2026-09-30 深审 R02：deadline 必须是**游戏 tick**，不是墙钟毫秒。
        // TaskSlot 用 `level().getGameTime() >= record.getDeadlineGameTime()` 判超时，
        // 旧代码传 `System.currentTimeMillis()+120000`（约 1.7e12）→ 这个 deadline 永远达不到，
        // 任务既不会超时、也不会让出身体（runSync 挂同步槽），能把同伴卡住整局。
        long deadlineTicks = companion.level().getGameTime() + DEADLINE_TICKS;
        UsePortalTaskRecord record = new UsePortalTaskRecord(toolCallId, deadlineTicks, kind, settle);
        runSync(companion, record, reply);
    }
}
