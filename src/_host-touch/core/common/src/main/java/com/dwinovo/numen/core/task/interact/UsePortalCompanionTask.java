package com.dwinovo.numen.core.task.interact;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 走到最近的传送门并 USE 它——真正完成一次跨维度。
 *
 * <p>形状：扫描（{@link BlockSearch}，与 {@code goto} 的 FIND / {@code scan_blocks}
 * 同一条路）→ 寻路走到门旁 → 静置 {@link UsePortalTaskRecord#settleTicks} → USE。
 *
 * <p><b>为什么不能只靠 goto</b>（2026-09-29 实机）：goto 的契约是「走到旁边」，
 * 它停在门旁就宣告完成；而跨维度只有 USE 才触发。症状是同伴在门前来回重复
 * {@code goto(block=minecraft:nether_portal)}，永远出不去。
 *
 * <p><b>为什么静置</b>：原版传送门被点燃后有 0.8 秒（16 tick）不可用，
 * 立刻 USE 会被原版静默吞掉——表现为「我按了但没反应」，模型于是反复重试。
 */
public final class UsePortalCompanionTask extends AbstractCompanionTask<UsePortalTaskRecord> {

    /** 搜索半径（格）。门不会在更远的地方有意义。 */
    private static final int SEARCH_RADIUS = 96;
    /** 要几个候选：最近那个可能已被点燃过/被岩浆糊掉，留备胎。 */
    private static final int WANT = 4;
    /**
     * 进门判据：身体<b>真的站进了门块里</b>。
     *
     * <p>2026-09-29 实机修正：我第一版是"走到门旁 + USE 一次"。实测
     * {@code use_portal USED portal at 59,56,-43 ... result=PASS}，同伴站在
     * (62,46,-43) 满血吃饭，<b>人还在下界</b>。原因：原版下界传送门
     * <b>不能被 USE</b> —— 它不是可交互方块，{@code use()} 直接 PASS；
     * 真正的传送发生在实体<b>走进门块</b>时（{@code NetherPortalBlock.entityInside}
     * → {@code PortalShape} 安排延迟传送）。所以本任务的重点是
     * <b>把人送进那个方块里，然后等原版做它的事</b>，不需要也不该自己调 use。
     */
    private static final double WALK_SPEED = 1.0;
    /** 走进门里之后，原版要几 tick 才把坐标换算到另一维度。 */
    private static final int VANILLA_TELEPORT_TICKS = 90;
    /** 门消失后重扫的上限，防止「门被人反复拆了」时无限重扫。 */
    private static final int MAX_RESCANS = 3;
    /**
     * 「走到门口」的预算（**按距离放宽**，见 {@link #approachBudgetTicks()}）。
     *
     * <p>2026-09-30 深审 R02/codex P1-4：旧值 {@code ENTER_TIMEOUT_TICKS}=200 被同时用来
     * 卡「从 96 格外找到门一路走过去」——门在几百格外时**根本走不到**就超时了。
     * 现在拆开：接近（随距离放宽）与进门后等待（固定），且各自独立计时。
     */
    private static final long APPROACH_BASE_TICKS = 200;   // 10 秒起：贴脸也得给点
    private static final long APPROACH_MAX_TICKS = 3600;   // 3 分钟封顶：不无限占身体
    private static final long TICKS_PER_BLOCK = 4;         // 走 1 格约 0.2s 的粗算
    /** 走进门里之后的额外等待上限（原版传送本身只要 ~90 tick）。 */
    private static final int ENTER_TIMEOUT_TICKS = 90;

    private final UsePortalTaskRecord rec;
    private final Block target;
    private final ResourceKey<Level> startDim;

    private int scanId;
    private List<BlockScanner.Hit> hits;
    private BlockPos door;
    private int rescans;
    /**
     * 两个**分开**的计时起点（2026-09-30 深审 R02/codex P1-4）。
     *
     * <p>旧代码只有一个 {@code enterSince}（找到门时写），于是"走路"和"进门后等传送"
     * 共用同一笔预算：走到门口花掉 150 tick 后，真正进门只剩 30 tick 就报"没传送"，
     * 而人其实就站在门里。
     *
     * <ul>
     *   <li>{@link #approachSince} —— 找到门、开始走过去；</li>
     *   <li>{@link #enterSince} —— <b>首次</b>站进门块那一刻（每次重扫/重新进门重写）。</li>
     * </ul>
     */
    private long approachSince;
    /**
     * 接近预算（<b>在选定门的那一刻定死</b>，深审/codex P1-3）。
     *
     * <p>如果每次 tick 按"当前剩余距离"重算，就会出现"越走预算越少"：
     * 96 格外起步预算 584 tick，走 350 tick 后剩 26 格、预算缩到 304 tick，
     * 正常接近反而被判超时。
     */
    private long approachBudget;
    private long enterSince;

    public UsePortalCompanionTask(NumenPlayer player, UsePortalTaskRecord record) {
        super(player, record);
        this.rec = record;
        this.target = BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.parse(record.targetBlock()));
        this.startDim = player.level().dimension();
    }

    @Override
    protected void onStart() {
        kickScan();
        Constants.LOG.info("[numen-task] use_portal start kind={} in {} target={}",
                rec.kind, startDim.location(), rec.targetBlock());
    }

    private void kickScan() {
        if (!(player.level() instanceof ServerLevel level)) {
            fail("no server level for portal search", FailureType.UNKNOWN);
            return;
        }
        var feet = BlockHelper.playerFeet(level, player.getX(), player.getY(), player.getZ());
        scanId = BlockSearch.start(player.getUUID(), level, feet, SEARCH_RADIUS, WANT,
                Set.of(target), res -> {
                    scanId = 0;
                    hits = res.matches();
                });
    }

    @Override
    protected TaskState onTick() {
        // 已经进了另一维度 → 收工
        if (player.level().dimension() != startDim) {
            return TaskState.SUCCESS;
        }
        if (door == null) {
            if (scanId == 0 && hits == null) {
                fail(noPortalHere(), FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            if (hits != null) {
                hits.stream()
                        .sorted((a, b) -> Double.compare(a.distance(), b.distance()))
                        .map(h -> h.pos().immutable())
                        .findFirst()
                        .ifPresent(p -> door = p);
                hits = null;
            }
            if (door == null) return TaskState.RUNNING;
            approachSince = player.level().getGameTime();
            // 2026-09-30 深审/codex P1-3：**接近预算在这一刻定死**。
            // 原来每次都按"当前剩余距离"重算 —— 走了 350 tick、还剩 26 格时预算从 584 缩到 304，
            // 正常接近也会被判超时。预算是"出发时估的"，不是"越走越少"。
            approachBudget = approachBudgetTicks();
            Constants.LOG.info("[numen-task] use_portal found portal at {} (approach budget {}s)",
                    door.toShortString(), approachBudget / 20);
        }
        if (door == null || !stillPortal()) {
            return lostPortal();
        }
        if (insidePortal()) {
            // 站进去了。剩下的交给原版：entityInside → PortalShape 会在几十 tick 后换维度。
            // 进门计时**从这一刻起算**，不含之前走路那段（深审 R02/codex P1-4）。
            if (enterSince == 0) {
                enterSince = player.level().getGameTime();
                Constants.LOG.info("[numen-task] use_portal inside the portal at {}, waiting for vanilla",
                        door.toShortString());
            }
            long insideFor = player.level().getGameTime() - enterSince;
            if (insideFor > VANILLA_TELEPORT_TICKS + ENTER_TIMEOUT_TICKS) {
                fail("standing inside the portal at " + door.toShortString() + " for "
                        + (VANILLA_TELEPORT_TICKS + ENTER_TIMEOUT_TICKS) + " ticks and it did not"
                        + " move me. A nether portal needs a matching one on the other side, and"
                        + " it is usually built from obsidian — check with inspect_block that this"
                        + " frame is obsidian and complete (a 4x5 minimum frame, no missing corners).",
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            return TaskState.RUNNING;      // 等原版传送
        }
        // 还没进去：在走。**导航必须每 tick 驱动**（深审 R01：
        // 旧代码 walkToward() 每 tick 重建一个 PlayerNav，而含 nav.tick() 的
        // driveNav() 从未被调用 → 身体一步都不走，只会在超时后失败）。
        if (player.level().getGameTime() - approachSince > approachBudget) {
            stopNav();
            fail("could not walk into the portal at " + door.toShortString()
                    + " — still out of reach after " + (approachBudget / 20) + "s. "
                    + describeBlockers(door)
                    + (describeBlockers(door).isEmpty() ? "" : " — mine/break those first. ")
                    + "It may also simply be too far (search reaches " + SEARCH_RADIUS
                    + " blocks, walking is capped at " + (APPROACH_MAX_TICKS / 20)
                    + "s — walk closer first and call this again).",
                    FailureType.OUT_OF_REACH);
            return TaskState.FAILED;
        }
        return driveNav();
    }

    /**
     * 接近预算：<b>按距离放宽</b>（深审 R02/codex P1-4 指出固定 200 tick 与注释不符）。
     *
     * <p>直线距离 + 固定余量，再夹在 [1×, 3×] 之间：贴着走给 10 秒，
     * 96 格外也最多只给 3 分钟，不会无限占着身体（单驾驶员铁律）。
     */
    private long approachBudgetTicks() {
        double dist = door == null ? 0 : Math.sqrt(player.distanceToSqr(
                door.getX() + 0.5, door.getY() + 0.5, door.getZ() + 0.5));
        long budget = (long) (dist * TICKS_PER_BLOCK) + APPROACH_BASE_TICKS;
        return Math.max(APPROACH_BASE_TICKS, Math.min(budget, APPROACH_MAX_TICKS));
    }

    /**
     * 身体是不是真的站在门块里（原版传送的唯一触发条件）。
     *
     * <p>2026-09-30 深审 R02：旧实现把「门周围 6 格的空气」也算"进去了"，
     * 于是身体还在门外就已经开始等原版传送，等到超时才失败 —— 白等 180 tick。
     * 现在只认<b>身体包围盒真正与门块相交</b>，这才是 {@code NetherPortalBlock.entityInside} 的判据。
     */
    private boolean insidePortal() {
        if (door == null) return false;
        var body = player.getBoundingBox();
        return player.level().getBlockState(door).getBlock() instanceof NetherPortalBlock
                && body.intersects(new net.minecraft.world.phys.AABB(door));
    }

    private boolean stillPortal() {
        return player.level().getBlockState(door).getBlock() instanceof NetherPortalBlock;
    }

    /** 门被拆/被熄：重扫，但有上限。 */
    private TaskState lostPortal() {
        Constants.LOG.warn("[numen-task] use_portal: {} is no longer a burning portal", door.toShortString());
        if (++rescans > MAX_RESCANS) {
            fail("found a portal at " + door.toShortString() + " but it stopped being one after "
                    + rescans + " rescans (someone is breaking it, or lava is dousing it)."
                    + " Build a fresh one with 10 obsidian and flint&steel, wait for it to"
                    + " light, then call this again.", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        stopNav();
        door = null;
        hits = null;
        enterSince = 0;          // 换了一扇门 → 进门计时重新开始
        kickScan();
        return TaskState.RUNNING;
    }

    private String noPortalHere() {
        return "no " + rec.targetBlock() + " within " + SEARCH_RADIUS + " blocks in "
                + startDim.location() + ". A portal only exists where someone built it — in the"
                + " nether it is your way HOME, so you must already be in the nether with the"
                + " portal you came in through. get_world_info shows which dimension you are in.";
    }

    /**
     * 走进门块里（不是停在旁边），并且**每 tick 驱动它**。
     *
     * <p>2026-09-30 深审 R01（codex 抓出并复现）：旧代码 {@code walkToward()} 每 tick
     * 新建一个 PlayerNav，而唯一含 {@code nav.tick()} 的 {@code driveNav()} 从未被调用 ——
     * 父类 {@code AbstractCompanionTask} 只调 onTick，不会替我们跑导航。
     * 症状：门找到了、日志打了 "walking to portal"、身体一步不动，直到超时失败。
     *
     * <p>现在：<b>只在这里建一次</b>，之后每 tick 调 {@link #driveNav()}。
     * 用 TERRAFORM 允许开路（下界门常被岩浆围着），目标格由 standOn 标 sacred，
     * 路线不会把门本身挖掉。
     */
    private TaskState driveNav() {
        if (nav == null) {
            var compiled = GoalCompiler.standOn(door);
            nav = PlayerNav.to(player, () -> compiled, WALK_SPEED, this::insidePortal,
                    PlayerNav.ContextProvider.TERRAFORM);
            Constants.LOG.info("[numen-task] use_portal walking to portal at {} ({} blocks away)",
                    door.toShortString(), (int) Math.sqrt(player.distanceToSqr(
                            door.getX() + 0.5, door.getY() + 0.5, door.getZ() + 0.5)));
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                yield TaskState.RUNNING;   // 下一 tick 走 insidePortal() 分支等原版传送
            }
            case FAILED -> {
                String why = nav.failReason();
                stopNav();
                if (insidePortal()) {
                    yield TaskState.RUNNING;   // 已经站进去了，别被寻路口径判死
                }
                fail(portalBlockedHint(door, why), FailureType.NO_PATH);
                yield TaskState.FAILED;
            }
        };
    }

    /**
     * 寻路打不通时给出**能照着做的**诊断，而不是干巴巴一句 "no path"（2026-09-30 实机）。
     *
     * <p>实机：主人随手在上界搭了个下界门（415,71,-300），门只有 <b>2 格高</b>（标准是 4 格）、
     * 且 416,71,-300 是黑曜石把路堵死。同伴在 418 处报告
     * "no path to target (about 3 blocks away)" —— 事实正确但**完全不可执行**：
     * 模型/主人不知道该挖哪、也不知道门根本不合格。
     *
     * <p>所以这里把三件事说清：门到哪、有没有东西挡着、门本身完不完整。
     */
    private String portalBlockedHint(BlockPos door, String navWhy) {
        StringBuilder sb = new StringBuilder();
        sb.append("could not walk into the portal at ").append(door.toShortString())
          .append(": ").append(navWhy).append(". ");
        // 门周围一圈：谁挡着路
        String blockers = describeBlockers(door);
        if (!blockers.isEmpty()) {
            sb.append("The way in is blocked by ").append(blockers).append(". ");
        }
        // 门框完整性：竖井至少 2 格、横向至少 3 格才算合格
        if (!portalFrameLooksComplete(door)) {
            sb.append("This portal also looks INCOMPLETE: a nether portal needs a 4x5 obsidian"
                    + " frame with a 3-wide by 4-tall opening (only obsidian may touch the"
                    + " inside). A partial frame like this one will not carry you through."
                    + " Rebuild it properly, or dig out the blocked side.");
        } else {
            sb.append("The frame looks complete, so the obstacle is physical: dig or build your way in.");
        }
        return sb.toString();
    }

    /** 门周围一圈里挡住通路的方块（人话，限 3 个）。 */
    private String describeBlockers(BlockPos door) {
        List<String> out = new ArrayList<>();
        for (var d : new net.minecraft.core.Direction[]{
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST,
                net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN}) {
            var p = door.relative(d);
            var st = player.level().getBlockState(p);
            if (st.isAir() || st.getBlock() instanceof NetherPortalBlock) continue;
            out.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(st.getBlock()).toString() + " at " + p.getX() + "," + p.getY() + "," + p.getZ());
            if (out.size() >= 3) break;
        }
        return String.join("; ", out);
    }

    /**
     * 门框看着完整吗：门内竖井 ≥2 格、横向 ≥3 格门格。
     *
     * <p>不够严谨（不解析 obsidian 框架的角），但足以抓实机里那种"随手 2 格高的假门" ——
     * 那种门本来就走不通，报"不完整"比报"没路"有用。
     */
    private boolean portalFrameLooksComplete(BlockPos door) {
        var level = player.level();
        int horizontal = 0;
        for (var d : new net.minecraft.core.Direction[]{
                net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST,
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH}) {
            if (level.getBlockState(door.relative(d)).getBlock() instanceof NetherPortalBlock) horizontal++;
        }
        if (horizontal < 2) return false;
        int vertical = 0;
        for (int dy = -1; dy <= 1; dy++) {
            if (level.getBlockState(door.above(dy)).getBlock() instanceof NetherPortalBlock) vertical++;
        }
        return vertical >= 2;
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        if (scanId != 0) {
            BlockSearch.cancel(scanId);
            scanId = 0;
        }
    }

    @Override
    protected String successMessage() {
        return "went through the portal at " + (door == null ? "?" : door.toShortString());
    }
}
