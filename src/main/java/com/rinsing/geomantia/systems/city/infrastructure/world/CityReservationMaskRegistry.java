package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class CityReservationMaskRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String PLANNED_REGISTRY_SCHEMA = "city_active_planned_structure_registry.v0.1";
    public static final String WORLDGEN_LEDGER_SCHEMA = "city_worldgen_structure_ledger.v0.1";

    private static final String ACTIVE_DIR = "geomantia_city_masks";
    private static final String ACTIVE_MASK_FILE = "active_reservation_mask_plan.json";
    private static final String ACTIVE_PLANNED_FILE = "active_planned_structure_registry.json";
    private static final String WORLDGEN_LEDGER_FILE = "worldgen_placement_ledger.json";

    private static volatile ActiveMask activeMask = ActiveMask.empty();
    private static volatile ActivePlannedStructures activePlannedStructures = ActivePlannedStructures.empty();
    private static volatile JsonObject worldgenLedger = emptyWorldgenLedger();
    private static volatile Path activeServerRoot;
    private static volatile long featureHookCalls;
    private static volatile long structureHookCalls;

    private CityReservationMaskRegistry() {
    }

    public static synchronized void activate(JsonObject reservationMaskPlan, Path serverRoot) throws IOException {
        activate(reservationMaskPlan, null, "", "", serverRoot);
    }

    public static synchronized JsonObject activate(JsonObject reservationMaskPlan,
                                                   JsonObject structureAnchorMap,
                                                   String runId,
                                                   String citySeedId,
                                                   Path serverRoot) throws IOException {
        return activate(reservationMaskPlan, structureAnchorMap, null, runId, citySeedId, serverRoot);
    }

    public static synchronized JsonObject activate(JsonObject reservationMaskPlan,
                                                   JsonObject structureAnchorMap,
                                                   JsonObject materializationPlan,
                                                   String runId,
                                                   String citySeedId,
                                                   Path serverRoot) throws IOException {
        activeServerRoot = serverRoot;
        activeMask = ActiveMask.from(reservationMaskPlan);
        if (materializationPlan != null) {
            activePlannedStructures = ActivePlannedStructures.fromMaterializationPlan(
                    materializationPlan, runId, citySeedId);
        } else if (structureAnchorMap != null) {
            activePlannedStructures = ActivePlannedStructures.from(structureAnchorMap, runId, citySeedId);
        }
        if (serverRoot != null) {
            Path dir = activeDir(serverRoot);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(ACTIVE_MASK_FILE), CityJson.GSON.toJson(reservationMaskPlan));
            if (materializationPlan != null || structureAnchorMap != null) {
                Files.writeString(dir.resolve(ACTIVE_PLANNED_FILE),
                        CityJson.GSON.toJson(activePlannedStructures.asJson()));
            }
            Path ledgerPath = dir.resolve(WORLDGEN_LEDGER_FILE);
            if (Files.exists(ledgerPath)) {
                try {
                    worldgenLedger = JsonParser.parseString(Files.readString(ledgerPath)).getAsJsonObject();
                } catch (Exception ignored) {
                    worldgenLedger = emptyWorldgenLedger();
                }
            } else {
                worldgenLedger = emptyWorldgenLedger();
                persistWorldgenLedger();
            }
        }
        LOGGER.info("Activated City reservation mask: noVegetation={}, noVanillaStructure={}, plannedStructures={}, serverRoot={}",
                activeMask.noVegetation.size(), activeMask.noVanillaStructure.size(),
                activePlannedStructures.plannedStructures.size(), serverRoot);
        return activePlannedStructures.asJson();
    }

    public static synchronized void load(Path serverRoot) {
        if (serverRoot == null) {
            return;
        }
        activeServerRoot = serverRoot;
        Path dir = activeDir(serverRoot);
        Path maskPath = dir.resolve(ACTIVE_MASK_FILE);
        if (Files.exists(maskPath)) {
            try {
                activeMask = ActiveMask.from(JsonParser.parseString(Files.readString(maskPath)).getAsJsonObject());
            } catch (Exception ignored) {
                activeMask = ActiveMask.empty();
            }
        }
        Path plannedPath = dir.resolve(ACTIVE_PLANNED_FILE);
        if (Files.exists(plannedPath)) {
            try {
                activePlannedStructures = ActivePlannedStructures.fromRegistry(
                        JsonParser.parseString(Files.readString(plannedPath)).getAsJsonObject());
            } catch (Exception ignored) {
                activePlannedStructures = ActivePlannedStructures.empty();
            }
        }
        Path ledgerPath = dir.resolve(WORLDGEN_LEDGER_FILE);
        if (Files.exists(ledgerPath)) {
            try {
                worldgenLedger = JsonParser.parseString(Files.readString(ledgerPath)).getAsJsonObject();
            } catch (Exception ignored) {
                worldgenLedger = emptyWorldgenLedger();
            }
        }
        LOGGER.info("Loaded City reservation mask registry: noVegetation={}, noVanillaStructure={}, plannedStructures={}, ledgerPlacements={}, serverRoot={}",
                activeMask.noVegetation.size(), activeMask.noVanillaStructure.size(),
                activePlannedStructures.plannedStructures.size(), ledgerPlacedStructures().size(), serverRoot);
    }

    public static boolean hooksAvailable() {
        return true;
    }

    public static void recordFeatureHookCall() {
        featureHookCalls++;
    }

    public static void recordStructureHookCall(ChunkPos chunkPos) {
        structureHookCalls++;
        ActivePlannedStructures registry = activePlannedStructures;
        if (registry.plannedStructures.isEmpty() || chunkPos == null) {
            return;
        }
        for (PlannedStructure planned : registry.plannedStructures) {
            if (planned.anchorChunkX() == chunkPos.x && planned.anchorChunkZ() == chunkPos.z) {
                LOGGER.info("City worldgen structure hook reached planned anchor chunk {},{} for {} ({})",
                        chunkPos.x, chunkPos.z, planned.anchorId(), planned.structureId());
                return;
            }
        }
    }

    public static boolean suppressFeature(ConfiguredFeature<?, ?> feature, BlockPos origin) {
        recordFeatureHookCall();
        ActiveMask mask = activeMask;
        if (mask.noVegetation.isEmpty() || origin == null || feature == null) {
            return false;
        }
        String description = feature.toString().toLowerCase(Locale.ROOT);
        if (!vegetationLike(description)) {
            return false;
        }
        boolean suppressed = mask.containsNoVegetation(origin.getX(), origin.getZ());
        if (suppressed) {
            recordFeatureSuppression(feature.toString(), origin);
        }
        return suppressed;
    }

    public static boolean suppressVanillaStructure(Structure structure, ChunkPos chunkPos) {
        recordStructureHookCall(chunkPos);
        ActiveMask mask = activeMask;
        if (mask.noVanillaStructure.isEmpty() || chunkPos == null || structure == null) {
            return false;
        }
        String structureName = structure.toString().toLowerCase(Locale.ROOT);
        ResourceLocation typeId = net.minecraft.core.registries.BuiltInRegistries.STRUCTURE_TYPE
                .getKey(structure.type());
        if (structureName.contains("geomantia") || (typeId != null && typeId.getNamespace().equals("geomantia"))) {
            return false;
        }
        BlockBounds chunkBounds = new BlockBounds(chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX(), chunkPos.getMaxBlockZ());
        return mask.overlapsNoVanillaStructure(chunkBounds);
    }

    public static List<PlannedStructure> plannedStructuresForChunk(ChunkPos chunkPos) {
        ActivePlannedStructures registry = activePlannedStructures;
        if (chunkPos == null || registry.plannedStructures.isEmpty()) {
            return List.of();
        }
        List<PlannedStructure> result = new ArrayList<>();
        for (PlannedStructure planned : registry.plannedStructures) {
            if (planned.anchorChunkX() == chunkPos.x && planned.anchorChunkZ() == chunkPos.z
                    && !ledgerContains(planned)) {
                result.add(planned);
            }
        }
        if (!result.isEmpty()) {
            LOGGER.info("City planned structures matched chunk {},{}: {}", chunkPos.x, chunkPos.z, result.size());
        }
        return List.copyOf(result);
    }

    public static boolean hasWorldgenLedger(String anchorId) {
        ActivePlannedStructures registry = activePlannedStructures;
        return ledgerContains(registry.runId(), registry.citySeedId(), registry.cityId(), anchorId);
    }

    public static boolean overlapsWorldgenLedger(BlockBounds candidate, String exceptAnchorId) {
        if (candidate == null) {
            return false;
        }
        for (JsonElement elem : ledgerPlacedStructures()) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            if (exceptAnchorId != null && exceptAnchorId.equals(stringValue(obj, "anchorId", ""))) {
                continue;
            }
            if (obj.has("actualFootprint") && obj.get("actualFootprint").isJsonObject()
                    && bounds(obj.getAsJsonObject("actualFootprint")).overlaps(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void recordWorldgenPlacement(PlannedStructure planned,
                                                            BlockBounds actualFootprint,
                                                            String startSignature,
                                                            JsonArray pieceBoxes,
                                                            ChunkPos generatingChunk,
                                                            String terrainAdaptation,
                                                            String reasonCode,
                                                            String message) {
        JsonArray placed = ledgerPlacedStructures();
        for (JsonElement elem : placed) {
            if (elem.isJsonObject() && ledgerIdentityMatches(planned, elem.getAsJsonObject())) {
                return;
            }
        }
        JsonObject obj = planned.asLedgerJson(actualFootprint, startSignature, pieceBoxes);
        obj.addProperty("reasonCode", reasonCode == null || reasonCode.isBlank()
                ? "WORLDGEN_PLACEMENT_RECORDED" : reasonCode);
        obj.addProperty("message", message == null ? "" : message);
        obj.addProperty("generatedAt", Instant.now().toString());
        obj.addProperty("generatingChunkX", generatingChunk.x);
        obj.addProperty("generatingChunkZ", generatingChunk.z);
        obj.addProperty("featureStagePending", true);
        obj.addProperty("terrainAdaptation", terrainAdaptation == null || terrainAdaptation.isBlank()
                ? "unknown" : terrainAdaptation);
        obj.addProperty("terrainAdaptationHookAvailable", false);
        obj.addProperty("beardifierSeen", false);
        obj.addProperty("terrainAdaptationReasonCode", "CITY_TERRAIN_ADAPTATION_HOOK_UNAVAILABLE");
        placed.add(obj);
        persistWorldgenLedger();
        LOGGER.info("Recorded City worldgen placement {} {} at chunk {},{} footprint {}",
                planned.anchorId(), planned.structureId(), generatingChunk.x, generatingChunk.z, actualFootprint);
    }

    public static synchronized void recordWorldgenFailure(PlannedStructure planned,
                                                          ChunkPos generatingChunk,
                                                          String reasonCode,
                                                          String message) {
        JsonArray failures = ensureArray(worldgenLedger, "failures");
        JsonObject obj = new JsonObject();
        obj.addProperty("anchorId", planned.anchorId());
        obj.addProperty("structureId", planned.structureId());
        obj.addProperty("reasonCode", reasonCode == null ? "WORLDGEN_PLACEMENT_FAILED" : reasonCode);
        obj.addProperty("message", message == null ? "" : message);
        obj.addProperty("generatedAt", Instant.now().toString());
        obj.addProperty("generatingChunkX", generatingChunk.x);
        obj.addProperty("generatingChunkZ", generatingChunk.z);
        failures.add(obj);
        persistWorldgenLedger();
        LOGGER.warn("City worldgen placement failed for {} {} at chunk {},{}: {} {}",
                planned.anchorId(), planned.structureId(), generatingChunk.x, generatingChunk.z,
                reasonCode, message);
    }

    public static JsonObject activeSummary() {
        JsonObject obj = activeMask.asJson();
        obj.addProperty("activePlannedStructureCount", activePlannedStructures.plannedStructures.size());
        obj.addProperty("worldgenPlacementMode", true);
        obj.addProperty("worldgenLedgerCount", ledgerPlacedStructures().size());
        obj.addProperty("featureHookCalls", featureHookCalls);
        obj.addProperty("structureHookCalls", structureHookCalls);
        return obj;
    }

    public static JsonObject plannedRegistrySummary() {
        return activePlannedStructures.asJson();
    }

    public static JsonObject worldgenLedgerSnapshot() {
        return worldgenLedger.deepCopy();
    }

    public static JsonObject ledgerForCity(String cityId) {
        return ledgerForCity("", "", cityId);
    }

    public static JsonObject ledgerForCity(String runId, String citySeedId, String cityId) {
        JsonObject ledger = new JsonObject();
        ledger.addProperty("schemaVersion", "city_placed_structure_ledger.v0.1");
        if (runId != null && !runId.isBlank()) {
            ledger.addProperty("runId", runId);
        }
        if (citySeedId != null && !citySeedId.isBlank()) {
            ledger.addProperty("citySeedId", citySeedId);
        }
        ledger.addProperty("cityId", cityId);
        JsonArray placed = new JsonArray();
        for (JsonElement elem : ledgerPlacedStructures()) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            if (identityMatches(runId, citySeedId, cityId, obj)) {
                placed.add(obj.deepCopy());
            }
        }
        ledger.add("placedStructures", placed);
        return ledger;
    }

    public static int activePlannedStructureCount() {
        return activePlannedStructures.plannedStructures.size();
    }

    public static boolean hasActivePlannedStructuresFor(String runId, String citySeedId, String cityId) {
        ActivePlannedStructures registry = activePlannedStructures;
        if (registry.plannedStructures.isEmpty()) {
            return false;
        }
        if (runId != null && !runId.isBlank() && !runId.equals(registry.runId())) {
            return false;
        }
        if (citySeedId != null && !citySeedId.isBlank() && !citySeedId.equals(registry.citySeedId())) {
            return false;
        }
        return cityId == null || cityId.isBlank() || cityId.equals(registry.cityId());
    }

    public static Path plannedRegistryPath(Path serverRoot) {
        return activeDir(serverRoot).resolve(ACTIVE_PLANNED_FILE);
    }

    public static Path worldgenLedgerPath(Path serverRoot) {
        return activeDir(serverRoot).resolve(WORLDGEN_LEDGER_FILE);
    }

    private static synchronized void recordFeatureSuppression(String featureDescription, BlockPos origin) {
        JsonArray array = ensureArray(worldgenLedger, "featureSuppressions");
        if (array.size() >= 512) {
            return;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("feature", featureDescription);
        obj.addProperty("x", origin.getX());
        obj.addProperty("y", origin.getY());
        obj.addProperty("z", origin.getZ());
        obj.addProperty("recordedAt", Instant.now().toString());
        array.add(obj);
        if (array.size() == 1 || array.size() % 32 == 0) {
            persistWorldgenLedger();
        }
    }

    private static boolean ledgerContains(PlannedStructure planned) {
        for (JsonElement elem : ledgerPlacedStructures()) {
            if (elem.isJsonObject() && ledgerIdentityMatches(planned, elem.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    private static boolean ledgerContains(String runId, String citySeedId, String cityId, String anchorId) {
        for (JsonElement elem : ledgerPlacedStructures()) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            if (anchorId.equals(stringValue(obj, "anchorId", ""))
                    && identityMatches(runId, citySeedId, cityId, obj)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ledgerIdentityMatches(PlannedStructure planned, JsonObject obj) {
        if (planned == null || obj == null
                || !planned.anchorId().equals(stringValue(obj, "anchorId", ""))) {
            return false;
        }
        return identityMatches(planned.runId(), planned.citySeedId(), planned.cityId(), obj);
    }

    private static boolean identityMatches(String runId, String citySeedId, String cityId, JsonObject obj) {
        if (obj == null) {
            return false;
        }
        if (runId != null && !runId.isBlank() && !runId.equals(stringValue(obj, "runId", ""))) {
            return false;
        }
        if (citySeedId != null && !citySeedId.isBlank()
                && !citySeedId.equals(stringValue(obj, "citySeedId", ""))) {
            return false;
        }
        return cityId == null || cityId.isBlank() || cityId.equals(stringValue(obj, "cityId", ""));
    }

    private static JsonArray ledgerPlacedStructures() {
        JsonObject ledger = worldgenLedger;
        if (!ledger.has("placedStructures") || !ledger.get("placedStructures").isJsonArray()) {
            ledger.add("placedStructures", new JsonArray());
        }
        ensureArray(ledger, "failures");
        ensureArray(ledger, "featureSuppressions");
        return ledger.getAsJsonArray("placedStructures");
    }

    private static JsonArray ensureArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            obj.add(key, new JsonArray());
        }
        return obj.getAsJsonArray(key);
    }

    private static void persistWorldgenLedger() {
        Path root = activeServerRoot;
        if (root == null) {
            return;
        }
        try {
            Path dir = activeDir(root);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(WORLDGEN_LEDGER_FILE), CityJson.GSON.toJson(worldgenLedger));
        } catch (IOException ignored) {
            // Worldgen must not crash because trace persistence failed; hard failures are recorded at hook setup time.
        }
    }

    private static Path activeDir(Path serverRoot) {
        return serverRoot.resolve(ACTIVE_DIR);
    }

    private static JsonObject emptyWorldgenLedger() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", WORLDGEN_LEDGER_SCHEMA);
        obj.add("placedStructures", new JsonArray());
        obj.add("failures", new JsonArray());
        obj.add("featureSuppressions", new JsonArray());
        return obj;
    }

    private static boolean vegetationLike(String value) {
        return value.contains("tree")
                || value.contains("vegetation")
                || value.contains("flower")
                || value.contains("grass")
                || value.contains("bamboo")
                || value.contains("mushroom")
                || value.contains("vine")
                || value.contains("patch")
                || value.contains("forest");
    }

    private record ActiveMask(String cityId, List<BlockBounds> noVegetation, List<BlockBounds> noVanillaStructure,
                              List<BlockBounds> gateCorridor, List<BlockBounds> noRoadsideStructure) {
        static ActiveMask empty() {
            return new ActiveMask("", List.of(), List.of(), List.of(), List.of());
        }

        static ActiveMask from(JsonObject plan) {
            if (plan == null) {
                return empty();
            }
            JsonObject channels = plan.has("worldgenMaskChannels") && plan.get("worldgenMaskChannels").isJsonObject()
                    ? plan.getAsJsonObject("worldgenMaskChannels") : new JsonObject();
            return new ActiveMask(
                    stringValue(plan, "cityId", ""),
                    masks(plan.getAsJsonArray("noVegetationMask")),
                    masks(plan.getAsJsonArray("noVanillaStructureMask")),
                    masks(plan.getAsJsonArray("gateCorridorMask")),
                    masks(channels.getAsJsonArray("noRoadsideStructure")));
        }

        boolean containsNoVegetation(int x, int z) {
            return noVegetation.stream().anyMatch(bounds -> bounds.contains(x, z));
        }

        boolean overlapsNoVanillaStructure(BlockBounds chunkBounds) {
            return noVanillaStructure.stream().anyMatch(bounds -> bounds.overlaps(chunkBounds));
        }

        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("cityId", cityId);
            obj.addProperty("noVegetationMaskCount", noVegetation.size());
            obj.addProperty("noVanillaStructureMaskCount", noVanillaStructure.size());
            obj.addProperty("gateCorridorMaskCount", gateCorridor.size());
            obj.addProperty("noRoadsideStructureMaskCount", noRoadsideStructure.size());
            return obj;
        }
    }

    private record ActivePlannedStructures(String schemaVersion, String runId, String citySeedId, String cityId,
                                           List<PlannedStructure> plannedStructures) {
        static ActivePlannedStructures empty() {
            return new ActivePlannedStructures(PLANNED_REGISTRY_SCHEMA, "", "", "", List.of());
        }

        static ActivePlannedStructures from(JsonObject anchorMap, String runId, String citySeedId) {
            String cityId = stringValue(anchorMap, "cityId", "");
            List<PlannedStructure> structures = new ArrayList<>();
            JsonArray anchors = anchorMap == null ? null : anchorMap.getAsJsonArray("anchors");
            if (anchors != null) {
                for (JsonElement elem : anchors) {
                    if (elem.isJsonObject()) {
                        structures.add(PlannedStructure.fromAnchor(
                                elem.getAsJsonObject(), runId, citySeedId, cityId));
                    }
                }
            }
            return new ActivePlannedStructures(PLANNED_REGISTRY_SCHEMA,
                    nullToEmpty(runId), nullToEmpty(citySeedId), cityId, List.copyOf(structures));
        }

        static ActivePlannedStructures fromMaterializationPlan(JsonObject plan, String runId, String citySeedId) {
            String cityId = stringValue(plan, "cityId", "");
            List<PlannedStructure> structures = new ArrayList<>();
            JsonArray planned = plan == null ? null : plan.getAsJsonArray("plannedWorldgenStructures");
            if (planned != null) {
                for (JsonElement elem : planned) {
                    if (elem.isJsonObject()) {
                        JsonObject item = elem.getAsJsonObject();
                        String status = stringValue(item, "status", "");
                        if ("planned_worldgen".equals(status)) {
                            structures.add(PlannedStructure.fromAnchor(item, runId, citySeedId, cityId));
                        }
                    }
                }
            }
            return new ActivePlannedStructures(PLANNED_REGISTRY_SCHEMA,
                    nullToEmpty(runId), nullToEmpty(citySeedId), cityId, List.copyOf(structures));
        }

        static ActivePlannedStructures fromRegistry(JsonObject registry) {
            List<PlannedStructure> structures = new ArrayList<>();
            JsonArray planned = registry == null ? null : registry.getAsJsonArray("plannedStructures");
            if (planned != null) {
                for (JsonElement elem : planned) {
                    if (elem.isJsonObject()) {
                        structures.add(PlannedStructure.fromRegistry(elem.getAsJsonObject()));
                    }
                }
            }
            return new ActivePlannedStructures(
                    stringValue(registry, "schemaVersion", PLANNED_REGISTRY_SCHEMA),
                    stringValue(registry, "runId", ""),
                    stringValue(registry, "citySeedId", ""),
                    stringValue(registry, "cityId", ""),
                    List.copyOf(structures));
        }

        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("schemaVersion", schemaVersion);
            obj.addProperty("runId", runId);
            obj.addProperty("citySeedId", citySeedId);
            obj.addProperty("cityId", cityId);
            obj.addProperty("worldgenPlacementMode", true);
            JsonArray array = new JsonArray();
            plannedStructures.forEach(planned -> array.add(planned.asJson()));
            obj.add("plannedStructures", array);
            return obj;
        }
    }

    public record PlannedStructure(String runId, String citySeedId, String cityId, String anchorId,
                                   String structureId, int anchorChunkX, int anchorChunkZ,
                                   BlockPoint anchorBlock, String rotation, BlockBounds plannedFootprint,
                                   BlockBounds reservedEnvelope, BlockBounds lockedActualFootprint,
                                   BlockBounds collisionEnvelope,
                                   BlockBounds maskEnvelope,
                                   String envelopeMode, String selectedEnvelopeGroupKey,
                                   String expectedStartSignature,
                                   JsonArray sourcePatchIds, JsonArray semanticTerms, JsonArray functionTerms,
                                   JsonArray styleTerms, JsonArray placementTerms, JsonArray usageTerms,
                                   JsonArray qualityTerms) {
        static PlannedStructure fromAnchor(JsonObject anchor, String runId, String citySeedId, String cityId) {
            BlockPoint anchorBlock = blockPoint(requiredObject(anchor, "anchorBlock"));
            BlockBounds reserved = bounds(requiredObject(anchor, "reservedEnvelope"));
            return new PlannedStructure(
                    nullToEmpty(runId),
                    nullToEmpty(citySeedId),
                    nullToEmpty(cityId),
                    requiredString(anchor, "anchorId"),
                    requiredString(anchor, "structureId"),
                    Math.floorDiv(anchorBlock.x(), 16),
                    Math.floorDiv(anchorBlock.z(), 16),
                    anchorBlock,
                    stringValue(anchor, "rotation", "NONE"),
                    bounds(requiredObject(anchor, "plannedFootprint")),
                    reserved,
                    optionalBounds(anchor, "lockedActualFootprint", optionalBounds(anchor, "actualFootprint",
                            bounds(requiredObject(anchor, "plannedFootprint")))),
                    optionalBounds(anchor, "collisionEnvelope", reserved),
                    optionalBounds(anchor, "maskEnvelope", reserved),
                    stringValue(anchor, "envelopeMode", ""),
                    stringValue(anchor, "selectedEnvelopeGroupKey", ""),
                    stringValue(anchor, "expectedStartSignature", ""),
                    sourcePatchIdsFromPatches(anchor.getAsJsonArray("sourcePatches")),
                    copyArray(anchor.getAsJsonArray("semanticTerms")),
                    copyArray(anchor.getAsJsonArray("functionTerms")),
                    copyArray(anchor.getAsJsonArray("styleTerms")),
                    copyArray(anchor.getAsJsonArray("placementTerms")),
                    copyArray(anchor.getAsJsonArray("usageTerms")),
                    copyArray(anchor.getAsJsonArray("qualityTerms")));
        }

        static PlannedStructure fromRegistry(JsonObject obj) {
            JsonObject anchorBlock = requiredObject(obj, "anchorBlock");
            JsonObject anchorChunk = requiredObject(obj, "anchorChunk");
            BlockBounds reserved = bounds(requiredObject(obj, "reservedEnvelope"));
            return new PlannedStructure(
                    stringValue(obj, "runId", ""),
                    stringValue(obj, "citySeedId", ""),
                    stringValue(obj, "cityId", ""),
                    requiredString(obj, "anchorId"),
                    requiredString(obj, "structureId"),
                    intValue(anchorChunk, "x", 0),
                    intValue(anchorChunk, "z", 0),
                    blockPoint(anchorBlock),
                    stringValue(obj, "rotation", "NONE"),
                    bounds(requiredObject(obj, "plannedFootprint")),
                    reserved,
                    optionalBounds(obj, "lockedActualFootprint", optionalBounds(obj, "actualFootprint",
                            bounds(requiredObject(obj, "plannedFootprint")))),
                    optionalBounds(obj, "collisionEnvelope", reserved),
                    optionalBounds(obj, "maskEnvelope", reserved),
                    stringValue(obj, "envelopeMode", ""),
                    stringValue(obj, "selectedEnvelopeGroupKey", ""),
                    stringValue(obj, "expectedStartSignature", ""),
                    copyArray(obj.getAsJsonArray("sourcePatchIds")),
                    copyArray(obj.getAsJsonArray("semanticTerms")),
                    copyArray(obj.getAsJsonArray("functionTerms")),
                    copyArray(obj.getAsJsonArray("styleTerms")),
                    copyArray(obj.getAsJsonArray("placementTerms")),
                    copyArray(obj.getAsJsonArray("usageTerms")),
                    copyArray(obj.getAsJsonArray("qualityTerms")));
        }

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("runId", runId);
            obj.addProperty("citySeedId", citySeedId);
            obj.addProperty("cityId", cityId);
            obj.addProperty("anchorId", anchorId);
            obj.addProperty("structureId", structureId);
            JsonObject chunk = new JsonObject();
            chunk.addProperty("x", anchorChunkX);
            chunk.addProperty("z", anchorChunkZ);
            obj.add("anchorChunk", chunk);
            obj.add("anchorBlock", anchorBlock.asJson());
            obj.addProperty("rotation", rotation);
            obj.add("plannedFootprint", boundsJson(plannedFootprint));
            obj.add("reservedEnvelope", boundsJson(reservedEnvelope));
            obj.add("lockedActualFootprint", boundsJson(lockedActualFootprint));
            obj.add("collisionEnvelope", boundsJson(collisionEnvelope));
            obj.addProperty("locked", !expectedStartSignature.isBlank());
            obj.add("lockedCollisionEnvelope", boundsJson(collisionEnvelope));
            obj.add("maskEnvelope", boundsJson(maskEnvelope));
            obj.addProperty("envelopeMode", envelopeMode);
            obj.addProperty("selectedEnvelopeGroupKey", selectedEnvelopeGroupKey);
            obj.addProperty("expectedStartSignature", expectedStartSignature);
            obj.add("sourcePatchIds", sourcePatchIds.deepCopy());
            obj.add("semanticTerms", semanticTerms.deepCopy());
            obj.add("functionTerms", functionTerms.deepCopy());
            obj.add("styleTerms", styleTerms.deepCopy());
            obj.add("placementTerms", placementTerms.deepCopy());
            obj.add("usageTerms", usageTerms.deepCopy());
            obj.add("qualityTerms", qualityTerms.deepCopy());
            return obj;
        }

        JsonObject asLedgerJson(BlockBounds actualFootprint, String startSignature, JsonArray pieceBoxes) {
            JsonObject obj = asJson();
            obj.add("actualFootprint", boundsJson(actualFootprint));
            obj.add("lockedActualFootprint", boundsJson(actualFootprint));
            obj.addProperty("startSignature", startSignature == null ? "" : startSignature);
            obj.add("pieceBoxes", pieceBoxes == null ? new JsonArray() : pieceBoxes.deepCopy());
            obj.addProperty("worldMutationApplied", true);
            obj.addProperty("worldgenPlacement", true);
            obj.addProperty("lateMaterialization", false);
            return obj;
        }
    }

    private static List<BlockBounds> masks(JsonArray array) {
        List<BlockBounds> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement elem : array) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            JsonObject bounds = obj.has("blockBounds") && obj.get("blockBounds").isJsonObject()
                    ? obj.getAsJsonObject("blockBounds")
                    : obj;
            result.add(bounds(bounds));
        }
        return List.copyOf(result);
    }

    private static JsonArray sourcePatchIdsFromPatches(JsonArray sourcePatches) {
        Set<String> values = new LinkedHashSet<>();
        if (sourcePatches != null) {
            for (JsonElement elem : sourcePatches) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject patch = elem.getAsJsonObject();
                Optional.ofNullable(stringValue(patch, "landformPatchId", null))
                        .filter(value -> !value.isBlank())
                        .ifPresent(values::add);
                Optional.ofNullable(stringValue(patch, "mapLabel", null))
                        .filter(value -> !value.isBlank())
                        .ifPresent(values::add);
            }
        }
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonArray copyArray(JsonArray array) {
        return array == null ? new JsonArray() : array.deepCopy();
    }

    private static BlockPoint blockPoint(JsonObject obj) {
        return new BlockPoint(intValue(obj, "x", 0), intValue(obj, "z", 0));
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static BlockBounds optionalBounds(JsonObject obj, String key, BlockBounds fallback) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject()
                ? bounds(obj.getAsJsonObject(key))
                : fallback;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return obj.getAsJsonObject(key);
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
