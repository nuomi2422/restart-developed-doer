package com.dwinovo.numen.acx.api;

import java.util.Collection;
import java.util.Optional;

/** 积木注册表契约。AC 加载期用它校验引用，运行期用它查执行目标。 */
public interface AcxToolRegistry {

    Optional<AcxTool> find(String name);

    boolean contains(String name);

    Collection<String> names();

    void register(AcxTool tool);
}