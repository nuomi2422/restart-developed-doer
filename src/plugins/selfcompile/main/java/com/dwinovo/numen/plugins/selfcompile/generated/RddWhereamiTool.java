package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 查询同伴当前所在位置：维度、方块坐标、朝向、是否着地。纯读世界，当场返回，无参数。
 * 由 Self-Compile 自变异系统生成。
 */
public final class RddWhereamiTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_whereami";
    }

    @Override
    public String description() {
        return "查询同伴当前所在位置（dimension 维度、x/y/z 方块坐标、yaw 朝向、on_ground 是否着地）。"
                + "当需要知道同伴在哪、在哪个维度、面向哪个方向，或判断其是否着地时使用。不需要任何参数。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
        );
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String dimension = companion.level().dimension().location().toString();
        int x = companion.blockPosition().getX();
        int y = companion.blockPosition().getY();
        int z = companion.blockPosition().getZ();
        float yaw = companion.getYRot();
        boolean onGround = companion.onGround();

        Map<String, Object> data = Map.of(
                "dimension", dimension,
                "x", x,
                "y", y,
                "z", z,
                "yaw", yaw,
                "on_ground", onGround
        );

        reply.accept(TaskResult.ok("同伴当前位置查询成功", data).toJson());
    }
}
