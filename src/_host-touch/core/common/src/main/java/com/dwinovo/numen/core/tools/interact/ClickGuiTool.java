package com.dwinovo.numen.core.tools.interact;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.tools.GuiOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 按下已打开 GUI 里的按钮/选项（附魔档位、村民交易、织布机图案、切石机配方、信标能力…）。
 *
 * <p>这个工具是自主跑真机上智能体自己提出的：它做附魔台阶段时诊断出
 * {@code interact_at} 只能对准世界方块、{@code transfer} 只能操作槽位，
 * 而附魔台的附魔选项是<b>按钮而非物品槽</b>，于是 {@code story/enchant_item} 卡死，
 * 并通过 {@code selfcompile_request} 请求了"可点击已打开 GUI 按钮"的能力。
 */
public final class ClickGuiTool implements NumenTool {

    private final GuiOps impl = new GuiOps();

    @Override
    public String name() {
        return "click_gui";
    }

    @Override
    public String description() {
        return "Press a BUTTON / OPTION inside the GUI you already have open — the counterpart to "
                + "transfer, which moves ITEMS between slots. Slots and buttons are different things: "
                + "transfer can never press a button, and this can never move an item. Use it for the "
                + "enchanting table's three offers (buttons 0, 1, 2), choosing a villager trade, a loom "
                + "pattern, a stonecutter recipe, or beacon powers. Workflow: open the station "
                + "(interact_at on the block) → inspect_gui to read the menu and its inputs → transfer "
                + "the materials it needs → click_gui the option you want. It fails loudly (never "
                + "silently) when the menu refuses — most often a missing material (the enchanting "
                + "table wants lapis lazuli in its slot and enough XP levels) or an out-of-range index. "
                + "button: 0-based index of the option to press.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("button",
                        "0-based index of the option/button to press. The enchanting table's three "
                                + "offers are 0, 1 and 2.", 0, 64)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        if (!args.has("button") || args.get("button").isJsonNull()) {
            reply.accept(TaskResult.fail("click_gui needs a 'button' index (0-based).").toJson());
            return;
        }
        int index;
        try {
            index = args.get("button").getAsInt();
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("click_gui 'button' must be an integer, got: "
                    + args.get("button")).toJson());
            return;
        }
        if (index < 0) {
            reply.accept(TaskResult.fail("click_gui 'button' must be >= 0.").toJson());
            return;
        }
        reply.accept(impl.clickMenuButton(self, index));
    }
}
