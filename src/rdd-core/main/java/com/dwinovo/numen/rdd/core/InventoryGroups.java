package com.dwinovo.numen.rdd.core;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Small, explicit survival groups. Counts are items, never recipe-equivalent units. */
public final class InventoryGroups {
    private InventoryGroups() {}
    private static final Set<String> FOOD = Set.of("bread", "carrot", "baked_potato", "beetroot",
            "apple", "golden_apple", "enchanted_golden_apple", "golden_carrot", "cooked_beef",
            "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_rabbit", "cooked_cod",
            "cooked_salmon", "melon_slice", "sweet_berries", "glow_berries", "pumpkin_pie",
            "mushroom_stew", "beetroot_soup", "rabbit_stew", "cookie", "dried_kelp");
    private static final Set<String> WOOD = wood();
    private static final Set<String> BLOCKS = Set.of("cobblestone", "cobbled_deepslate", "stone",
            "deepslate", "dirt", "coarse_dirt", "netherrack", "end_stone", "andesite", "diorite",
            "granite", "tuff", "blackstone", "basalt", "sandstone", "red_sandstone", "moss_block");
    private static Set<String> wood() {
        Set<String> result = new HashSet<>();
        for (String species : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"}) {
            for (String suffix : new String[]{"_log", "_wood", "_planks"}) result.add(species + suffix);
            result.add("stripped_" + species + "_log");
            result.add("stripped_" + species + "_wood");
        }
        for (String species : new String[]{"crimson", "warped"}) {
            for (String suffix : new String[]{"_stem", "_hyphae", "_planks"}) result.add(species + suffix);
            result.add("stripped_" + species + "_stem");
            result.add("stripped_" + species + "_hyphae");
        }
        result.add("bamboo_planks");
        return Set.copyOf(result);
    }
    public static boolean known(Object group) {
        return "food".equals(group) || "wood".equals(group) || "blocks".equals(group);
    }
    public static boolean contains(String group, String id) {
        if (id == null || !id.startsWith("minecraft:")) return false;
        String item = id.substring(10);
        return switch (group) {
            case "food" -> FOOD.contains(item);
            case "wood" -> WOOD.contains(item);
            case "blocks" -> BLOCKS.contains(item) || item.endsWith("_planks") && WOOD.contains(item);
            default -> false;
        };
    }
    public static long count(String group, Map<String, Integer> inventory) {
        if (!known(group) || inventory == null) return 0;
        long total = 0;
        for (var entry : inventory.entrySet()) {
            if (contains(group, entry.getKey()) && entry.getValue() != null && entry.getValue() > 0)
                total += entry.getValue();
        }
        return total;
    }
}
