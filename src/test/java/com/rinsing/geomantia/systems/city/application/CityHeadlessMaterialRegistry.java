package com.rinsing.geomantia.systems.city.application;
import java.util.Set;
/** Synthetic installed blocks for headless tests; production always reads the live registry. */
final class CityHeadlessMaterialRegistry implements CityBlockMaterials.Registry {
    public Set<String> ids() { return Set.of("minecraft:stone","minecraft:stone_brick_slab"); }
    public CityBlockMaterials.BlockInfo get(String id) {
        if(!ids().contains(id)) return null;
        return new CityBlockMaterials.BlockInfo(id,id,"",id.equals("minecraft:stone")?CityBlockMaterials.Shape.BLOCK:CityBlockMaterials.Shape.SLAB,true);
    }
}
