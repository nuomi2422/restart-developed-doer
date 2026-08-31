package com.dwinovo.numen.ac.api;

import java.util.List;
import java.util.Optional;

/**
 * AC 定义版本仓储契约。AI authoring 的 publish/replace/listVersions/load 落地于此。
 * 存储保持不可变（{@code AcDefinition} 是 record），替换是原子操作：只有校验通过的
 * 候选才写入，失败不破坏当前版本。
 */
public interface AcVersionStore {

    /** 保存一个 AC 版本（覆盖同名同版本，其他版本保留）。 */
    void put(String name, String version, AcDefinition ac);

    /** 按名+版本取；不存在返回 empty。 */
    Optional<AcDefinition> get(String name, String version);

    /** 取该 AC 的最高版本（按版本字符串比较）；无记录返回 empty。 */
    Optional<AcDefinition> latest(String name);

    /** 该 AC 的全部版本号（稳定排序）。 */
    List<String> versions(String name);

    default boolean contains(String name, String version) {
        return get(name, version).isPresent();
    }
}
