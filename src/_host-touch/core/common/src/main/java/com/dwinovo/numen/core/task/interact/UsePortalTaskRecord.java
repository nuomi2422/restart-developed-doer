package com.dwinovo.numen.core.task.interact;

import com.dwinovo.numen.task.TaskRecord;

/**
 * {@code use_portal} 的任务描述：<b>走到最近的下界/末地传送门并真的走进去</b>。
 *
 * <p><b>为什么需要它（2026-09-29 实机）</b>：同伴在下界时反复调
 * {@code goto(block=minecraft:nether_portal)}，每次都成功"走到门旁"，
 * 然后发现还在下界、再次调 {@code goto} —— 无限循环，永远回不去上界。
 * 根因是 {@code goto} 的契约就是<b>"走到旁边"</b>：它停���离门一格，
 * 从不 USE 那块门；而全仓<b>没有任何"跨维度传送"代码</b>
 * （搜 {@code NetherPortalBlock.process} / {@code changeDimension} 全无命中），
 * 传送门只能靠一次 USE 激活，而 {@code interact_at} 又不负责走过去。
 *
 * <p><b>为什么不改 goto 而另起一个工具</b>：{@code goto} 的"停在旁边"
 * 是被 place_block / break_block / mine 一整串任务依赖的契约（它们靠
 * "走到工作距离内"这个不变量工作）。给 goto 塞"到了就 USE"会在
 * "去工作台"与"进门"之间产生歧义。分开后两个意图各自单一、可描述。
 *
 * <p><b>维度语义</b>：门是<b>双向</b>的——用当前维度的门就回到另一维度。
 * 所以不传任何坐标，工具只在<b>同伴当前所在维度</b>里找门。
 * 坐标换算（下界 x/z 是上界的 1/8）由原版传送门机制负责，本工具不猜坐标。
 */
public final class UsePortalTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "use_portal";

    /** 找下界门（唯一支持的类型）。 */
    public static final String KIND_NETHER = "nether";

    /** 走进门后等原版传送的额外 tick。 */
    public static final int KIND_SETTLE_DEFAULT = 0;

    public final String kind;

    /** 找到后停这么多 tick 再进：门刚点燃时原版有 0.8 秒不可用，立刻进会被静默吞掉。 */
    public final int settleTicks;

    public UsePortalTaskRecord(String toolCallId, long deadlineGameTime, String kind, int settleTicks) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        String asked = (kind == null || kind.isBlank()) ? KIND_NETHER : kind.trim().toLowerCase(java.util.Locale.ROOT);
        // 2026-09-30 深审 R02/codex P1：end 门**从契约移除**而不是留一个必败的分支。
        // 末地门是 end_portal（EndPortalBlock），本任务只会认 NetherPortalBlock ——
        // 留个 kind=end 会让模型以为能用，真跑起来却在"门消失→重扫"里耗尽预算。
        // 末地门需要"先用 eye_of_ender 激活方块"这套前置，做完整是另一个功能。
        // 诚实拒绝 > 挂一个看起来能用、实际必败的能力。
        if (!KIND_NETHER.equals(asked)) {
            throw new IllegalArgumentException("use_portal only supports kind='"
                    + KIND_NETHER + "' (a nether portal — it is the way home from the nether"
                    + " and the way in from the overworld). kind='" + asked + "' is not supported:"
                    + " end portals must be activated with an eye_of_ender first and are not"
                    + " handled by this tool. Use goto/interact_at for end-portal work.");
        }
        this.kind = asked;
        this.settleTicks = Math.max(0, Math.min(settleTicks, 200));
    }

    /** 目标方块 id（与 {@code goto} 的 block 参数同口径）。 */
    public String targetBlock() {
        return "minecraft:nether_portal";
    }

    @Override
    public String describe() {
        return "走下界门（回上界 / 进下界）" + (settleTicks > 0 ? "，门前等 " + settleTicks + " tick 再进" : "");
    }
}
