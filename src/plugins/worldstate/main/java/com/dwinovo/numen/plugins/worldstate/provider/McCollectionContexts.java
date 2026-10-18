package com.dwinovo.numen.plugins.worldstate.provider;

import com.dwinovo.numen.plugins.worldstate.McCollectionContext;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderException;

/** 采集器拿到的上下文必须是本插件那条 Minecraft 上下文；否则明确报错，不静默。 */
final class McCollectionContexts {

    private McCollectionContexts() {
    }

    static McCollectionContext require(CollectionContext ctx) {
        if (ctx instanceof McCollectionContext mc) return mc;
        throw new ProviderException(FailReason.INVALID_REQUEST,
                "context is not a Minecraft collection context; host must supply McCollectionContext");
    }
}
