package com.dwinovo.numen.plugins.worldstate.provider;

import com.dwinovo.numen.plugins.worldstate.McCollectionContext;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderException;
import com.dwinovo.numen.worldstate.core.provider.ProviderResult;
import com.dwinovo.numen.worldstate.core.provider.WorldProvider;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.server.level.ServerLevel;

/**
 * 环境快照采集器：维度、游戏时间、天气。<b>只读</b>，范围无关，全局完整覆盖。
 *
 * <p>{@code getDayTime} 会被 {@code /time set} 直接改（不像 {@code getGameTime} 只增），
 * 这正是"环境是当前事实"的语义——观测记录的是当下时间。
 */
public final class EnvironmentWorldProvider implements WorldProvider {

    @Override public Topic topic() { return Topic.ENVIRONMENT; }

    @Override public String id() { return "environment"; }

    @Override public int dataVersion() { return 1; }

    @Override
    public ProviderResult collect(CollectionContext ctx, ObservationRequest request) {
        McCollectionContext mc = McCollectionContexts.require(ctx);
        ServerLevel level = mc.levelOf(request.dimension());
        if (level == null) {
            throw new ProviderException(FailReason.DIMENSION_MISSING,
                    "dimension not loaded: " + request.dimension());
        }
        EnvironmentPayload payload = new EnvironmentPayload(request.dimension(),
                level.getDayTime(), level.isRaining(), level.isThundering());
        return ProviderResult.of(payload, Coverage.COMPLETE);
    }
}
