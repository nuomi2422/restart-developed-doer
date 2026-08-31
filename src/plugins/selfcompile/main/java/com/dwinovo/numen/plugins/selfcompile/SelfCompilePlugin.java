package com.dwinovo.numen.plugins.selfcompile;

import com.dwinovo.numen.api.NumenPlugins;

/**
 * Self-Compile 插件骨架。
 *
 * <p>这一版只负责把插件挂进 Numen 的公开入口，不做自动生成、编译、部署。
 * 其职责是作为自变异系统的受控承载点，后续再逐步接上工作区、候选产物和验证链路。
 */
public final class SelfCompilePlugin {

    private SelfCompilePlugin() {}

    public static void install() {
        NumenPlugins.register(numen -> {
            numen.onClient(() -> {
                // 预留客户端初始化入口：后续用于加载自变异面板、状态展示、实验结果回放。
            });

            numen.contributeState(uuid -> "");
        });
    }
}
