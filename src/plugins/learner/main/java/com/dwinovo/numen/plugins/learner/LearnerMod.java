package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;

/**
 * learner 的 NeoForge 入口：只做注册，逻辑都在 {@link LearnerPlugin}。
 *
 * <p>与 {@code AcMod} / {@code ExperienceMod} 同形。
 */
@Mod("learner")
public final class LearnerMod {

    public LearnerMod() {
        NumenPlugins.register(new LearnerPlugin());
    }
}
