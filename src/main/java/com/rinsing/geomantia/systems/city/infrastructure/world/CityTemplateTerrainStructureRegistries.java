package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.GeomantiaMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Persistent registry entries required by the City template StructureStart experiment. */
public final class CityTemplateTerrainStructureRegistries {
    public static final ResourceLocation CITY_TEMPLATE_TERRAIN_STRUCTURE_ID =
            new ResourceLocation(GeomantiaMod.MOD_ID, "city_template_terrain");

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, GeomantiaMod.MOD_ID);
    public static final DeferredRegister<StructurePieceType> STRUCTURE_PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, GeomantiaMod.MOD_ID);

    public static final RegistryObject<StructureType<CityTemplateTerrainStructure>> CITY_TEMPLATE_TERRAIN_TYPE =
            STRUCTURE_TYPES.register("city_template_terrain", () -> () -> CityTemplateTerrainStructure.CODEC);
    public static final RegistryObject<StructurePieceType> CITY_TEMPLATE_TERRAIN_PIECE =
            STRUCTURE_PIECES.register("city_template_terrain_piece",
                    () -> CityTemplateTerrainStructurePiece::new);

    private CityTemplateTerrainStructureRegistries() {
    }

    public static void register(IEventBus eventBus) {
        STRUCTURE_TYPES.register(eventBus);
        STRUCTURE_PIECES.register(eventBus);
    }
}
