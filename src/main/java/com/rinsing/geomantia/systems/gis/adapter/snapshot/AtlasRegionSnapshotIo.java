package com.rinsing.geomantia.systems.gis.adapter.snapshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.gis.GisAtlasConstants;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;
import com.rinsing.geomantia.systems.gis.domain.cell.CellStateFlag;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;
import com.rinsing.geomantia.systems.gis.domain.landform.PatchFlag;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import com.rinsing.geomantia.systems.gis.domain.region.RegionStatus;
import com.rinsing.geomantia.systems.gis.preview.AtlasJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class AtlasRegionSnapshotIo {
    public void write(AtlasRegion region, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("snapshotPurpose", "test-reproduction-and-field-restore");
        root.addProperty("productionPersistence", false);
        root.addProperty("atlasVersion", GisAtlasConstants.ATLAS_VERSION);
        root.addProperty("configVersion", GisAtlasConstants.CONFIG_VERSION);
        root.addProperty("regionId", region.regionId());
        root.addProperty("dimensionId", region.dimensionId());
        root.addProperty("regionX", region.regionX());
        root.addProperty("regionZ", region.regionZ());
        root.addProperty("blockMinX", region.blockMinX());
        root.addProperty("blockMinZ", region.blockMinZ());
        root.addProperty("sizeChunks", region.sizeChunks());
        root.addProperty("cellStepBlocks", region.cellStepBlocks());
        root.addProperty("status", region.status().contractName());
        root.addProperty("updatedAt", region.updatedAt());
        JsonArray cells = new JsonArray();
        for (AtlasCell cell : region.cells()) {
            cells.add(cellToJson(cell));
        }
        root.add("cells", cells);
        JsonArray patches = new JsonArray();
        for (LandformPatch patch : region.patches()) {
            patches.add(patchToJson(patch));
        }
        root.add("patches", patches);
        Files.writeString(path, AtlasJson.GSON.toJson(root));
    }

    public AtlasRegion read(Path path, GisSampleConfig config) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        AtlasRegion region = new AtlasRegion(
                root.get("dimensionId").getAsString(),
                root.get("regionX").getAsInt(),
                root.get("regionZ").getAsInt(),
                config
        );
        region.setStatus(RegionStatus.fromContractName(root.get("status").getAsString()));
        for (var element : root.getAsJsonArray("cells")) {
            JsonObject json = element.getAsJsonObject();
            AtlasCell cell = region.cell(json.get("localCellX").getAsInt(), json.get("localCellZ").getAsInt());
            if (cell == null) {
                continue;
            }
            Set<CellStateFlag> flags = cellStateFlags(json);
            if (flags.contains(CellStateFlag.SAMPLED)) {
                cell.setSample(
                        SampleSource.fromContractName(json.get("sampleSource").getAsString()),
                        json.get("elevation").getAsDouble(),
                        SurfaceType.fromContractName(json.get("surfaceType").getAsString()),
                        json.get("biomeId").getAsString(),
                        json.get("isWater").getAsBoolean(),
                        json.get("waterDepth").getAsDouble()
                );
            }
            if (flags.contains(CellStateFlag.METRICS_READY_SMALL)) {
                cell.setSmallMetrics(
                        json.get("slope").getAsDouble(),
                        json.get("localRelief").getAsDouble(),
                        json.get("roughness").getAsDouble(),
                        json.get("tpiSmall").getAsDouble()
                );
            }
            if (flags.contains(CellStateFlag.METRICS_READY_LARGE)) {
                cell.setLargeMetrics(json.get("tpiLarge").getAsDouble(), json.get("waterDistance").getAsDouble());
            }
            if (flags.contains(CellStateFlag.LANDFORM_READY)) {
                cell.setLandformType(LandformType.fromContractName(json.get("landformType").getAsString()));
            }
            if (json.has("patchId") && !json.get("patchId").getAsString().isBlank()) {
                cell.setPatchId(json.get("patchId").getAsString());
            }
            for (var flagElement : json.getAsJsonArray("stateFlags")) {
                CellStateFlag flag = stateFlag(flagElement.getAsString());
                if (flag != null) {
                    cell.addFlag(flag);
                }
            }
        }
        List<LandformPatch> patches = new ArrayList<>();
        for (var element : root.getAsJsonArray("patches")) {
            patches.add(patchFromJson(element.getAsJsonObject()));
        }
        region.replacePatches(patches);
        return region;
    }

    private static JsonObject cellToJson(AtlasCell cell) {
        JsonObject json = new JsonObject();
        json.addProperty("regionId", cell.regionId());
        json.addProperty("cellX", cell.globalCellX());
        json.addProperty("cellZ", cell.globalCellZ());
        json.addProperty("localCellX", cell.localCellX());
        json.addProperty("localCellZ", cell.localCellZ());
        json.addProperty("blockMinX", cell.blockMinX());
        json.addProperty("blockMinZ", cell.blockMinZ());
        json.addProperty("sampleSource", cell.sampleSource().contractName());
        json.addProperty("elevation", cell.elevation());
        json.addProperty("surfaceType", cell.surfaceType().contractName());
        json.addProperty("biomeId", cell.biomeId());
        json.addProperty("isWater", cell.isWater());
        json.addProperty("waterDepth", cell.waterDepth());
        json.addProperty("slope", cell.slope());
        json.addProperty("localRelief", cell.localRelief());
        json.addProperty("roughness", cell.roughness());
        json.addProperty("tpiSmall", cell.tpiSmall());
        json.addProperty("tpiLarge", cell.tpiLarge());
        json.addProperty("waterDistance", Double.isFinite(cell.waterDistance()) ? cell.waterDistance() : 9999.0);
        json.addProperty("landformType", cell.landformType().contractName());
        json.addProperty("patchId", cell.patchId());
        JsonArray flags = new JsonArray();
        for (CellStateFlag flag : cell.stateFlags()) {
            flags.add(flag.contractName());
        }
        json.add("stateFlags", flags);
        return json;
    }

    private static JsonObject patchToJson(LandformPatch patch) {
        JsonObject json = new JsonObject();
        json.addProperty("patchId", patch.patchId());
        json.addProperty("regionId", patch.regionId());
        json.addProperty("landformType", patch.landformType().contractName());
        json.addProperty("cellCount", patch.cellCount());
        json.addProperty("blockMinX", patch.blockMinX());
        json.addProperty("blockMinZ", patch.blockMinZ());
        json.addProperty("blockMaxX", patch.blockMaxX());
        json.addProperty("blockMaxZ", patch.blockMaxZ());
        json.addProperty("meanElevation", patch.meanElevation());
        json.addProperty("minElevation", patch.minElevation());
        json.addProperty("maxElevation", patch.maxElevation());
        json.addProperty("meanSlope", patch.meanSlope());
        json.addProperty("waterDistanceMean", patch.waterDistanceMean());
        json.addProperty("touchesWater", patch.touchesWater());
        json.addProperty("touchesRegionEdge", patch.touchesRegionEdge());
        json.addProperty("confidence", patch.confidence());
        JsonArray flags = new JsonArray();
        for (PatchFlag flag : patch.flags()) {
            flags.add(flag.contractName());
        }
        json.add("flags", flags);
        return json;
    }

    private static LandformPatch patchFromJson(JsonObject json) {
        Set<PatchFlag> flags = EnumSet.noneOf(PatchFlag.class);
        for (var flagElement : json.getAsJsonArray("flags")) {
            PatchFlag flag = patchFlag(flagElement.getAsString());
            if (flag != null) {
                flags.add(flag);
            }
        }
        return new LandformPatch(
                json.get("patchId").getAsString(),
                json.get("regionId").getAsString(),
                LandformType.fromContractName(json.get("landformType").getAsString()),
                json.get("cellCount").getAsInt(),
                json.get("blockMinX").getAsInt(),
                json.get("blockMinZ").getAsInt(),
                json.get("blockMaxX").getAsInt(),
                json.get("blockMaxZ").getAsInt(),
                json.get("meanElevation").getAsDouble(),
                json.get("minElevation").getAsDouble(),
                json.get("maxElevation").getAsDouble(),
                json.get("meanSlope").getAsDouble(),
                json.get("waterDistanceMean").getAsDouble(),
                json.get("touchesWater").getAsBoolean(),
                json.get("touchesRegionEdge").getAsBoolean(),
                json.get("confidence").getAsDouble(),
                flags
        );
    }

    private static Set<CellStateFlag> cellStateFlags(JsonObject json) {
        Set<CellStateFlag> flags = EnumSet.noneOf(CellStateFlag.class);
        for (var flagElement : json.getAsJsonArray("stateFlags")) {
            CellStateFlag flag = stateFlag(flagElement.getAsString());
            if (flag != null) {
                flags.add(flag);
            }
        }
        return flags;
    }

    private static CellStateFlag stateFlag(String value) {
        for (CellStateFlag flag : CellStateFlag.values()) {
            if (flag.contractName().equals(value)) {
                return flag;
            }
        }
        return null;
    }

    private static PatchFlag patchFlag(String value) {
        for (PatchFlag flag : PatchFlag.values()) {
            if (flag.contractName().equals(value)) {
                return flag;
            }
        }
        return null;
    }
}
