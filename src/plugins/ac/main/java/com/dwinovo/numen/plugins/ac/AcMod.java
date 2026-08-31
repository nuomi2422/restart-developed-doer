package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;

/** NeoForge 入口：把 AC 插件挂到 NUMEN 宿主。 */
@Mod("ac")
public final class AcMod {

    public AcMod() {
        NumenPlugins.register(new AcPlugin());
    }
}
