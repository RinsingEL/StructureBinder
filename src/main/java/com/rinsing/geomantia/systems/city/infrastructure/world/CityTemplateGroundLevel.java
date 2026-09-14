package com.rinsing.geomantia.systems.city.infrastructure.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.*;

/** Local first-free height above an authored soil base, not the template bounding-box bottom. */
final class CityTemplateGroundLevel {
    private static final Map<StructureTemplate, Integer> CACHE = new WeakHashMap<>();
    private static final Set<String> SOIL = Set.of("minecraft:grass_block", "minecraft:dirt",
            "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:podzol",
            "minecraft:mycelium", "minecraft:farmland", "minecraft:dirt_path",
            "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:mud");
    private CityTemplateGroundLevel() { }

    static synchronized int offset(StructureTemplate template) {
        return CACHE.computeIfAbsent(template, value -> offset(value.save(new CompoundTag())));
    }

    static int offset(CompoundTag nbt) {
        var size = nbt.getList("size", Tag.TAG_INT);
        if (size.size() != 3) return 0;
        int width = size.getInt(0), height = size.getInt(1), depth = size.getInt(2);
        var palette = nbt.getList("palette", Tag.TAG_COMPOUND);
        if (palette.isEmpty()) {
            var palettes = nbt.getList("palettes", Tag.TAG_LIST);
            if (!palettes.isEmpty()) palette = (net.minecraft.nbt.ListTag) palettes.get(0);
        }
        Map<Long, Integer> tops = new HashMap<>();
        for (var entry : nbt.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) entry;
            var pos = block.getList("pos", Tag.TAG_INT);
            int state = block.getInt("state");
            if (pos.size() != 3 || state < 0 || state >= palette.size()) continue;
            int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2);
            // Exterior soil expresses where an embedded structure meets the terrain.
            // Interior planters must not change the datum of a bare-floor template.
            if (x != 0 && x != width - 1 && z != 0 && z != depth - 1) continue;
            if (y < 0 || y >= height || !SOIL.contains(palette.getCompound(state).getString("Name"))) continue;
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            tops.merge(key, y + 1, Math::max);
        }
        Map<Integer, Integer> counts = new HashMap<>();
        tops.values().forEach(y -> counts.merge(y, 1, Integer::sum));
        return counts.entrySet().stream().sorted(Comparator
                .<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparingInt(Map.Entry::getKey)).mapToInt(Map.Entry::getKey).findFirst().orElse(0);
    }
}
