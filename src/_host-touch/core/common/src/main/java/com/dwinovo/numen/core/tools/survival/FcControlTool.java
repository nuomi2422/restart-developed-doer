package com.dwinovo.numen.core.tools.survival;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Controls the companion's automatic FC survival layer without creating a
 * second scheduler or replacing its current explicit task.
 */
public final class FcControlTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final List<String> CAPABILITIES = List.of(
            "fall_rescue_water_bucket_or_soft_block",
            "escape_lava_toward_nearest_dry_foothold",
            "break_suffocating_block",
            "surface_for_air",
            "close_hostile_defense_with_combat_shield",
            "unstuck_burst");

    private record Args(String action) {}

    @Override
    public String name() {
        return "fc_control";
    }

    @Override
    public String description() {
        return "Inspect or switch the automatic FC survival layer. FC normally saves a fast fall "
                + "with a water bucket/soft block, escapes lava, digs out of suffocating blocks, "
                + "surfaces for air, handles a nearby hostile "
                + "through the normal combat-and-shield path, and attempts a short unstuck burst. "
                + "Call with action=status (or omit it) to inspect. action=disable is only for a "
                + "controlled experiment or an explicit owner instruction: it makes every automatic "
                + "FC reflex yield the body on the next tick, but does not cancel your explicit task. "
                + "action=enable restores FC. The setting persists with this companion.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "Omit/status to inspect; enable restores automatic FC; "
                                + "disable turns off automatic FC for this companion.",
                        "status", "enable", "disable")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        String action = parsed == null || parsed.action() == null ? "status" : parsed.action();
        switch (action) {
            case "enable" -> companion.setFcEnabled(true);
            case "disable" -> companion.setFcEnabled(false);
            case "status" -> { }
            default -> {
                reply.accept(TaskResult.fail("unknown fc_control action '" + action
                        + "'; use status, enable, or disable").toJson());
                return;
            }
        }
        boolean enabled = companion.fcEnabled();
        String message = switch (action) {
            case "enable" -> "automatic FC enabled; survival reflexes may protect the body again";
            case "disable" -> "automatic FC disabled; active reflexes will yield on the next brain tick";
            default -> enabled ? "automatic FC is enabled" : "automatic FC is disabled";
        };
        reply.accept(TaskResult.ok(message, Map.of(
                "fc_enabled", enabled,
                "capabilities", CAPABILITIES)).toJson());
    }
}
