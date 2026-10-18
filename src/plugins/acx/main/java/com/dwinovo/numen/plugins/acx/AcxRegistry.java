package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolRegistry;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link AcxToolRegistry} 的生产实现（acx-core 只带测试替身，宿主必须提供一份）。
 *
 * <p>桥接 / 别名 / 手写积木都注册到这里；执行器异步线程读它，用并发表。</p>
 */
public final class AcxRegistry implements AcxToolRegistry {

    private final ConcurrentMap<String, AcxTool> tools = new ConcurrentHashMap<>();

    @Override
    public Optional<AcxTool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    @Override
    public boolean contains(String name) {
        return name != null && tools.containsKey(name);
    }

    @Override
    public Collection<String> names() {
        return tools.keySet();
    }

    @Override
    public void register(AcxTool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("积木名不能为空");
        }
        tools.put(tool.name(), tool);
    }
}
