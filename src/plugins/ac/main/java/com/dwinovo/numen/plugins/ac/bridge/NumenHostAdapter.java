package com.dwinovo.numen.plugins.ac.bridge;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolAnchor;
import com.dwinovo.numen.agent.tool.ToolCall;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 把 Numen {@link NumenTool} 包装成纯 JVM 的 {@link HostTool}。这是桥中<b>唯一</b>
 * 接触 NumenTool / ToolCall / ToolAnchor 的文件；其余桥逻辑可脱离 Numen 单测。
 *
 * <p>每次 AC 步骤调用产生唯一 {@code callId} 并原样贯穿；结果经 {@code complete}
 * 回调一次。无实体上下文时用占位 UUID 保证调用不空指针（真实场景由 AcExecuteTool
 * 注入 companion UUID）。
 */
public final class NumenHostAdapter implements HostTool {

    private final NumenTool tool;

    public NumenHostAdapter(NumenTool tool) {
        this.tool = Objects.requireNonNull(tool, "tool");
    }

    @Override
    public String name() {
        return tool.name();
    }

    @Override
    public void invoke(String callId, String argsJson, UUID anchorUuid, Consumer<String> completion) {
        UUID effective = anchorUuid == null ? UUID.randomUUID() : anchorUuid;
        ToolAnchor anchor = () -> effective;
        ToolCall call = new ToolCall(callId, tool.name(), argsJson, anchor, completion);
        tool.invoke(call);
    }
}
