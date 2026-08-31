package com.dwinovo.numen.plugins.ac.bridge;

import com.dwinovo.numen.ac.api.AcTool;
import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.StepResult;
import com.google.gson.Gson;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 把宿主工具桥接成 AC 的同步步骤工具。AC executor 是同步的，而 Numen 工具
 * 是异步 {@code complete} 回调 —— 本桥为一次步骤调用创建唯一 id，发起宿主调用，
 * 阻塞等待回调，并把结果 JSON 映射为 {@link StepResult}（见
 * {@link BridgeResultMapper}）。带超时：超时视为 {@code PAUSED}（可续）。
 */
public final class NumenToolBridge implements AcTool {

    /** ExecutionContext 中宿主实体 UUID 的属性键。 */
    public static final String HOST_ENTITY_UUID = "numen.entityUuid";
    /** 默认等待一次宿主工具回调的墙钟上限。 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    private static final Gson GSON = new Gson();

    private final HostTool host;
    private final long timeoutMs;

    public NumenToolBridge(HostTool host) {
        this(host, DEFAULT_TIMEOUT_MS);
    }

    public NumenToolBridge(HostTool host, long timeoutMs) {
        this.host = Objects.requireNonNull(host, "host");
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    @Override
    public StepResult execute(Map<String, Object> params, ExecutionContext context) {
        String callId = UUID.randomUUID().toString();
        String argsJson = params == null || params.isEmpty() ? "{}" : GSON.toJson(params);
        UUID anchor = null;
        if (context != null) {
            Object v = context.attribute(HOST_ENTITY_UUID);
            if (v instanceof UUID u) anchor = u;
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        // 发起调用必须在 try 内：transport 工具（如 get_self_status 走 ServerToolTransport.ship）
        // 在 AC 后台线程可能直接抛 RuntimeException 甚至 Error（NoClassDefFoundError），
        // 漏掉会让 future 异常完成、查询链炸。
        try {
            host.invoke(callId, argsJson, anchor, done::complete);
        } catch (Throwable e) {
            return StepResult.failed("host tool invoke threw: " + e
                    + " (tool=" + host.name() + ", callId=" + callId + ")");
        }
        try {
            String json = done.get(timeoutMs, TimeUnit.MILLISECONDS);
            return BridgeResultMapper.map(host.name(), json);
        } catch (TimeoutException e) {
            return StepResult.paused("host tool timeout: " + host.name(),
                    Map.of("tool", host.name(), "call_id", callId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepResult.paused("host tool interrupted: " + host.name(),
                    Map.of("tool", host.name(), "call_id", callId));
        } catch (Exception e) {
            return StepResult.failed("host tool bridge error: " + e.getMessage());
        }
    }
}
