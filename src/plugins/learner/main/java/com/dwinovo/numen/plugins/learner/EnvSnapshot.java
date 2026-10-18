package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「固化环境」：在写备忘录的那一刻，把同伴所处世界的关键状态拍成一张
 * <b>能被 {@code Memo.parseKeyValues} 认出来的</b>键值串。
 *
 * <p><b>为什么要有这个类（38号v3 §2.2 实现约束 1）</b>：
 * 「固化环境」是<b>系统的义务</b>，不是调用方的记忆义务。用户 2026-10-01 口述的学习者职责里
 * 明确含「那一刻会固化环境」；而原实现把 {@code snapshot} 设成 optional 交给调用方传，
 * 结果 2026-10-01 实机抓到：没传就上报 {@code carrier_hp=-1}（哨兵漏成数据）。
 *
 * <p><b>输出格式的硬约束</b>（{@code Memo.parseKeyValues} 只按 {@code , ;} 切、
 * <b>不能按空格切</b> —— 那是 2026-09-29 修过的真 bug）：
 * {@code key=value} 对、逗号分隔、值只含字母数字与 {@code _ . / -}。
 *
 * <p><b>红线</b>：本类<b>只读</b>。不移动、不攻击、不建造、不注册任何工具
 * → RL-1 / RL-9 不受影响。⚠️ 也<b>不 import {@code core.common}</b> 的
 * {@code Menace}/{@code Battlefield}/{@code Loadout}（它们在插件 compileOnly 类路径外，
 * {@code numen-plugin.gradle:37-44} 刻意封死跨插件 import）——
 * 「附近有没有敌对」这里用原版 {@code Enemy} 接口自己判。
 *
 * <p><b>线程</b>：读世界状态必须在服务端主线程。调用方用
 * {@link #onServerThread} 判断并切线程，<b>不要</b>直接调 {@link #capture}。
 */
final class EnvSnapshot {

    private EnvSnapshot() {
    }

    /** 采 nearby 的半径（格）。与「被什么围住了」这个判断的用途相称，不做全图扫描。 */
    private static final double NEARBY_RADIUS = 16.0D;

    /** nearby 最多列几个实体名，防止快照无限长。 */
    private static final int NEARBY_MAX = 6;

    /**
     * 如果当前不在服务端主线程，切过去再跑。
     *
     * <p>为什么需要：框架用 {@code dispatchAsync} 触发工具，<b>不保证</b>在主线程；
     * 而 {@code level().getEntities(...)} / 读背包离开主线程都不安全。
     */
    static void onServerThread(NumenPlayer companion, Runnable body) {
        if (companion == null || companion.level() == null || companion.level().getServer() == null) {
            return;
        }
        var server = companion.level().getServer();
        if (server.isSameThread()) {
            body.run();
        } else {
            server.execute(body);
        }
    }

    /**
     * 拍一张环境快照。**必须在服务端主线程调用**（见 {@link #onServerThread}）。
     *
     * @return 形如 {@code hp=6/20, food=11, armor=iron_chestplate, weapon=none,
     *         nearby=zombie, hostile=true, dim=overworld, pos=12,64,-30}；
     *         同伴不可用时返回空串（调用方据此按「缺失」处理，见 B21）
     */
    static String capture(NumenPlayer p) {
        if (p == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        // 血量：hp=<当前>/<上限>，parseKeyValues 能从 hp= 与 N/M 两种写法里取到
        int hp = (int) Math.ceil(p.getHealth());
        int maxHp = (int) Math.ceil(p.getMaxHealth());
        sb.append("hp=").append(hp).append('/').append(maxHp);

        // 饥饿：D1「饿」的客观信号。用户 2026-10-30 口述的 D1 死因就是它
        sb.append(", food=").append(p.getFoodData().getFoodLevel());

        // ★ 2026-10-06 补：背包（含副手）里**可食用**物品的总个数。
        //   food（饱食度 0-20）≠ food_items（背包里有没有吃的）：
        //   饱食度高也可能一颗都没有（刚吃完），饱食度低也可能塞满面包。
        //   取不到写 -1（未知）**不写 0** —— 0 是「真的没有」这个真值（DL-4：UNKNOWN ≠ 0）。
        //   按 Item.isEdible() 判而不是按名字：这是模组整合包，食物大多不叫 *_food。
        sb.append(", food_items=").append(edibleCount(p));

        // 护甲：有哪件算什么；四件全空才写 none（这正是 2026-09-29 修过的「armor=none 被 contains 判成有护甲」的反面）
        String chest = itemPath(p.getItemBySlot(EquipmentSlot.CHEST));
        String legs = itemPath(p.getItemBySlot(EquipmentSlot.LEGS));
        String head = itemPath(p.getItemBySlot(EquipmentSlot.HEAD));
        String boots = itemPath(p.getItemBySlot(EquipmentSlot.FEET));
        if (!chest.isEmpty()) {
            sb.append(", armor=").append(chest).append(", chestplate=").append(chest);
        }
        if (!legs.isEmpty()) {
            sb.append(", leggings=").append(legs);
        }
        if (!head.isEmpty()) {
            sb.append(", helmet=").append(head);
        }
        if (!boots.isEmpty()) {
            sb.append(", boots=").append(boots);
        }
        if (chest.isEmpty() && legs.isEmpty() && head.isEmpty() && boots.isEmpty()) {
            sb.append(", armor=none");
        }

        // 武器：主手。空则 none，让携带器能判出「该带武器获取类工具」
        String main = itemPath(p.getMainHandItem());
        if (main.isEmpty() || main.equals("air")) {
            sb.append(", weapon=none");
        } else {
            sb.append(", weapon=").append(main);
        }

        // ★ E2：床（睡觉可行性）。按 BedItem 语义判；读不到写 -1（未知）**不写 0**。
        sb.append(", bed=").append(bedCount(p));

        // 附近实体：只列名字；有没有敌对单独给 hostile 标志
        List<String> names = new ArrayList<>();
        boolean hostile = false;
        int nearestCreeper = -1;
        boolean creeperIgnited = false;
        try {
            var box = p.getBoundingBox().inflate(NEARBY_RADIUS);
            for (Entity e : p.level().getEntities(p, box, x -> true)) {
                if (e == null || e == p) {
                    continue;
                }
                if (e instanceof Enemy) {
                    hostile = true;
                }
                // ★ E2：苦力怕（闹钟数据）。距离 + 点燃态（getSwellDir()>0 = 引信已响）。
                if (e instanceof Creeper c) {
                    int d = (int) Math.round(Math.sqrt(p.distanceToSqr(c)));
                    if (nearestCreeper < 0 || d < nearestCreeper) {
                        nearestCreeper = d;
                    }
                    if (c.isIgnited() || c.getSwellDir() > 0) {
                        creeperIgnited = true;
                    }
                }
                if (names.size() < NEARBY_MAX) {
                    String n = safeName(e);
                    if (!n.isEmpty()) {
                        names.add(n);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 世界正在卸载 / 区块未加载时 getEntities 可能抛；缺这一项不该让整次快照失败
        }
        if (!names.isEmpty()) {
            sb.append(", nearby=").append(String.join("+", names));
        }
        sb.append(", hostile=").append(hostile);
        // ★ E2：只有范围内有苦力怕才写这两个键；没有 = 键缺失 = 闹钟不触发（脱离即恢复）。
        if (nearestCreeper >= 0) {
            sb.append(", creeper=").append(nearestCreeper)
              .append(", ignited=").append(creeperIgnited);
        }

        // 维度与位置：position 便于复现「在哪出的问题」
        try {
            sb.append(", dim=").append(p.level().dimension().location().toString().toLowerCase(Locale.ROOT));
            sb.append(", pos=").append((int) p.getX()).append(',').append((int) p.getY()).append(',').append((int) p.getZ());
        } catch (RuntimeException ignored) {
            // 同上：维度信息拿不到就不写，不编造
        }
        // ★ E2：时间/夜晚。night 只在有昼夜循环的维度写（下界/末地不写 →
        //   闹钟不会错误建议睡觉；这是「非主世界不错误睡觉」的采样端保证）。
        try {
            sb.append(", time=").append((int) (p.level().getDayTime() % 24000L));
            if (!p.level().dimensionType().hasFixedTime()) {
                sb.append(", night=").append(p.level().isNight() ? "1" : "0");
            }
        } catch (RuntimeException ignored) {
            // 时间拿不到就不写，不编造
        }
        return sb.toString();
    }

    /**
     * 背包（含副手）里<b>可食用</b>物品的总个数。
     *
     * <p>按食物数据组件（{@code DataComponents.FOOD}）判，不按名字判 ——
     * 模组食物大多不叫 {@code *_food} / {@code bread}，按名字猜会漏掉一整片，
     * 而漏掉的后果是「系统说没食物，其实有一背包」。
     *
     * <p>范围：{@code getContainerSize()} 覆盖快捷栏 + 主背包 + 盔甲 + <b>副手</b>。
     *
     * @return 可食用物品总数；读世界失败返回 <b>-1</b>（未知，不是 0）
     */
    private static int edibleCount(NumenPlayer p) {
        try {
            var inv = p.getInventory();
            int n = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && st.get(DataComponents.FOOD) != null) {
                    n += st.getCount();
                }
            }
            return n;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 背包里<b>床</b>的总个数（E2 夜晚闹钟的「有床可睡」判据）。
     *
     * <p>按 {@code BedItem} 判（模组床只要继承它就一并覆盖）；数个数不数槽位。
     *
     * @return 床总数；读世界失败返回 <b>-1</b>（未知，不是 0）
     */
    private static int bedCount(NumenPlayer p) {
        try {
            var inv = p.getInventory();
            int n = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && st.getItem() instanceof BedItem) {
                    n += st.getCount();
                }
            }
            return n;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String itemPath(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String safeName(Entity e) {
        try {
            return e.getName().getString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_+]", "");
        } catch (RuntimeException ex) {
            return "";
        }
    }
}
