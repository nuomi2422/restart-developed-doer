package com.dwinovo.numen.acx.core;

import java.util.Map;
import java.util.Optional;

import com.dwinovo.numen.acx.api.AcxDefinition;

/**
 * AC 名字 → 定义的目录。执行器靠它解析「步骤 block 指向另一个 AC」这种递归调用。
 *
 * <p>抽成接口而不是直接传 {@code Map}，是为了让加载器（{@code AcxLoader}）能提供
 * 带分级 / 隔离语义的实现，而不是被执行器绑死成一张扁平表。</p>
 */
@FunctionalInterface
public interface AcxCatalog {

    Optional<AcxDefinition> find(String name);

    static AcxCatalog empty() {
        return name -> Optional.empty();
    }

    static AcxCatalog of(Map<String, AcxDefinition> defs) {
        return name -> Optional.ofNullable(defs.get(name));
    }
}
