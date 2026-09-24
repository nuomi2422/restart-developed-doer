package com.dwinovo.numen.agent;

import java.util.Set;

/** Shared classification for durable workstations worth remembering as world assets. */
public final class FunctionalBlockTypes {
    private static final Set<String> TRACKED = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker",
            "chest", "trapped_chest", "barrel", "ender_chest",
            "anvil", "chipped_anvil", "damaged_anvil",
            "grindstone", "stonecutter", "smithing_table",
            "enchanting_table", "brewing_stand", "lodestone",
            "nether_portal", "end_portal", "end_portal_frame");

    private FunctionalBlockTypes() {}

    public static String stationType(String blockId) {
        if (blockId == null || blockId.isBlank()) return "";
        int colon = blockId.indexOf(':');
        String path = colon >= 0 ? blockId.substring(colon + 1) : blockId;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public static boolean isTracked(String blockId) {
        return TRACKED.contains(stationType(blockId));
    }
}
