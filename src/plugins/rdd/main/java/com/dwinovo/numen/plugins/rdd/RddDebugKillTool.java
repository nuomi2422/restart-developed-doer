package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 调试工具（验证专用）：让同伴**直接死亡一次**，用于验证"死亡→复活→回收"链路（V3）。
 *
 * <p>仅在验证阶段使用：把 HP 设为 0 并走原版死亡流程（触发 CompanionEvent.DEATH →
 * 资产失效/历史 LOST/重生点复活）。默认拒绝，除非显式传 confirm=true，防误用。
 */
final class RddDebugKillTool implements NumenTool {
    @Override public String name() { return "debug_kill"; }

    @Override public String description() {
        return "调试/验证专用：让同伴立即死亡一次（走原版死亡流程，触发资产失效与重生）。" +
                "用于验证死亡回收闭环。必须显式 confirm=true，否则拒绝。正常游戏不要用。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object().string("confirm", "必须为 true 才执行（防误用）。").build();
    }

    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            boolean confirm = args != null && args.has("confirm")
                    && "true".equalsIgnoreCase(args.get("confirm").getAsString());
            if (!confirm) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail("debug_kill refused: confirm=true required").toJson());
                return;
            }
            // 走原版"指令 /kill"的死亡通道，而不是 hurt(genericKill, MAX_VALUE)。
            //
            // 2026-09-30 实测（用户报"怎么打都打不死、进入假死"）：旧写法
            // `companion.hurt(genericKill, Float.MAX_VALUE)` 在同伴身上**不生效** ——
            // 血量纹丝不动(20.0)，而工具照样回 "lethal damage applied"，
            // 主人看着像调试器成功、实际人还活着（= 静默说谎的调试工具）。
            // entity.kill() 是 LivingEntity 的终局入口（内部 hurt(die, MAX_VALUE)），
            // 不经过 invulnerable / hurtTime 判定，是让"调试死亡"确定生效的那条路。
            companion.kill();
            boolean died = !companion.isAlive() || companion.getHealth() <= 0f;
            // 如实回报：死没死、当前维度与坐标。静默"成功"比失败更糟 ——
            // 它会让人以为死亡链路验过了，其实一次都没触发。
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    died ? "debug_kill executed (entity.kill(); death resolves this tick)"
                         : "debug_kill FAILED: entity.kill() ran but the companion is still alive",
                    Map.of("hp", companion.getHealth(),
                            "alive", companion.isAlive(),
                            "dimension", companion.level().dimension().location().toString(),
                            "position", companion.blockPosition().toShortString())).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("debug_kill failed: " + ex.getMessage()).toJson());
        }
    }
}
