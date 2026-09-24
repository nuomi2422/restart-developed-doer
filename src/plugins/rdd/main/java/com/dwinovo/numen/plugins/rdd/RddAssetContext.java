package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Bounded, explicit projection of persisted world assets for model and monitor consumers. */
final class RddAssetContext {
    private RddAssetContext() {}

    static List<AssetRegistry.AssetEntry> worldAssets(AssetRegistry registry) {
        if (registry == null) return List.of();
        List<AssetRegistry.AssetEntry> out = new ArrayList<>();
        for (var entry : registry.snapshot()) {
            if (RddAssetStore.isWorldAsset(entry)) out.add(entry);
        }
        out.sort(Comparator.comparingLong((AssetRegistry.AssetEntry e) -> e.observation().observedAt()).reversed());
        return List.copyOf(out);
    }

    static String render(AssetRegistry registry, int maxChars) {
        if (maxChars < 64) return "";
        List<AssetRegistry.AssetEntry> assets = worldAssets(registry);
        if (assets.isEmpty()) return "";
        StringBuilder out = new StringBuilder("<known_world_assets>\n");
        out.append("Last observed reusable world assets. LAZY entries are leads; re-check before relying on them.\n");
        for (var entry : assets) {
            Map<String, Object> value = entry.observation().value();
            String line = "- kind=" + text(value.get("kind"))
                    + " label=" + text(value.getOrDefault("label", entry.assetId()))
                    + " dimension=" + text(value.get("dimension"))
                    + " pos=" + text(value.get("x")) + "," + text(value.get("y")) + "," + text(value.get("z"))
                    + " refresh=" + text(value.getOrDefault("refresh_policy", "LAZY"))
                    + " status=" + entry.status()
                    + details(value) + "\n";
            if (out.length() + line.length() + 22 > maxChars) break;
            out.append(line);
        }
        out.append("</known_world_assets>");
        return out.toString();
    }

    private static String details(Map<String, Object> value) {
        Object summary = value.get("summary");
        Object contents = value.get("important_items");
        if (summary == null && contents == null) return "";
        return " details=" + text(summary != null ? summary : contents);
    }

    private static String text(Object value) {
        if (value == null) return "unknown";
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", " ").replace("\r", " ");
    }
}
