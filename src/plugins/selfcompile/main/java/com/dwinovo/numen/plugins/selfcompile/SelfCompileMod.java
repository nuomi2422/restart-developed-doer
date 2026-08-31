package com.dwinovo.numen.plugins.selfcompile;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;

/** NeoForge entry point for the standalone Self-Compile plugin. */
@Mod(SelfCompileMod.MOD_ID)
public final class SelfCompileMod {

    public static final String MOD_ID = "selfcompile";

    public SelfCompileMod() {
        NumenPlugins.register(new SelfCompileEntry());
    }
}
