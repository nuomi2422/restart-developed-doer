package com.dwinovo.numen.acx.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.api.AcxToolRegistry;
import com.dwinovo.numen.acx.api.AcxToolSchema;

/**
 * 把一组宿主端口批量注册进引擎积木表。
 *
 * <p>规则（对齐现有 AC 已踩过的坑）：</p>
 * <ul>
 *   <li>{@code acx_} 前缀保留给引擎门面 —— 防「门面被当积木递归调用」</li>
 *   <li>重名 / 已存在 → 跳过并记 warning，不覆盖已有积木</li>
 *   <li>别名（{@link AcxAliases}）只在真名注册成功时才挂；挂成独立 {@code AliasTool}
 *       指向同一 adapter，脚本写旧名也能加载</li>
 * </ul>
 *
 * <p>纯 JVM。真正的宿主实现（把 NumenTool 包成 AcxToolPort）在迁入时写，
 * 不在本批范围内。</p>
 */
public final class AcxHostBridge {

    public record Report(List<String> registered, List<String> aliasNames,
                         List<String> skipped, List<String> warnings) {

        public String summary() {
            return "registered=" + registered.size() + " aliases=" + aliasNames.size()
                    + " skipped=" + skipped.size() + " warnings=" + warnings.size();
        }
    }

    private AcxHostBridge() { }

    public static Report registerAll(AcxToolRegistry registry,
                                     List<AcxToolPort> ports,
                                     AcxCompletionProbe probe) {
        List<String> registered = new ArrayList<>();
        List<String> aliasNames = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, PortToolAdapter> byName = new LinkedHashMap<>();

        if (ports != null) {
            for (AcxToolPort port : ports) {
                if (port == null) {
                    skipped.add("(null port)");
                    continue;
                }
                String name = port.name();
                if (name == null || name.isBlank()) {
                    skipped.add("(blank name)");
                    continue;
                }
                if (name.startsWith("acx_")) {
                    skipped.add(name + "（acx_ 前缀留给门面，防递归）");
                    continue;
                }
                if (byName.containsKey(name)) {
                    skipped.add(name + "（本批端口内重名）");
                    continue;
                }
                if (registry.contains(name)) {
                    skipped.add(name + "（注册表已有同名积木，不覆盖）");
                    continue;
                }
                PortToolAdapter adapter = PortToolAdapter.builder(port).probe(probe).build();
                registry.register(adapter);
                byName.put(name, adapter);
                registered.add(name);
            }
        }

        for (Map.Entry<String, String> e : AcxAliases.all().entrySet()) {
            String alias = e.getKey();
            PortToolAdapter target = byName.get(e.getValue());
            if (target == null) {
                continue;
            }
            if (registry.contains(alias)) {
                warnings.add("别名 " + alias + " 与已有积木同名，未注册（真名 " + e.getValue() + "）");
                continue;
            }
            registry.register(new AliasTool(alias, target));
            aliasNames.add(alias);
        }

        return new Report(registered, aliasNames, skipped, warnings);
    }

    /** 别名壳：名字不同，schema / 行为全部委托真身。 */
    private static final class AliasTool implements AcxTool {
        private final String alias;
        private final AcxTool delegate;

        AliasTool(String alias, AcxTool delegate) {
            this.alias = alias;
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return alias;
        }

        @Override
        public AcxToolSchema schema() {
            return delegate.schema();
        }

        @Override
        public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            return delegate.execute(params, ctx);
        }
    }
}
