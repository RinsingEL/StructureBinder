package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read once per compilation; generated features and materials freeze the chosen appearance. */
public record CityVillageRoadSettings(boolean enabled, Set<String> roadKinds, long seed,
        List<WeightedBlock> surfacePalette, String stairBlockId, String baseBlockId,
        boolean decorationsEnabled, boolean structureTreesEnabled, int spacingBlocks, double chance, int edgeOffsetBlocks,
        int endClearanceBlocks, List<Variant> variants) {
    public record WeightedBlock(String blockId, int weight) {
        public WeightedBlock { validBlock(blockId); positiveWeight(weight); }
    }
    public record DecorationBlock(int along, int outward, int height, String blockId) {
        public DecorationBlock {
            validBlock(blockId);
            if (Math.abs((long)along)>4 || outward<0 || outward>4 || height<1 || height>6)
                throw new IllegalArgumentException("VILLAGE_ROAD_DECORATION_OFFSET_INVALID");
        }
    }
    public record Variant(String id, int weight, List<DecorationBlock> blocks) {
        public Variant {
            positiveWeight(weight); blocks=List.copyOf(blocks);
            if (id==null || !id.matches("[a-z0-9_-]+") || blocks.isEmpty() || blocks.size()>32)
                throw new IllegalArgumentException("VILLAGE_ROAD_VARIANT_INVALID");
            Set<String> occupied=new HashSet<>();
            for (var b:blocks) if(!occupied.add(b.along()+","+b.outward()+","+b.height()))
                throw new IllegalArgumentException("VILLAGE_ROAD_DUPLICATE_DECORATION_BLOCK");
            for (var b:blocks) if(b.height()>1 && blocks.stream().noneMatch(lower -> lower.along()==b.along()
                    && lower.outward()==b.outward() && lower.height()==b.height()-1))
                throw new IllegalArgumentException("VILLAGE_ROAD_DECORATION_REQUIRES_SUPPORT");
        }
    }
    public CityVillageRoadSettings {
        roadKinds=Set.copyOf(roadKinds); surfacePalette=List.copyOf(surfacePalette); variants=List.copyOf(variants);
        validBlock(stairBlockId); validBlock(baseBlockId);
        if (!stairBlockId.endsWith("_stairs") || roadKinds.isEmpty() || roadKinds.contains("CITY_BRIDGE")
                || surfacePalette.isEmpty() || surfacePalette.size()>32 || variants.size()>32
                || spacingBlocks<4 || spacingBlocks>128 || !Double.isFinite(chance) || chance<0 || chance>1
                || edgeOffsetBlocks<1 || edgeOffsetBlocks>8 || endClearanceBlocks<0 || endClearanceBlocks>16
                || decorationsEnabled && variants.isEmpty())
            throw new IllegalArgumentException("VILLAGE_ROAD_CONFIG_INVALID");
        if(variants.stream().map(Variant::id).distinct().count()!=variants.size())
            throw new IllegalArgumentException("VILLAGE_ROAD_VARIANT_ID_DUPLICATE");
    }
    public static CityVillageRoadSettings load() {
        return load(Path.of("config","geomantia","city_land_use","village_roads.json"));
    }
    public static CityVillageRoadSettings load(Path path) {
        try (Reader reader=Files.isRegularFile(path)?Files.newBufferedReader(path):new InputStreamReader(
                Objects.requireNonNull(CityVillageRoadSettings.class.getResourceAsStream(
                        "/geomantia/default_config/city_land_use/village_roads.json")), StandardCharsets.UTF_8)) {
            var json=JsonParser.parseReader(reader).getAsJsonObject();
            if(!json.keySet().equals(Set.of("enabled","roadKinds","seed","surfacePalette","stairBlockId",
                    "baseBlockId","decorationsEnabled","structureTreesEnabled","spacingBlocks","chance","edgeOffsetBlocks","endClearanceBlocks","variants")))
                throw new IllegalArgumentException("VILLAGE_ROAD_CONFIG_FIELDS_INVALID");
            return new Gson().fromJson(json,CityVillageRoadSettings.class);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("VILLAGE_ROAD_CONFIG_INVALID: "+path,e); }
    }
    private static void validBlock(String block) {
        if(block==null || !block.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || Set.of(
                "minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:water","minecraft:lava").contains(block))
            throw new IllegalArgumentException("VILLAGE_ROAD_BLOCK_INVALID: "+block);
    }
    private static void positiveWeight(int weight) {
        if(weight<1 || weight>10000) throw new IllegalArgumentException("VILLAGE_ROAD_WEIGHT_INVALID");
    }
}
