package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;

/** NeoForge 入口：把 ACX 插件挂到 NUMEN 宿主。 */
@Mod("acx")
public final class AcxMod {

    public AcxMod() {
        NumenPlugins.register(new AcxPlugin());
    }
}
