package com.dwinovo.numen.worldstate.core.provider;

/**
 * 采集器执行时的上下文。刻意<b>不</b>暴露任何 Minecraft 类型——纯核心不认识世界。
 * 宿主侧（plugins/worldstate）用真实服务器实现它。
 */
public interface CollectionContext {

    /** 世界存档标识（命名空间的一部分）。 */
    String worldId();

    /** 当前游戏 tick。 */
    long gameTick();

    /** 当前实际时间（毫秒），用于写观测的时间戳。 */
    long nowMs();
}
