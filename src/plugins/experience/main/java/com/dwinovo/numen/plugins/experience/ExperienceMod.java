package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;

/** NeoForge 入口：把经验记忆插件注册进 Numen。 */
@Mod("expmem")
public final class ExperienceMod {

    public ExperienceMod() {
        NumenPlugins.register(new ExperiencePlugin());
    }
}
