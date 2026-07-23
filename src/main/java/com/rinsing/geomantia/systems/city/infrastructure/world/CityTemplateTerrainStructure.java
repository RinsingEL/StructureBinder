package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.Optional;

/** A registry-backed shell used only for City fixed-template terrain adaptation. */
public final class CityTemplateTerrainStructure extends Structure {
    public static final Codec<CityTemplateTerrainStructure> CODEC =
            Structure.simpleCodec(CityTemplateTerrainStructure::new);

    public CityTemplateTerrainStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        // City injects an already selected start; this structure must never enter natural selection.
        return Optional.empty();
    }

    @Override
    public StructureType<?> type() {
        return CityTemplateTerrainStructureRegistries.CITY_TEMPLATE_TERRAIN_TYPE.get();
    }
}
