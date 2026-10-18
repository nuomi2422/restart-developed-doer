package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.accept.EntityView;
import com.dwinovo.numen.settlement.core.model.BlockBox;

import java.util.List;

/**
 * 过门自动关门（纯策略）——把同伴走过之后敞着的栅栏门关回去。
 *
 * <p><b>为什么需要它</b>：寻路器开门过路用的是原生右键（{@code gameMode.useItemOn}），
 * 走过去之后<b>没有任何东西负责关</b>。实测（2026-10-09）：新建的羊圈门在同伴穿过后
 * 一直是 {@code open=true}；一个敞着的门等于圈没围——羊会走出去，"引羊进圈"这件事
 * 从根上不成立。用户点名的两条（引羊 AC、牧场可用）都依赖它。
 *
 * <p><b>三条安全约束</b>（缺一条都会把同伴夹在门里或把圈拆了）：
 * <ol>
 *   <li><b>只关门、不开门</b>：开门是同伴自己的路权判断，自动化不许替她决定去哪。</li>
 *   <li><b>门洞里有人就不关</b>：同伴/动物正站在门口时关门＝把方块塞进她身体里。
 *       判据是"门格及其正上方一格内有没有实体"，宁可多等一刻。</li>
 *   <li><b>未加载不动手</b>：看不到就别猜（与 {@code WorldProbe} 的同一纪律）。</li>
 * </ol>
 *
 * <p>本类不依赖任何 Minecraft 类型，宿主把"是不是门、开没开、门洞有没有人"喂进来。
 */
public final class GateKeeper {

    private GateKeeper() {}

    /** 一次关门判定的结果（带原因，便于日志与测试断言）。 */
    public record Decision(boolean close, String reason) {

        public static Decision closeIt(String why) {
            return new Decision(true, why);
        }

        public static Decision keep(String why) {
            return new Decision(false, why);
        }
    }

    /**
     * 该不该把这扇门关上。
     *
     * @param loaded         该格所在区块是否加载
     * @param isGate         这一格是不是栅栏门/门这类可开关的方块
     * @param open           它当前是不是开着的
     * @param doorwayBlocked 门洞（门格 + 正上方一格）里有没有实体
     */
    public static Decision decide(boolean loaded, boolean isGate, boolean open, boolean doorwayBlocked) {
        if (!loaded) {
            return Decision.keep("未加载，不动手");
        }
        if (!isGate) {
            return Decision.keep("不是可开关的门");
        }
        if (!open) {
            return Decision.keep("门本来就是关的");
        }
        if (doorwayBlocked) {
            return Decision.keep("门洞里还有实体，关了会卡住它");
        }
        return Decision.closeIt("门敞着且门洞已空 → 关回去");
    }

    /**
     * 门洞是否被实体占住。
     *
     * <p><b>判据 = 实体的身体跨度与门格是否相交</b>，不是"脚在哪一格"。
     * 2026-10-09 实机踩到：同伴站在门口一块 <b>dirt_path</b> 上，而 dirt_path 的碰撞高
     * 只有 15/16，于是她的 {@code y = 66.9375}（比门格 68 低 1.06 格）——身体却正好在
     * 门格里。早先那版用 {@code y >= gateY - 0.5}（=67.5）当门槛，把这种情况判成"门洞空"，
     * 结果<b>把门关在了她身上</b>。半砖/耕地/雪层/台阶上都会重现同一个偏差。
     *
     * <p>所以这里用<b>保守的上下带</b>：脚落在 {@code [gateY-2, gateY+1]} 之内就算占住。
     * 2 格是玩家/大体型动物的身高上界（保守 = 宁可多等一刻，也不把方块塞进她身体里）；
     * 上界 {@code +1} 覆盖"站在门顶那一格"。
     *
     * <p>水平判据用"格中心 ± {@code tolerance}"而不是严格落格：实体位置是浮点，
     * 站在门槛上时中心可能压在相邻格边界。
     *
     * @param entities 附近实体（宿主已按盒筛过；这里再做一次精确判定）
     * @param gateX    门格世界坐标
     */
    public static boolean doorwayOccupied(List<EntityView> entities, int gateX, int gateY, int gateZ) {
        if (entities == null || entities.isEmpty()) {
            return false;
        }
        for (EntityView e : entities) {
            if (e == null) {
                continue;
            }
            double dx = Math.abs(e.x() - (gateX + 0.5));
            double dz = Math.abs(e.z() - (gateZ + 0.5));
            // 门格本身 1 格宽；站在门里时水平偏移 < 1 格
            if (dx >= 1.0 || dz >= 1.0) {
                continue;
            }
            // 身体跨度 [y, y+2) 与门格相交；写成带上下界的闭区间判据（见方法注释）。
            if (e.y() >= gateY - 2.0 && e.y() <= gateY + 1.0) {
                return true;
            }
        }
        return false;
    }

    /** 门洞的探测盒（门格 + 正上方一格），宿主用它去查附近实体。 */
    public static BlockBox doorwayBox(int gateX, int gateY, int gateZ) {
        return BlockBox.of(gateX, gateY, gateZ, gateX, gateY + 1, gateZ);
    }
}
