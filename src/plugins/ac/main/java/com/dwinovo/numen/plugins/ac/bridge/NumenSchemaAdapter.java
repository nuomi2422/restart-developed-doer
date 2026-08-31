package com.dwinovo.numen.plugins.ac.bridge;

import com.dwinovo.numen.ac.api.ToolSchema;
import com.dwinovo.numen.agent.tool.NumenTool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Numen 工具的 OpenAI 风格 parameterSchema（Map）转换为 AC 的有限
 * {@link ToolSchema}。转换是宽松的：只取 type / required / description，
 * 复杂约束（enum/min/max/嵌套 object）在 AC 侧暂不做完整映射，宁可用
 * ANY 放行也不误拒。
 */
public final class NumenSchemaAdapter {

    private NumenSchemaAdapter() {}

    public static ToolSchema from(NumenTool tool) {
        String name = tool.name();
        String version = "1";
        String description = tool.description() == null ? "" : tool.description();

        Map<String, Object> schema = tool.parameterSchema();
        if (schema == null || schema.isEmpty()) {
            return new ToolSchema(name, version, description, List.of(), true);
        }
        Set<String> required = new HashSet<>();
        Object reqObj = schema.get("required");
        if (reqObj instanceof List<?> list) {
            for (Object o : list) required.add(String.valueOf(o));
        }
        List<ToolSchema.Param> params = new ArrayList<>();
        Object propsObj = schema.get("properties");
        if (propsObj instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                String pname = String.valueOf(e.getKey());
                Object spec = e.getValue();
                String type = "any";
                String pdesc = "";
                if (spec instanceof Map<?, ?> m) {
                    if (m.get("type") != null) type = String.valueOf(m.get("type"));
                    if (m.get("description") != null) pdesc = String.valueOf(m.get("description"));
                }
                params.add(new ToolSchema.Param(pname, mapType(type), required.contains(pname),
                        false, null, List.of(), pdesc));
            }
        }
        boolean allowUnknown = !(schema.get("additionalProperties") instanceof Boolean b) || b;
        return new ToolSchema(name, version, description, params, allowUnknown);
    }

    private static ToolSchema.Type mapType(String raw) {
        if (raw == null) return ToolSchema.Type.ANY;
        return switch (raw.toLowerCase()) {
            case "string" -> ToolSchema.Type.STRING;
            case "integer" -> ToolSchema.Type.INTEGER;
            case "number" -> ToolSchema.Type.NUMBER;
            case "boolean" -> ToolSchema.Type.BOOLEAN;
            case "array" -> ToolSchema.Type.ARRAY;
            case "object" -> ToolSchema.Type.OBJECT;
            default -> ToolSchema.Type.ANY;
        };
    }
}
