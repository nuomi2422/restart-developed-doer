package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;

import java.util.List;
import java.util.Optional;

/**
 * 观测存储的读取契约。<b>读取永不触发采集</b>——这正是「读」与「取」分开的那条线。
 *
 * <p>写入必须遵守「旧刷新不得覆盖更新的观测」：由 {@link #put} 的实现在修订号上把关。
 */
public interface ObservationStore {

    Optional<Observation> get(ObservationKey key);

    /**
     * 写入一条观测。实现必须拒绝比现有观测更旧的修订号（防止迟到的旧刷新覆盖新结果）。
     * 
     * @return 是否真的写入了
     */
    boolean put(Observation observation);

    List<Observation> all();

    int size();

    void clear();
}
