package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔炉/容器观测：把「正在干活」的证据从 GUI 里捞出来，供卡死监督判断行为是否在变。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>熔炉快照</b>——GUI 在烹饪途中被关掉时，保留最后一次真实观测，避免把
 *       "关掉界面" 误判成 "停止生产"。</li>
 *   <li><b>容器内容指纹</b>——把容器槽位拌进进度串；燃料槽除外（燃料在烧不等于有产出）。</li>
 * </ol>
 *
 * <p>只读，不改变任何任务状态；纯服务端线程调用。
 */
final class RddFurnaceWatch {

    /** Keep the last real furnace observation when its GUI closes during cooking. */
    private final Map<UUID, FurnaceWatch> furnaces = new ConcurrentHashMap<>();

    record FurnaceWatch(TaskChain chain, String subtaskId, AbstractFurnaceMenu menu, BlockEntity block) {}

    /**
     * 校验并刷新某同伴的熔炉快照，返回当前有效快照（没有则 {@code null}）。
     *
     * <p>快照在以下情况作废：链换了、二级换了、方块没了、跨维度了。
     * 若此刻正开着熔炉 GUI，则用当前界面刷新快照。
     */
    FurnaceWatch track(NumenPlayer ap, RddRuntime rt, Subtask current) {
        UUID uuid = ap.getUUID();
        FurnaceWatch watch = furnaces.get(uuid);
        if (watch != null && (watch.chain() != rt.chain() || !watch.subtaskId().equals(current.id())
                || watch.block().isRemoved() || watch.block().getLevel() != ap.level())) {
            furnaces.remove(uuid);
            watch = null;
        }
        AbstractContainerMenu menu = ap.containerMenu;
        if (menu instanceof AbstractFurnaceMenu furnace && !menu.slots.isEmpty()
                && menu.slots.getFirst().container instanceof BlockEntity block) {
            watch = new FurnaceWatch(rt.chain(), current.id(), furnace, block);
            furnaces.put(uuid, watch);
        }
        return watch;
    }

    /**
     * 把容器内容拌进进度串。燃料槽跳过——燃料在烧不证明有配方在产出。
     * 同伴自己背包的槽位也跳过（背包是女仆属性，不算"生产进度"）。
     */
    static void appendContainer(StringBuilder progress, AbstractContainerMenu menu, NumenPlayer ap) {
        progress.append("|container=").append(menu.getClass().getName());
        for (var slot : menu.slots) {
            if (slot.container == ap.getInventory()) continue;
            // Burning fuel does not prove that a recipe is producing anything.
            if (menu instanceof AbstractFurnaceMenu && slot.index == AbstractFurnaceMenu.FUEL_SLOT) continue;
            ItemStack item = slot.getItem();
            progress.append('|').append(slot.index).append(':')
                    .append(BuiltInRegistries.ITEM.getKey(item.getItem())).append('=').append(item.getCount());
        }
    }

    void remove(UUID uuid) {
        furnaces.remove(uuid);
    }

    void clear() {
        furnaces.clear();
    }
}
