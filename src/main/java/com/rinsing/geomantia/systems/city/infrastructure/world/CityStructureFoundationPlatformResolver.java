package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.terrain.CityTerrainFoundationDensityComputer;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Resolves frozen full-footprint structure platforms for the Beardifier density pass. */
public final class CityStructureFoundationPlatformResolver {
    static final String FULL_FOOTPRINT_SUPPORT = "full_footprint_support";
    static final int MAX_DEPTH_BLOCKS = 32;
    static final int SHOULDER_BLOCKS = 2;

    private CityStructureFoundationPlatformResolver() {
    }

    public static List<CityTerrainFoundationDensityComputer.FoundationPlatformView> forChunk(ChunkPos chunkPos) {
        List<CityTerrainFoundationDensityComputer.FoundationPlatformView> result = new ArrayList<>();
        for (CityReservationMaskRegistry.PlannedStructure planned
                : CityReservationMaskRegistry.plannedStructuresForChunk(chunkPos)) {
            if (!CityTemplateTerrainStartPolicy.usesStructureStart(planned)
                    || !FULL_FOOTPRINT_SUPPORT.equals(supportPolicy(planned.templatePlan()))) {
                continue;
            }
            OptionalInt datum = CityReservationMaskRegistry.resolvedTemplateDatum(planned);
            if (datum.isEmpty()) continue;
            BlockBounds footprint = planned.lockedActualFootprint();
            result.add(new Platform(footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ(),
                    datum.getAsInt(), MAX_DEPTH_BLOCKS, SHOULDER_BLOCKS));
        }
        return List.copyOf(result);
    }

    static String supportPolicy(JsonObject plan) {
        if (plan == null) return "";
        if (plan.has("supportPolicy") && !plan.get("supportPolicy").isJsonNull()) {
            return plan.get("supportPolicy").getAsString();
        }
        if (plan.has("structureTemplate") && plan.get("structureTemplate").isJsonObject()) {
            JsonObject template = plan.getAsJsonObject("structureTemplate");
            if (template.has("supportPolicy") && !template.get("supportPolicy").isJsonNull()) {
                return template.get("supportPolicy").getAsString();
            }
        }
        return "";
    }

    private record Platform(int minX, int minZ, int maxX, int maxZ, int targetY,
                            int maxDepthBlocks, int shoulderBlocks)
            implements CityTerrainFoundationDensityComputer.FoundationPlatformView {
    }
}
