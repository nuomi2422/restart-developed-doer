package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.ac.api.AcTool;
import com.dwinovo.numen.ac.api.ToolSchema;
import com.dwinovo.numen.ac.core.AcAuthoringService;
import com.dwinovo.numen.ac.core.AcExecutor;
import com.dwinovo.numen.ac.core.DefaultToolRegistry;
import com.dwinovo.numen.ac.core.FileAcVersionStore;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.plugins.ac.bridge.NumenHostAdapter;
import com.dwinovo.numen.plugins.ac.bridge.NumenSchemaAdapter;
import com.dwinovo.numen.plugins.ac.bridge.NumenToolBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AC 插件的 NUMEN 宿主适配器。把 Numen 工具目录桥接成 AC 原子工具并注册，
 * 暴露 {@code ac_execute / ac_status / ac_resume} 三个门面工具给主 AI。
 *
 * <p>AC core（ac-api/ac-core）保持纯 JVM、不依赖 NUMEN；本插件是它唯一的宿主面。
 * 门面工具（ac_*）不注册为 AC 步骤工具，避免递归。
 */
public final class AcPlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(AcPlugin.class);

    private final DefaultToolRegistry registry = new DefaultToolRegistry();
    private final AcSessions sessions = new AcSessions();
    private AcExecutor executor;
    private AcAuthoringService authoring;

    @Override
    public void setup(NumenApi numen) {
        executor = new AcExecutor(registry);
        // AC 库落盘：config/numen/ac-library.json，重启恢复，AI 可经 ac_execute 按名复用
        authoring = new AcAuthoringService(registry, new FileAcVersionStore(numen.configDir().resolve("ac-library.json")));

        // AC 执行事件 → 监测台 ac.jsonl（旁路观测，不破坏执行）
        executor.addEventListener(e -> com.dwinovo.numen.plugins.ac.AcMonitor.publish(e));

        numen.registerTool(new AcExecuteTool(executor, authoring, sessions, this::ensureBridged));
        numen.registerTool(new AcStatusTool(sessions));
        numen.registerTool(new AcResumeTool(executor, sessions));
        numen.registerTool(new AcPublishTool(authoring, this::ensureBridged));

        LOG.info("[ac] plugin ready; Numen tools bridged lazily on first ac_execute");
    }

    /**
     * 幂等惰性桥接：插件 setup 时机早于 NumenCore 全量注册工具，不能在构造期全量桥接。
     * 每次 ac_execute 前调用，从 Numen ToolRegistry 同步尚未注册的工具（跳过 ac_ 门面）。
     */
    public synchronized void ensureBridged() {
        for (NumenTool tool : ToolRegistry.all()) {
            String name = tool.name();
            if (name == null || name.isBlank() || name.startsWith("ac_")) {
                continue;
            }
            if (registry.contains(name)) {
                continue;
            }
            try {
                ToolSchema schema = NumenSchemaAdapter.from(tool);
                AcTool bridge = new NumenToolBridge(new NumenHostAdapter(tool));
                registry.register(name, bridge, schema);
                LOG.info("[ac] 惰性桥接工具: {}", name);
            } catch (RuntimeException e) {
                LOG.warn("[ac] 跳过工具 {}: {}", name, e.getMessage());
            }
        }
    }

    /** 供调试/外部查询 AC 工具目录。 */
    public java.util.List<String> acToolNames() {
        return registry.toolNames();
    }
}
