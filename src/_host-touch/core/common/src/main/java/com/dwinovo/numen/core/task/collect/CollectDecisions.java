package com.dwinovo.numen.core.task.collect;

/**
 * 拾取对账的纯判据——不碰 Minecraft(同 {@code SurvivalDecisions} 的做法:能无头单测)。
 *
 * <h2>为什么要有这一层</h2>
 * 掉落物<b>消失 ≠ 本人拿到</b>。它可能被烧掉、掉进岩浆、被别人捡走、或被原版 despawn。
 * 旧实现只看见 {@code ItemEntity.isRemoved()} 就把 {@code collected} 加一,于是工具报
 * "扫完了 n 件"而背包里根本没有 n 件;上层(规划器)看到 SUCCESS 就打勾,"黑曜石挖了不捡"
 * 就是这样被当成完成的。
 *
 * <p>所以归属只能由<b>背包增量</b>证明:出发前记下这件东西的持有量,东西消失后再读一次,
 * 涨了才算战果。这是 {@code MineCompanionTask} 早就在用的同一套机制(那边叫 baseline),
 * 这里只是把它补到"按件对账"的粒度上。
 *
 * <p>方向性:这套判据只会<b>少报</b>,不会多报(见 {@link #classify} 里 delta 的取用)。
 * 少报的代价是多走一趟,多报的代价是上层误判完成——后者才是要治的病。
 */
public final class CollectDecisions {

    private CollectDecisions() {}

    /** 一次接近的收尾判据。 */
    public enum Outcome {
        /** 东西进了背包——这才算 collected。 */
        CREDITED,
        /** 东西没了但背包没涨:被烧/被抢/超时消失,不是我们的战果。 */
        VANISHED,
        /** 还在那儿(走到了却没吸上),本轮跳过它,别在同一件上空转。 */
        STILL_THERE,
        /** 寻路失败,够不着,放弃这一件。 */
        UNREACHABLE
    }

    /**
     * 给一次接近收尾。
     *
     * @param targetRemoved 目标掉落物是否已从世界里消失
     * @param inventoryDelta 从出发到此刻,背包里<b>这一种物品</b>的净增量(可负:被消耗)
     * @return 四种收尾之一
     */
    public static Outcome classify(boolean targetRemoved, int inventoryDelta) {
        if (!targetRemoved) {
            return Outcome.STILL_THERE;
        }
        return inventoryDelta > 0 ? Outcome.CREDITED : Outcome.VANISHED;
    }

    /**
     * 一次接近最多能记几件。
     *
     * <p>刻意<b>只记 1</b>:一个 {@code ItemEntity} 是一"件掉落",哪怕它是个 64 组的堆、
     * 哪怕同一瞬间别的来源也进了包。少记的代价是多走一趟(安全),多记的代价是虚报完成
     * (要治的病)。真要按堆记,得先有"这堆是谁的"这个我们目前拿不到的证据。
     */
    public static int creditFor(Outcome outcome) {
        return outcome == Outcome.CREDITED ? 1 : 0;
    }

    /**
     * 一趟扫完,是不是"该拿的都拿到了"。
     *
     * <p>给上层一个不用去解析文案就能判的布尔:有东西在到达前消失、够不着、或者走到了
     * 却没吸上,这一趟就<b>不是</b>干净完成。终态仍是 SUCCESS(工具契约没改),但这个
     * 标志和 {@code resultData} 里的分项计数让"扫完了"和"拿到了"能被上层分开看。
     *
     * @param reachedEnd 是否真的扫到了"再没有候选"那一步。
     *                   <b>必须有这一条</b>:超时或被取消时任务根本没走完一圈,
     *                   三个缺口计数都还是 0,只看缺口就会把"一件都没拿"报成
     *                   {@code all_picked_up=true} —— 恰好是本类要治的那个病。
     * @param vanished 消失但不是我们拿到的件数
     * @param unreachable 寻路够不着的件数
     * @param leftBehind 走到了却没吸上、被本轮跳过的件数
     */
    public static boolean sweepComplete(boolean reachedEnd, int vanished, int unreachable, int leftBehind) {
        return reachedEnd && vanished == 0 && unreachable == 0 && leftBehind == 0;
    }
}
