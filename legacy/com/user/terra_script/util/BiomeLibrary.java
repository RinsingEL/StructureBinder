package com.user.terra_script.util;

import java.util.Set;

public class BiomeLibrary {

    // 稀有/特殊群系白名单
    // 命中这些群系时，我们会特别记录坐标，作为“奇观”或“圣地”的候选点
    private static final Set<String> RARE_BIOMES = Set.of(
            // 原版稀有
            "minecraft:mushroom_fields",
            "minecraft:ice_spikes",
            "minecraft:eroded_badlands",
            "minecraft:bamboo_jungle",
            "minecraft:deep_dark",
            "minecraft:cherry_grove",
            "minecraft:meadow",
            "minecraft:jagged_peaks",
            "minecraft:lush_caves",
            "minecraft:dripstone_caves",

            // 如果有 ReTerraForged 或其他 Mod 的特殊群系，加在这里
            // 例如: "reterraforged:volcano"
            "reterraforged:bryce"
    );

    public static boolean isRare(String biomeId) {
        return RARE_BIOMES.contains(biomeId);
    }
}