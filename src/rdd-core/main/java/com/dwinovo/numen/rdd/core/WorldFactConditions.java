package com.dwinovo.numen.rdd.core;

import java.util.Map;
import java.util.Set;

/** Schema only. Actual evidence is read by the Minecraft host, never by the core. */
public final class WorldFactConditions {
    private WorldFactConditions() {}
    public static boolean valid(Map<String, Object> condition) {
        if (condition == null || condition.containsKey("asset_key") || condition.containsKey("group")) return false;
        Object type = condition.get("type");
        if (!(type instanceof String name)) return false;
        if (condition.containsKey("dimension") && !id(condition.get("dimension"))) return false;
        return switch (name) {
            case "advancement" -> id(condition.get("advancement"));
            case "structure" -> id(condition.get("structure"));
            case "entity_killed" -> id(condition.get("entity")) && positive(condition.getOrDefault("minimum", 1));
            case "base" -> true;
            default -> false;
        };
    }
    public static boolean knownType(Object type) {
        return type instanceof String s && Set.of("advancement", "structure", "entity_killed", "base").contains(s);
    }
    private static boolean id(Object value) {
        return value instanceof String s && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
    private static boolean positive(Object value) {
        return value instanceof Number n && n.intValue() > 0 && n.doubleValue() == n.intValue();
    }
}
