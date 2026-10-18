package com.dwinovo.numen.worldstate.core.provider;

import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;

/**
 * 世界采集器。每个主题一个，声明自己支持什么、能查多大、返回什么版本的数据。
 *
 * <p>实现必须<b>只读世界</b>：不强制加载/生成区块、不改世界、不登记、不派工。
 * 需要区块已加载时，未加载就抛 {@link ProviderException}（原因 {@code CHUNK_UNLOADED}）
 * 或返回 {@code PARTIAL}/{@code UNLOADED} 覆盖，<b>不许拿空当"没有"</b>。
 *
 * <p><b>线程契约</b>：{@link #collect} 会读取真实的 Minecraft 世界，宿主必须保证它在服务端线程上执行。
 */
public interface WorldProvider {

    /** 本采集器负责的主题。 */
    Topic topic();

    /** 采集器 id（写进观测的 source 字段，也用于监测台）。 */
    String id();

    /** 载荷数据格式版本（结构变了就 +1，便于将来迁移）。 */
    int dataVersion();

    /** 声明的最大可采集体积（方块数）。{@code <=0} 表示无体积限制（如环境快照）。 */
    default long maxVolume() {
        return 0L;
    }

    /** 能否服务该请求（默认：主题匹配即可）。 */
    default boolean supports(ObservationRequest request) {
        return request != null && request.topic() == topic();
    }

    /**
     * 执行一次只读采集。
     *
     * @throws ProviderException 明确失败（未加载/维度缺失/预算超限等）
     */
    ProviderResult collect(CollectionContext ctx, ObservationRequest request);
}
