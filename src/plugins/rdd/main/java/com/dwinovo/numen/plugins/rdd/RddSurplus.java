package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** One best-effort nearby hay bonus. Minimum stays the only completion requirement. */
final class RddSurplus {
    private record Run(TaskChain chain, String subtask, String bodyId, long started, BlockPos origin, int initial) {}
    private final Map<UUID, Run> runs = new HashMap<>();
    void clear() { runs.clear(); }
    void inspect(NumenPlayer ap, TaskChain chain, Map<String, Integer> counts) {
        Run run = runs.get(ap.getUUID());
        if (run == null) return;
        var active = CompanionTickDispatcher.currentTaskFor(ap.getUUID());
        boolean owns = active != null && active.publicId().equals(run.bodyId());
        boolean current = run.chain() == chain && chain.currentSubtask() != null
                && run.subtask().equals(chain.currentSubtask().id());
        if (owns && (!current || !RddPlugin.supervisionEnabled()
                || !RddSurplusPolicy.withinBudget(System.nanoTime() - run.started(),
                ap.blockPosition().distSqr(run.origin()), ap.getHealth(),
                counts.getOrDefault("minecraft:hay_block", 0) - run.initial())))
            CompanionTickDispatcher.stopActive(ap, "optional hay surplus budget ended");
        if (!current) runs.remove(ap.getUUID());
    }
    boolean hold(NumenPlayer ap, TaskChain chain, Subtask task, Map<String, Integer> counts) {
        Run run = runs.get(ap.getUUID());
        var active = CompanionTickDispatcher.currentTaskFor(ap.getUUID());
        int have = counts.getOrDefault("minecraft:hay_block", 0);
        if (run != null && run.chain() == chain && run.subtask().equals(task.id())) {
            boolean owns = active != null && active.publicId().equals(run.bodyId());
            if (owns && RddSurplusPolicy.withinBudget(System.nanoTime() - run.started(), ap.blockPosition().distSqr(run.origin()), ap.getHealth(), have - run.initial()))
                return true;
            if (owns) CompanionTickDispatcher.stopActive(ap, "optional hay surplus budget reached; minimum already met");
            // Keep the completed marker until the task changes: never start a second bonus.
            return false;
        }
        runs.remove(ap.getUUID());
        if (!"minecraft:hay_block".equals(task.condition().get("asset_key")) || active != null
                || ap.getHealth() <= 12 || !RddPlugin.bodySubmissionEnabled() || !RddPlugin.supervisionEnabled()) return false;
        var mine = ToolRegistry.resolve("mine");
        if (mine == null) return false;
        BlockPos origin = ap.blockPosition();
        int nearby = 0;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -3, -6), origin.offset(6, 3, 6))) {
            if (ap.serverLevel().hasChunkAt(pos) && ap.serverLevel().getBlockState(pos).is(Blocks.HAY_BLOCK) && ++nearby >= 8) break;
        }
        if (nearby == 0) return false;
        JsonObject args = new JsonObject();
        JsonArray blocks = new JsonArray(); blocks.add("minecraft:hay_block"); args.add("block_ids", blocks);
        args.addProperty("count", Math.min(8, nearby));
        mine.onServerCall("rdd-surplus-" + task.id(), args, ap, result -> {});
        active = CompanionTickDispatcher.currentTaskFor(ap.getUUID());
        if (active == null || !"mine".equals(active.getToolName())) return false;
        runs.put(ap.getUUID(), new Run(chain, task.id(), active.publicId(), System.nanoTime(), origin.immutable(), have));
        RddMonitor.publish("surplus_started", Map.of("companionId", ap.getUUID().toString(), "subtask", task.id(),
                "maximumExtra", 8, "maximumSeconds", 30, "maximumDistance", 8));
        return true;
    }
}
