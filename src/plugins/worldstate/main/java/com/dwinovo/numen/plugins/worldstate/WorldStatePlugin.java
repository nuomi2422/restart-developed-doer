package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.plugins.worldstate.tool.WorldStateTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界观测插件：装配 {@link WorldStateService}，注册 {@code world_state} 工具入口。
 *
 * <p>V1 只把「只读查询、显式采集、带时间与覆盖信息的基础存储」这条链跑通，
 * 其余主题（blocks/containers/facilities）按同一契约逐个接入，旧扫描器保持现状。
 */
public final class WorldStatePlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("worldstate");

    @Override
    public void setup(NumenApi numen) {
        WorldStateService service = new WorldStateService(numen.configDir());
        WorldStateMod.bindService(service);
        numen.registerTool(new WorldStateTool(service));
        LOG.info("[worldstate] registered; store={} topics={}", service.file(), service.topics());
    }
}
