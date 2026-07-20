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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
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
            if (planned.coversChunk(chunkPos)) {
                LOGGER.info("City worldgen structure hook reached planned owner chunk {},{} for {} ({})",
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

    public static synchronized List<PlannedStructure> plannedStructuresForChunk(ChunkPos chunkPos) {
        ActivePlannedStructures registry = activePlannedStructures;
        if (chunkPos == null || registry.plannedStructures.isEmpty()) {
            return List.of();
        }
        List<PlannedStructure> result = new ArrayList<>();
        for (PlannedStructure planned : registry.plannedStructures) {
            boolean pendingTemplateFragment = planned.isTemplatePlacement()
                    && planned.coversChunk(chunkPos)
                    && !templateFragmentRecorded(planned, chunkPos);
            boolean pendingConfiguredStructure = !planned.isTemplatePlacement()
                    && planned.anchorChunkX() == chunkPos.x
                    && planned.anchorChunkZ() == chunkPos.z;
            if (!ledgerContains(planned) && (pendingTemplateFragment || pendingConfiguredStructure)) {
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

    /**
     * Durably registers an owner as observed during its first FEATURES pass and, for the anchor
     * owner only, freezes the template-level surface datum. Callers must not write blocks unless
     * this method returns READY.
     */
    public static synchronized TemplateDatumPreparation prepareTemplateOwner(
            PlannedStructure planned,
            ChunkPos ownerChunk,
            OptionalInt anchorDatumCandidate,
            int minBuildHeight) {
        if (planned == null || ownerChunk == null || !planned.isTemplatePlacement()
                || !planned.coversChunk(ownerChunk)) {
            return TemplateDatumPreparation.invalid("TEMPLATE_OWNER_INVALID",
                    "Template owner is not covered by the locked footprint.");
        }
        if (activeServerRoot == null) {
            return TemplateDatumPreparation.persistenceFailed();
        }
        OptionalInt candidate = anchorDatumCandidate == null ? OptionalInt.empty() : anchorDatumCandidate;
        if (candidate.isPresent()
                && (ownerChunk.x != planned.anchorChunkX() || ownerChunk.z != planned.anchorChunkZ())) {
            return TemplateDatumPreparation.conflict("TEMPLATE_DATUM_OWNER_MISMATCH",
                    "Only the anchor owner may resolve templateDatumY.");
        }

        JsonObject before = worldgenLedger.deepCopy();
        if (!templatePendingRecorded(planned, ownerChunk)) {
            JsonObject pending = templateIdentity(planned);
            pending.addProperty("generatingChunkX", ownerChunk.x);
            pending.addProperty("generatingChunkZ", ownerChunk.z);
            pending.addProperty("firstWorldgenFeaturesObserved", true);
            pending.addProperty("reasonCode", "TEMPLATE_DATUM_WAITING");
            pending.addProperty("message", "Owner was observed during first worldgen FEATURES before completion.");
            pending.addProperty("observedAt", Instant.now().toString());
            ledgerTemplatePendingFragments().add(pending);
        }

        OptionalInt resolved = resolvedTemplateDatumInternal(planned);
        if (candidate.isPresent()) {
            int datum = candidate.getAsInt();
            if (datum <= minBuildHeight) {
                if (!persistWorldgenLedger()) {
                    worldgenLedger = before;
                    return TemplateDatumPreparation.persistenceFailed();
                }
                return TemplateDatumPreparation.waiting("TEMPLATE_DATUM_SURFACE_UNAVAILABLE",
                        "Anchor owner heightmap did not provide a usable surface datum.");
            }
            if (resolved.isPresent() && resolved.getAsInt() != datum) {
                worldgenLedger = before;
                return TemplateDatumPreparation.conflict("TEMPLATE_DATUM_CONFLICT",
                        "Template datum was already frozen at " + resolved.getAsInt()
                                + " but a later callback proposed " + datum + ".");
            }
            if (resolved.isEmpty()) {
                JsonObject datumState = templateIdentity(planned);
                datumState.addProperty("templateDatumPolicy", stringValue(
                        planned.templatePlan(), "templateDatumPolicy", ""));
                datumState.addProperty("templateDatumY", datum);
                datumState.addProperty("resolvedByChunkX", ownerChunk.x);
                datumState.addProperty("resolvedByChunkZ", ownerChunk.z);
                datumState.addProperty("resolvedAt", Instant.now().toString());
                ledgerTemplateDatums().add(datumState);
                resolved = OptionalInt.of(datum);
            }
        }

        if (!persistWorldgenLedger()) {
            worldgenLedger = before;
            return TemplateDatumPreparation.persistenceFailed();
        }
        if (resolved.isEmpty()) {
            return TemplateDatumPreparation.waiting("TEMPLATE_DATUM_WAITING",
                    "Waiting for the anchor owner first-worldgen FEATURES heightmap.");
        }
        return TemplateDatumPreparation.ready(resolved.getAsInt());
    }

    /**
     * Freezes one generator-derived datum before a City StructureStart is injected. Every touched
     * chunk is durably authorized up front because the piece will be placed by vanilla structure
     * processing rather than a later FEATURES callback.
     */
    public static synchronized TemplateDatumPreparation prepareTemplateTerrainStart(
            PlannedStructure planned, int templateDatumY) {
        if (planned == null || !planned.isTemplatePlacement()) {
            return TemplateDatumPreparation.invalid("TEMPLATE_TERRAIN_START_INVALID",
                    "Only a fixed template placement may create a terrain StructureStart.");
        }
        if (activeServerRoot == null) {
            return TemplateDatumPreparation.persistenceFailed();
        }
        OptionalInt resolved = resolvedTemplateDatumInternal(planned);
        if (resolved.isPresent() && resolved.getAsInt() != templateDatumY) {
            return TemplateDatumPreparation.conflict("TEMPLATE_DATUM_CONFLICT",
                    "Template datum was already frozen at " + resolved.getAsInt()
                            + " but StructureStart proposed " + templateDatumY + ".");
        }

        JsonObject before = worldgenLedger.deepCopy();
        if (resolved.isEmpty()) {
            JsonObject datumState = templateIdentity(planned);
            datumState.addProperty("templateDatumPolicy", "generator_base_height_motion_blocking_no_leaves");
            datumState.addProperty("templateDatumY", templateDatumY);
            datumState.addProperty("resolvedByChunkX", planned.anchorChunkX());
            datumState.addProperty("resolvedByChunkZ", planned.anchorChunkZ());
            datumState.addProperty("resolvedAt", Instant.now().toString());
            ledgerTemplateDatums().add(datumState);
            resolved = OptionalInt.of(templateDatumY);
        }
        for (ChunkPos owner : templateOwnerChunks(planned)) {
            if (templatePendingRecorded(planned, owner)) {
                continue;
            }
            JsonObject pending = templateIdentity(planned);
            pending.addProperty("generatingChunkX", owner.x);
            pending.addProperty("generatingChunkZ", owner.z);
            pending.addProperty("firstWorldgenFeaturesObserved", false);
            pending.addProperty("reasonCode", "TEMPLATE_TERRAIN_START_PENDING");
            pending.addProperty("message", "Owner was authorized before StructureStart piece placement.");
            pending.addProperty("observedAt", Instant.now().toString());
            ledgerTemplatePendingFragments().add(pending);
        }
        if (!persistWorldgenLedger()) {
            worldgenLedger = before;
            return TemplateDatumPreparation.persistenceFailed();
        }
        return TemplateDatumPreparation.ready(resolved.getAsInt());
    }

    public static synchronized OptionalInt resolvedTemplateDatum(PlannedStructure planned) {
        return resolvedTemplateDatumInternal(planned);
    }

    public static synchronized boolean hasTemplatePendingProof(PlannedStructure planned, ChunkPos ownerChunk) {
        return templatePendingRecorded(planned, ownerChunk);
    }

    public static synchronized Optional<PlannedStructure> findTemplatePlacement(
            String anchorId, String templateRef, String templateHash) {
        for (PlannedStructure planned : activePlannedStructures.plannedStructures) {
            if (planned.isTemplatePlacement()
                    && planned.anchorId().equals(anchorId)
                    && templateRef(planned).equals(templateRef)
                    && templateHash(planned).equals(templateHash)) {
                return Optional.of(planned);
            }
        }
        return Optional.empty();
    }

    /** Returns only owners that are authorized for delayed first-generation retry. */
    public static synchronized List<PlannedStructure> pendingTemplateStructuresForChunk(ChunkPos ownerChunk) {
        if (ownerChunk == null) {
            return List.of();
        }
        List<PlannedStructure> result = new ArrayList<>();
        for (PlannedStructure planned : activePlannedStructures.plannedStructures) {
            if (planned.isTemplatePlacement()
                    && planned.coversChunk(ownerChunk)
                    && templatePendingRecorded(planned, ownerChunk)
                    && !templateFragmentRecorded(planned, ownerChunk)
                    && !ledgerContains(planned)) {
                result.add(planned);
            }
        }
        return List.copyOf(result);
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

    public static synchronized void recordTemplateWorldgenPlacement(PlannedStructure planned,
                                                                    BlockBounds actualFootprint,
                                                                    String startSignature,
                                                                    JsonArray pieceBoxes,
                                                                    ChunkPos generatingChunk,
                                                                    int templateDatumY,
                                                                    String terrainAdaptation,
                                                                    String reasonCode,
                                                                    String message) {
        recordTemplateWorldgenFragment(planned, actualFootprint, startSignature, pieceBoxes, generatingChunk,
                templateDatumY, terrainAdaptation, reasonCode, message);
    }

    /**
     * Records one owner-chunk write for a template. A template only enters the public placed ledger
     * after every chunk touched by its locked footprint has reported a successful write.
     */
    public static synchronized TemplateFragmentRecordResult recordTemplateWorldgenFragment(
            PlannedStructure planned,
            BlockBounds actualFootprint,
            String startSignature,
            JsonArray pieceBoxes,
            ChunkPos generatingChunk,
            int templateDatumY,
            String terrainAdaptation,
            String reasonCode,
            String message) {
        if (planned == null || actualFootprint == null || generatingChunk == null) {
            return TemplateFragmentRecordResult.rejectedResult("TEMPLATE_FRAGMENT_INVALID");
        }
        if (!planned.lockedActualFootprint().equals(actualFootprint)) {
            return TemplateFragmentRecordResult.rejectedResult("TEMPLATE_LOCKED_FOOTPRINT_MISMATCH");
        }
        if (ledgerContains(planned)) {
            return TemplateFragmentRecordResult.completedResult();
        }
        if (!templatePendingRecorded(planned, generatingChunk)) {
            return TemplateFragmentRecordResult.rejectedResult("TEMPLATE_PENDING_PROOF_MISSING");
        }
        OptionalInt frozenDatum = resolvedTemplateDatumInternal(planned);
        if (frozenDatum.isEmpty()) {
            return TemplateFragmentRecordResult.rejectedResult("TEMPLATE_DATUM_NOT_RESOLVED");
        }
        if (frozenDatum.getAsInt() != templateDatumY) {
            return TemplateFragmentRecordResult.rejectedResult("TEMPLATE_DATUM_CONFLICT");
        }
        JsonObject before = worldgenLedger.deepCopy();
        JsonArray fragments = ledgerTemplateFragments();
        if (!templateFragmentRecorded(planned, generatingChunk)) {
            JsonObject fragment = new JsonObject();
            fragment.addProperty("runId", planned.runId());
            fragment.addProperty("citySeedId", planned.citySeedId());
            fragment.addProperty("cityId", planned.cityId());
            fragment.addProperty("anchorId", planned.anchorId());
            fragment.addProperty("structureId", planned.structureId());
            fragment.addProperty("templateRef", templateRef(planned));
            fragment.addProperty("templateHash", templateHash(planned));
            fragment.addProperty("startSignature", startSignature == null ? "" : startSignature);
            fragment.add("templateFootprint", boundsJson(actualFootprint));
            fragment.add("ownerFragment", boundsJson(intersection(actualFootprint, chunkBounds(generatingChunk))));
            fragment.addProperty("templateDatumY", templateDatumY);
            fragment.addProperty("reasonCode", reasonCode == null || reasonCode.isBlank()
                    ? "TEMPLATE_FRAGMENT_RECORDED" : reasonCode);
            fragment.addProperty("message", message == null ? "" : message);
            fragment.addProperty("generatedAt", Instant.now().toString());
            fragment.addProperty("generatingChunkX", generatingChunk.x);
            fragment.addProperty("generatingChunkZ", generatingChunk.z);
            fragments.add(fragment);
        }

        List<ChunkPos> requiredOwners = templateOwnerChunks(planned);
        if (allTemplateFragmentsRecorded(planned, requiredOwners)) {
            JsonArray placed = ledgerPlacedStructures();
            JsonObject obj = planned.asLedgerJson(actualFootprint, startSignature, pieceBoxes);
            obj.addProperty("templateDatumY", frozenDatum.getAsInt());
            obj.addProperty("reasonCode", "TEMPLATE_MATERIALIZATION_RECORDED");
            obj.addProperty("message", "All " + requiredOwners.size()
                    + " template owner fragments were written during world generation.");
            obj.addProperty("generatedAt", Instant.now().toString());
            obj.addProperty("generatingChunkX", generatingChunk.x);
            obj.addProperty("generatingChunkZ", generatingChunk.z);
            obj.addProperty("templateFragmentCount", requiredOwners.size());
            obj.addProperty("featureStagePending", true);
            obj.addProperty("terrainAdaptation", terrainAdaptation == null || terrainAdaptation.isBlank()
                    ? "unknown" : terrainAdaptation);
            obj.addProperty("terrainAdaptationHookAvailable", false);
            obj.addProperty("beardifierSeen", false);
            obj.addProperty("terrainAdaptationReasonCode", "CITY_TERRAIN_ADAPTATION_HOOK_UNAVAILABLE");
            placed.add(obj);
            LOGGER.info("Recorded completed City template worldgen placement {} {} after {} owner chunks at datum {}",
                    planned.anchorId(), planned.structureId(), requiredOwners.size(), frozenDatum.getAsInt());
        }
        if (!persistWorldgenLedger()) {
            worldgenLedger = before;
            return TemplateFragmentRecordResult.rejectedResult("TEMPLATE_LEDGER_PERSISTENCE_FAILED");
        }
        return allTemplateFragmentsRecorded(planned, requiredOwners)
                ? TemplateFragmentRecordResult.completedResult()
                : TemplateFragmentRecordResult.fragmentRecorded();
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
        obj.addProperty("worldgenTemplateFragmentCount", ledgerTemplateFragments().size());
        obj.addProperty("worldgenTemplatePendingCount", ledgerTemplatePendingFragments().size());
        obj.addProperty("worldgenTemplateDatumCount", ledgerTemplateDatums().size());
        obj.addProperty("featureHookCalls", featureHookCalls);
        obj.addProperty("structureHookCalls", structureHookCalls);
        return obj;
    }

    public static JsonObject plannedRegistrySummary() {
        return activePlannedStructures.asJson();
    }

    public static synchronized JsonObject worldgenLedgerSnapshot() {
        return worldgenLedger.deepCopy();
    }

    public static JsonObject ledgerForCity(String cityId) {
        return ledgerForCity("", "", cityId);
    }

    public static synchronized JsonObject ledgerForCity(String runId, String citySeedId, String cityId) {
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

    private static JsonArray ledgerTemplateFragments() {
        return ensureArray(worldgenLedger, "templateFragments");
    }

    private static JsonArray ledgerTemplatePendingFragments() {
        return ensureArray(worldgenLedger, "templatePendingFragments");
    }

    private static JsonArray ledgerTemplateDatums() {
        return ensureArray(worldgenLedger, "templateDatums");
    }

    private static boolean templatePendingRecorded(PlannedStructure planned, ChunkPos ownerChunk) {
        if (planned == null || ownerChunk == null) {
            return false;
        }
        for (JsonElement elem : ledgerTemplatePendingFragments()) {
            if (elem.isJsonObject() && templateOwnerIdentityMatches(
                    planned, ownerChunk, elem.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    private static OptionalInt resolvedTemplateDatumInternal(PlannedStructure planned) {
        if (planned == null) {
            return OptionalInt.empty();
        }
        Integer resolved = null;
        for (JsonElement elem : ledgerTemplateDatums()) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject datum = elem.getAsJsonObject();
            if (!ledgerIdentityMatches(planned, datum)
                    || !templateHash(planned).equals(stringValue(datum, "templateHash", ""))) {
                continue;
            }
            int value = intValue(datum, "templateDatumY", Integer.MIN_VALUE);
            if (resolved != null && resolved != value) {
                LOGGER.error("Conflicting persisted template datums for {} {}: {} and {}",
                        planned.anchorId(), planned.structureId(), resolved, value);
                return OptionalInt.empty();
            }
            resolved = value;
        }
        return resolved == null ? OptionalInt.empty() : OptionalInt.of(resolved);
    }

    private static boolean templateFragmentRecorded(PlannedStructure planned, ChunkPos ownerChunk) {
        if (planned == null || ownerChunk == null) {
            return false;
        }
        for (JsonElement elem : ledgerTemplateFragments()) {
            if (elem.isJsonObject() && templateFragmentIdentityMatches(
                    planned, ownerChunk, elem.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    private static boolean allTemplateFragmentsRecorded(PlannedStructure planned, List<ChunkPos> owners) {
        return owners.stream().allMatch(owner -> templateFragmentRecorded(planned, owner));
    }

    private static boolean templateFragmentIdentityMatches(PlannedStructure planned,
                                                           ChunkPos ownerChunk,
                                                           JsonObject fragment) {
        OptionalInt frozenDatum = resolvedTemplateDatumInternal(planned);
        return frozenDatum.isPresent()
                && templateOwnerIdentityMatches(planned, ownerChunk, fragment)
                && frozenDatum.getAsInt() == intValue(fragment, "templateDatumY", Integer.MIN_VALUE);
    }

    private static boolean templateOwnerIdentityMatches(PlannedStructure planned,
                                                        ChunkPos ownerChunk,
                                                        JsonObject state) {
        return ledgerIdentityMatches(planned, state)
                && ownerChunk.x == intValue(state, "generatingChunkX", Integer.MIN_VALUE)
                && ownerChunk.z == intValue(state, "generatingChunkZ", Integer.MIN_VALUE)
                && templateHash(planned).equals(stringValue(state, "templateHash", ""));
    }

    private static JsonObject templateIdentity(PlannedStructure planned) {
        JsonObject identity = new JsonObject();
        identity.addProperty("runId", planned.runId());
        identity.addProperty("citySeedId", planned.citySeedId());
        identity.addProperty("cityId", planned.cityId());
        identity.addProperty("anchorId", planned.anchorId());
        identity.addProperty("structureId", planned.structureId());
        identity.addProperty("templateRef", templateRef(planned));
        identity.addProperty("templateHash", templateHash(planned));
        identity.addProperty("terrainPosePolicy", stringValue(planned.templatePlan(), "terrainPosePolicy", ""));
        return identity;
    }

    private static List<ChunkPos> templateOwnerChunks(PlannedStructure planned) {
        BlockBounds footprint = planned.lockedActualFootprint();
        List<ChunkPos> owners = new ArrayList<>();
        for (int x = Math.floorDiv(footprint.minX(), 16); x <= Math.floorDiv(footprint.maxX(), 16); x++) {
            for (int z = Math.floorDiv(footprint.minZ(), 16); z <= Math.floorDiv(footprint.maxZ(), 16); z++) {
                owners.add(new ChunkPos(x, z));
            }
        }
        return owners;
    }

    private static String templateHash(PlannedStructure planned) {
        return stringValue(planned.templatePlan(), "templateHash", "");
    }

    private static String templateRef(PlannedStructure planned) {
        return stringValue(planned.templatePlan(), "templateRef", "");
    }

    private static BlockBounds chunkBounds(ChunkPos chunk) {
        return new BlockBounds(chunk.getMinBlockX(), chunk.getMinBlockZ(),
                chunk.getMaxBlockX(), chunk.getMaxBlockZ());
    }

    private static BlockBounds intersection(BlockBounds first, BlockBounds second) {
        return new BlockBounds(Math.max(first.minX(), second.minX()), Math.max(first.minZ(), second.minZ()),
                Math.min(first.maxX(), second.maxX()), Math.min(first.maxZ(), second.maxZ()));
    }

    private static JsonArray ensureArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            obj.add(key, new JsonArray());
        }
        return obj.getAsJsonArray(key);
    }

    private static boolean persistWorldgenLedger() {
        Path root = activeServerRoot;
        if (root == null) {
            return true;
        }
        Path temp = null;
        try {
            Path dir = activeDir(root);
            Files.createDirectories(dir);
            Path target = dir.resolve(WORLDGEN_LEDGER_FILE);
            temp = Files.createTempFile(dir, WORLDGEN_LEDGER_FILE + ".", ".tmp");
            Files.writeString(temp, CityJson.GSON.toJson(worldgenLedger));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ex) {
            LOGGER.error("Failed to persist City worldgen ledger; template writes remain pending.", ex);
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A unique temporary file can be cleaned up on the next server start.
                }
            }
        }
    }

    private static Path activeDir(Path serverRoot) {
        return serverRoot.resolve(ACTIVE_DIR);
    }

    private static JsonObject emptyWorldgenLedger() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", WORLDGEN_LEDGER_SCHEMA);
        obj.add("placedStructures", new JsonArray());
        obj.add("templateFragments", new JsonArray());
        obj.add("templatePendingFragments", new JsonArray());
        obj.add("templateDatums", new JsonArray());
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

    public record TemplateDatumPreparation(TemplateDatumPreparationStatus status,
                                           OptionalInt templateDatumY,
                                           String reasonCode,
                                           String message) {
        public TemplateDatumPreparation {
            templateDatumY = templateDatumY == null ? OptionalInt.empty() : templateDatumY;
            reasonCode = reasonCode == null ? "" : reasonCode;
            message = message == null ? "" : message;
        }

        public boolean ready() {
            return status == TemplateDatumPreparationStatus.READY && templateDatumY.isPresent();
        }

        private static TemplateDatumPreparation ready(int datum) {
            return new TemplateDatumPreparation(TemplateDatumPreparationStatus.READY, OptionalInt.of(datum),
                    "TEMPLATE_DATUM_READY", "Template datum is durably frozen.");
        }

        private static TemplateDatumPreparation waiting(String reasonCode, String message) {
            return new TemplateDatumPreparation(TemplateDatumPreparationStatus.WAITING, OptionalInt.empty(),
                    reasonCode, message);
        }

        private static TemplateDatumPreparation conflict(String reasonCode, String message) {
            return new TemplateDatumPreparation(TemplateDatumPreparationStatus.CONFLICT, OptionalInt.empty(),
                    reasonCode, message);
        }

        private static TemplateDatumPreparation invalid(String reasonCode, String message) {
            return new TemplateDatumPreparation(TemplateDatumPreparationStatus.INVALID, OptionalInt.empty(),
                    reasonCode, message);
        }

        private static TemplateDatumPreparation persistenceFailed() {
            return new TemplateDatumPreparation(TemplateDatumPreparationStatus.PERSISTENCE_FAILED,
                    OptionalInt.empty(), "TEMPLATE_LEDGER_PERSISTENCE_FAILED",
                    "Template pending/datum state could not be persisted; no world write is allowed.");
        }
    }

    public enum TemplateDatumPreparationStatus {
        READY,
        WAITING,
        CONFLICT,
        INVALID,
        PERSISTENCE_FAILED
    }

    public record TemplateFragmentRecordResult(boolean recorded,
                                               boolean templateCompleted,
                                               String reasonCode) {
        private static TemplateFragmentRecordResult fragmentRecorded() {
            return new TemplateFragmentRecordResult(true, false, "TEMPLATE_FRAGMENT_RECORDED");
        }

        private static TemplateFragmentRecordResult completedResult() {
            return new TemplateFragmentRecordResult(true, true, "TEMPLATE_MATERIALIZATION_RECORDED");
        }

        private static TemplateFragmentRecordResult rejectedResult(String reasonCode) {
            return new TemplateFragmentRecordResult(false, false, reasonCode);
        }
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
                                   JsonArray qualityTerms, JsonObject sourcePlan) {
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
                    copyArray(anchor.getAsJsonArray("qualityTerms")),
                    anchor.deepCopy());
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
                    copyArray(obj.getAsJsonArray("qualityTerms")),
                    obj.deepCopy());
        }

        public boolean isTemplatePlacement() {
            return sourcePlan != null
                    && (sourcePlan.has("templateRef")
                    || sourcePlan.has("templateHash")
                    || sourcePlan.has("structureTemplate")
                    || "structure_template_nbt".equals(stringValue(sourcePlan,
                    "materializationSource", "")));
        }

        public boolean coversChunk(ChunkPos chunkPos) {
            return chunkPos != null && lockedActualFootprint.overlaps(chunkBounds(chunkPos));
        }

        public JsonObject templatePlan() {
            return sourcePlan == null ? new JsonObject() : sourcePlan.deepCopy();
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
            if (isTemplatePlacement()) {
                for (String key : List.of("templateId", "templateRef", "templateHash", "variantId", "mirror",
                        "terrainPosePolicy",
                        "materializationSource", "templateDatumPolicy", "templateSize", "lockedActualFootprint",
                        "transformed", "transformedRoadEntrances", "structureTemplate")) {
                    if (sourcePlan.has(key)) {
                        obj.add(key, sourcePlan.get(key).deepCopy());
                    }
                }
            }
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
