package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;
import com.rinsing.geomantia.systems.city.domain.model.PlanningGrid;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

public final class CityStructureArrayCandidatePlanner {
    public static final String PLAN_SCHEMA = "city_d4_array_candidate_plan.v0.1";
    public static final String CANDIDATE_SET_SCHEMA = "city_d4_array_candidate_set.v0.1";

    private static final int MAX_GROUP_CANDIDATES = 5;
    private static final List<String> DEFAULT_PATTERNS =
            List.of("loose_cluster", "patch_axis_band", "scattered");
    private static final List<String> SUPPORTED_PATTERNS =
            List.of("loose_cluster", "patch_axis_band", "scattered", "compound_cluster",
                    "grid", "courtyard", "l_shape", "u_shape", "organic_compact");

    public Result plan(Path baseDirectory,
                       CityLandformReviewPackage reviewPackage,
                       JsonObject terraSenseProfileSource,
                       JsonObject arrayCandidatePlan,
                       CityStructureEnvelopeFacts envelopeFacts,
                       JsonObject occupiedStructureAnchorMap,
                       JsonArray occupiedEnvelopes) throws IOException {
        long started = System.nanoTime();
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4 array candidates.");
        }
        rejectLegacyPayload(arrayCandidatePlan);
        if (arrayCandidatePlan == null) {
            throw new IllegalArgumentException("arrayCandidatePlan object is required.");
        }
        String cityId = stringValue(arrayCandidatePlan, "cityId", reviewPackage.cityId());
        if (!reviewPackage.cityId().equals(cityId)) {
            throw new IllegalArgumentException("ArrayCandidatePlan cityId mismatch.");
        }
        String arrayId = requiredString(arrayCandidatePlan, "arrayId");
        String displayRole = stringValue(arrayCandidatePlan, "displayRole", arrayId);
        int arrayCount = intValue(arrayCandidatePlan, "arrayCount", 0);
        if (arrayCount <= 0) {
            throw new IllegalArgumentException("arrayCandidatePlan.arrayCount must be positive.");
        }

        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
        Map<String, CityStructureProfileCatalog.StructureProfile> profiles = catalog.byId();
        CityStructureEnvelopeFacts facts = envelopeFacts == null ? CityStructureEnvelopeFacts.empty() : envelopeFacts;
        Map<String, LandformPatchSummary> patches = patchesByRef(reviewPackage);
        List<LandformPatchSummary> sourcePatches = sourcePatches(arrayCandidatePlan, patches);
        List<String> structureIds = structureIds(arrayCandidatePlan);
        List<String> patterns = patterns(arrayCandidatePlan);
        List<String> hardBlocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>(catalog.warnings());
        List<String> needsReview = new ArrayList<>(catalog.needsReview());
        JsonArray groupCandidates = new JsonArray();
        JsonArray generationReports = new JsonArray();
        List<BlockBounds> occupied = occupiedBounds(occupiedStructureAnchorMap, occupiedEnvelopes);

        if (sourcePatches.isEmpty()) {
            hardBlocks.add(arrayId + ": candidatePatchRefs must reference D3 landform patches or map labels.");
        }
        if (structureIds.isEmpty()) {
            hardBlocks.add(arrayId + ": structureIds must not be empty.");
        }
        for (String structureId : structureIds) {
            if (!profiles.containsKey(structureId)) {
                hardBlocks.add(arrayId + ": structureId is not in approved TerraSense catalog: " + structureId);
            }
        }

        if (hardBlocks.isEmpty()) {
            int candidateIndex = 0;
            for (String pattern : patterns) {
                for (LandformPatchSummary pivot : sourcePatches) {
                    if (candidateIndex >= MAX_GROUP_CANDIDATES) {
                        break;
                    }
                    GroupBuildResult built = buildGroup(candidateIndex + 1, pattern, pivot, arrayCandidatePlan,
                            reviewPackage.grid(), sourcePatches, profiles, facts, occupied);
                    generationReports.add(built.report());
                    if (built.candidate() != null) {
                        candidateIndex++;
                        groupCandidates.add(built.candidate());
                    } else {
                        warnings.add(arrayId + ": pattern " + pattern + " could not place all "
                                + arrayCount + " items near " + pivot.landformPatchId() + ".");
                    }
                }
                if (candidateIndex >= MAX_GROUP_CANDIDATES) {
                    break;
                }
            }
            if (groupCandidates.isEmpty()) {
                hardBlocks.add("D4_ARRAY_COUNT_UNSATISFIED: " + arrayId
                        + " could not place " + arrayCount
                        + " non-overlapping structures in candidatePatchRefs.");
            }
        }

        JsonObject candidateSet = new JsonObject();
        candidateSet.addProperty("schemaVersion", CANDIDATE_SET_SCHEMA);
        candidateSet.addProperty("cityId", reviewPackage.cityId());
        candidateSet.addProperty("arrayId", arrayId);
        candidateSet.addProperty("displayRole", displayRole);
        candidateSet.addProperty("planningMode", "array_group_candidates");
        candidateSet.addProperty("generatedAt", Instant.now().toString());
        candidateSet.add("grid", reviewPackage.grid().asJson());
        candidateSet.add("sourceArrayCandidatePlan", arrayCandidatePlan.deepCopy());
        candidateSet.add("sourceTerraSenseProfileSource", terraSenseProfileSource.deepCopy());
        candidateSet.add("structureProfileCatalog", catalog.asJson());
        candidateSet.add("occupiedEnvelopes", occupiedEnvelopeJson(occupied));
        candidateSet.add("arrayCandidates", groupCandidates);
        candidateSet.add("generationReports", generationReports);
        JsonObject quality = quality(hardBlocks, warnings, needsReview, groupCandidates);
        candidateSet.add("quality", quality);
        candidateSet.add("timingMs", timing(started));
        return new Result(arrayCandidatePlan.deepCopy(), candidateSet, quality);
    }

    private GroupBuildResult buildGroup(int candidateIndex,
                                        String pattern,
                                        LandformPatchSummary pivot,
                                        JsonObject plan,
                                        PlanningGrid grid,
                                        List<LandformPatchSummary> sourcePatches,
                                        Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                        CityStructureEnvelopeFacts facts,
                                        List<BlockBounds> occupied) {
        String arrayId = requiredString(plan, "arrayId");
        String displayRole = stringValue(plan, "displayRole", arrayId);
        int arrayCount = intValue(plan, "arrayCount", 0);
        List<String> structureIds = structureIds(plan);
        int spacing = configuredSpacing(plan, structureIds, profiles, facts);
        List<BlockPoint> rawPoints = rawPoints(pattern, pivot, sourcePatches, grid, plan,
                structureIds, profiles, facts, arrayCount);
        List<BlockBounds> groupCollision = new ArrayList<>();
        JsonArray items = new JsonArray();
        JsonArray anchors = new JsonArray();
        JsonArray rejected = new JsonArray();
        BlockBounds groupCollisionUnion = null;
        BlockBounds groupMaskUnion = null;
        int pointCursor = 0;

        for (int itemIndex = 0; itemIndex < arrayCount; itemIndex++) {
            String structureId = structureIdForItem(plan, pattern, candidateIndex, itemIndex + 1, structureIds);
            CityStructureProfileCatalog.StructureProfile profile = profiles.get(structureId);
            JsonObject itemOptions = plan.deepCopy();
            itemOptions.addProperty("rotation", "NONE");
            itemOptions.addProperty("compactArraySubmission", true);
            JsonObject accepted = null;
            BlockPoint acceptedPoint = null;
            CityStructureCandidateEnvelope.Estimate acceptedEstimate = null;
            LandformPatchSummary acceptedPatch = null;

            while (pointCursor < rawPoints.size()) {
                BlockPoint point = rawPoints.get(pointCursor++);
                LandformPatchSummary pointPatch = sourcePatches.stream()
                        .filter(patch -> patchContains(patch, grid, point))
                        .findFirst()
                        .orElse(null);
                if (pointPatch == null || !grid.containsBlock(point.x(), point.z())) {
                    rejected.add(rejection(itemIndex + 1, structureId, point, "POINT_OUTSIDE_PATCH"));
                    continue;
                }
                CityStructureCandidateEnvelope.Estimate estimate =
                        CityStructureCandidateEnvelope.estimate(point, profile, facts, itemOptions);
                if (estimate.requiredFactsMissing()) {
                    return GroupBuildResult.failed(pattern, pivot,
                            "D4_ARRAY_STRUCTURE_ENVELOPE_FACTS_REQUIRED", rejected);
                }
                if (!estimate.hardBlockReason().isBlank()) {
                    rejected.add(rejection(itemIndex + 1, structureId, point, estimate.hardBlockReason()));
                    continue;
                }
                if (!gridContains(grid, estimate.collisionEnvelope())) {
                    rejected.add(rejection(itemIndex + 1, structureId, point, "COLLISION_ENVELOPE_OUTSIDE_CITY_GRID"));
                    continue;
                }
                if (overlapsAny(occupied, estimate.collisionEnvelope())) {
                    rejected.add(rejection(itemIndex + 1, structureId, point, "OCCUPIED_ENVELOPE_OVERLAP"));
                    continue;
                }
                if (overlapsAny(groupCollision, estimate.collisionEnvelope())) {
                    rejected.add(rejection(itemIndex + 1, structureId, point, "GROUP_COLLISION_OVERLAP"));
                    continue;
                }
                acceptedPoint = point;
                acceptedEstimate = estimate;
                acceptedPatch = pointPatch;
                accepted = itemJson(plan, pattern, spacing, candidateIndex, itemIndex + 1, structureId,
                        point, pointPatch, estimate);
                break;
            }

            if (accepted == null) {
                JsonObject report = report(pattern, pivot, items.size(), arrayCount, "D4_ARRAY_COUNT_UNSATISFIED");
                report.add("rejectedPoints", rejected);
                return new GroupBuildResult(null, report);
            }
            items.add(accepted);
            groupCollision.add(acceptedEstimate.collisionEnvelope());
            groupCollisionUnion = groupCollisionUnion == null ? acceptedEstimate.collisionEnvelope()
                    : CityStructureCandidateEnvelope.union(groupCollisionUnion, acceptedEstimate.collisionEnvelope());
            groupMaskUnion = groupMaskUnion == null ? acceptedEstimate.maskEnvelope()
                    : CityStructureCandidateEnvelope.union(groupMaskUnion, acceptedEstimate.maskEnvelope());
            anchors.add(anchorJson(plan, pattern, itemIndex + 1, structureId, acceptedPoint,
                    acceptedPatch, acceptedEstimate));
        }

        JsonObject candidate = new JsonObject();
        candidate.addProperty("arrayCandidateId", arrayId + "_" + pattern + "_"
                + String.format(Locale.ROOT, "%02d", candidateIndex));
        candidate.addProperty("arrayPattern", pattern);
        candidate.addProperty("arrayShape", compoundShape(plan, pattern));
        candidate.addProperty("spacingBlocks", spacing);
        candidate.addProperty("arrayId", arrayId);
        candidate.addProperty("displayRole", displayRole);
        candidate.addProperty("placedItemCount", items.size());
        candidate.addProperty("requestedItemCount", arrayCount);
        candidate.addProperty("sourcePivotPatchId", pivot.landformPatchId());
        candidate.add("items", items);
        candidate.add("groupCollisionEnvelope", CityStructureCandidateEnvelope.boundsJson(groupCollisionUnion));
        candidate.add("groupMaskEnvelope", CityStructureCandidateEnvelope.boundsJson(groupMaskUnion));
        candidate.add("scoreBreakdown", score(pattern, pivot, groupCollisionUnion, items.size(), arrayCount));
        candidate.add("risks", risks(pivot, rejected));
        JsonObject expanded = new JsonObject();
        expanded.addProperty("schemaVersion", CityStructureAnchorPlanner.PLAN_SCHEMA);
        expanded.addProperty("cityId", stringValue(plan, "cityId", ""));
        expanded.add("anchors", anchors);
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", PLAN_SCHEMA);
        trace.addProperty("arrayId", arrayId);
        trace.addProperty("arrayPattern", pattern);
        trace.addProperty("arrayShape", compoundShape(plan, pattern));
        trace.addProperty("spacingBlocks", spacing);
        trace.addProperty("arrayCandidateId", candidate.get("arrayCandidateId").getAsString());
        trace.addProperty("itemCount", items.size());
        expanded.add("arrayCandidateTrace", trace);
        candidate.add("expandedStructureAnchorPlan", expanded);

        JsonObject report = report(pattern, pivot, items.size(), arrayCount, "accepted");
        report.addProperty("rejectedPointCount", rejected.size());
        return new GroupBuildResult(candidate, report);
    }

    private static JsonObject itemJson(JsonObject plan,
                                       String pattern,
                                       int spacing,
                                       int candidateIndex,
                                       int itemIndex,
                                       String structureId,
                                       BlockPoint point,
                                       LandformPatchSummary patch,
                                       CityStructureCandidateEnvelope.Estimate estimate) {
        JsonObject obj = new JsonObject();
        String arrayId = requiredString(plan, "arrayId");
        obj.addProperty("itemId", arrayId + "_" + String.format(Locale.ROOT, "%02d", itemIndex));
        obj.addProperty("itemIndex", itemIndex);
        obj.addProperty("structureId", structureId);
        obj.addProperty("arrayPattern", pattern);
        obj.addProperty("arrayShape", compoundShape(plan, pattern));
        obj.addProperty("spacingBlocks", spacing);
        obj.add("anchorBlock", point.asJson());
        obj.add("roadPoint", point.asJson());
        obj.addProperty("rotation", "NONE");
        obj.addProperty("variantSelectionMode", variantSelectionMode(plan));
        obj.add("sourcePatchRefs", patchRefs(patch));
        obj.add("plannedFootprint", CityStructureCandidateEnvelope.boundsJson(estimate.plannedFootprint()));
        obj.add("estimatedCollisionEnvelope",
                CityStructureCandidateEnvelope.boundsJson(estimate.collisionEnvelope()));
        obj.add("estimatedMaskEnvelope", CityStructureCandidateEnvelope.boundsJson(estimate.maskEnvelope()));
        obj.add("diagnosticMaxObservedEnvelope",
                CityStructureCandidateEnvelope.boundsJson(estimate.diagnosticMaxObservedEnvelope()));
        obj.addProperty("geometryStatus", "available");
        obj.addProperty("envelopeMode", estimate.envelopeMode());
        obj.addProperty("selectedEnvelopeGroupKey", estimate.selectedEnvelopeGroupKey());
        obj.addProperty("arrayCandidateOrdinal", candidateIndex);
        obj.addProperty("smallClearanceBlocks", CityStructureCandidateEnvelope.DEFAULT_SMALL_CLEARANCE_BLOCKS);
        obj.addProperty("roadAccessIntent", "array_connect_deferred_to_d7");
        return obj;
    }

    private static JsonObject anchorJson(JsonObject plan,
                                         String pattern,
                                         int itemIndex,
                                         String structureId,
                                         BlockPoint point,
                                         LandformPatchSummary patch,
                                         CityStructureCandidateEnvelope.Estimate estimate) {
        String arrayId = requiredString(plan, "arrayId");
        JsonObject anchor = new JsonObject();
        anchor.addProperty("anchorId", arrayId + "_" + String.format(Locale.ROOT, "%02d", itemIndex));
        anchor.addProperty("arrayId", arrayId);
        anchor.addProperty("arrayPattern", pattern);
        anchor.addProperty("structureId", structureId);
        anchor.add("sourcePatchIds", patchRefs(patch));
        anchor.add("anchorBlock", point.asJson());
        anchor.addProperty("rotation", "NONE");
        anchor.addProperty("variantSelectionMode", variantSelectionMode(plan));
        anchor.add("intentTerms", intentTerms(arrayId, stringValue(plan, "displayRole", arrayId), pattern));
        anchor.addProperty("priority", intValue(plan, "priority", 100) + itemIndex);
        anchor.addProperty("roadAccessIntent", "array_connect_deferred_to_d7");
        if (!estimate.selectedEnvelopeGroupKey().isBlank()) {
            anchor.addProperty("envelopeGroupKey", estimate.selectedEnvelopeGroupKey());
        }
        anchor.addProperty("smallClearanceBlocks", CityStructureCandidateEnvelope.DEFAULT_SMALL_CLEARANCE_BLOCKS);
        anchor.addProperty("selectionReason", "Selected from D4 array candidate " + arrayId + " pattern " + pattern);
        CityStructureAnchorPlanner.applyPlacementProvenance(anchor, anchor);
        return anchor;
    }

    private static List<BlockPoint> rawPoints(String pattern,
                                              LandformPatchSummary pivot,
                                              List<LandformPatchSummary> patches,
                                              PlanningGrid grid,
                                              JsonObject plan,
                                              List<String> structureIds,
                                              Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                              CityStructureEnvelopeFacts facts,
                                              int arrayCount) {
        int spacing = configuredSpacing(plan, structureIds, profiles, facts);
        LinkedHashSet<BlockPoint> points = new LinkedHashSet<>();
        switch (pattern) {
            case "patch_axis_band" -> axisBand(points, pivot, patches, spacing, arrayCount);
            case "scattered" -> scattered(points, patches, grid, spacing, arrayCount);
            case "compound_cluster", "grid", "courtyard", "l_shape", "u_shape", "organic_compact" ->
                    compoundCluster(points, plan, pattern, pivot, patches, spacing, arrayCount);
            default -> looseCluster(points, pivot, patches, spacing, arrayCount);
        }
        return memberCellCandidatePoints(patches, grid, unionBounds(patches), pivot.centerBlock(),
                new ArrayList<>(points));
    }

    private static void compoundCluster(LinkedHashSet<BlockPoint> points,
                                        JsonObject plan,
                                        String pattern,
                                        LandformPatchSummary pivot,
                                        List<LandformPatchSummary> patches,
                                        int spacing,
                                        int arrayCount) {
        String shape = compoundShape(plan, pattern);
        if (!"organic_compact".equals(shape)) {
            ShapeGrid grid = shapeGrid(plan, arrayCount);
            structuredGrid(points, pivot.centerBlock(), grid.rows(), grid.columns(), spacing, shape);
        }
        looseCluster(points, pivot, patches, spacing, arrayCount);
    }

    private static void structuredGrid(LinkedHashSet<BlockPoint> points,
                                       BlockPoint center,
                                       int rows,
                                       int columns,
                                       int spacing,
                                       String shape) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                if (!includesCell(shape, row, col, rows, columns)) {
                    continue;
                }
                int x = center.x() + (int) Math.round((col - (columns - 1) / 2.0) * spacing);
                int z = center.z() + (int) Math.round((row - (rows - 1) / 2.0) * spacing);
                points.add(new BlockPoint(x, z));
            }
        }
    }

    private static boolean includesCell(String shape, int row, int col, int rows, int columns) {
        if ("courtyard".equals(shape) && rows > 2 && columns > 2) {
            return row == 0 || row == rows - 1 || col == 0 || col == columns - 1;
        }
        if ("l_shape".equals(shape) && rows > 1 && columns > 1) {
            return row == rows - 1 || col == 0;
        }
        if ("u_shape".equals(shape) && rows > 1 && columns > 2) {
            return row == rows - 1 || col == 0 || col == columns - 1;
        }
        return true;
    }

    private static void looseCluster(LinkedHashSet<BlockPoint> points,
                                     LandformPatchSummary pivot,
                                     List<LandformPatchSummary> patches,
                                     int spacing,
                                     int arrayCount) {
        points.add(pivot.centerBlock());
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 1; i < arrayCount * 24; i++) {
            double radius = Math.max(spacing, spacing * 0.62 * Math.sqrt(i));
            double angle = i * golden;
            int x = pivot.centerBlock().x() + (int) Math.round(Math.cos(angle) * radius);
            int z = pivot.centerBlock().z() + (int) Math.round(Math.sin(angle) * radius);
            points.add(new BlockPoint(x, z));
        }
        for (LandformPatchSummary patch : patches) {
            points.add(patch.centerBlock());
        }
    }

    private static void axisBand(LinkedHashSet<BlockPoint> points,
                                 LandformPatchSummary pivot,
                                 List<LandformPatchSummary> patches,
                                 int spacing,
                                 int arrayCount) {
        List<LandformPatchSummary> ordered = new ArrayList<>();
        ordered.add(pivot);
        for (LandformPatchSummary patch : patches) {
            if (!patch.landformPatchId().equals(pivot.landformPatchId())) {
                ordered.add(patch);
            }
        }
        for (LandformPatchSummary patch : ordered) {
            BlockBounds b = patch.blockBounds();
            boolean xAxis = b.widthBlocks() >= b.heightBlocks();
                int lanes = Math.max(1, Math.min(3, (xAxis ? b.heightBlocks() : b.widthBlocks()) / spacing));
            int length = xAxis ? b.widthBlocks() : b.heightBlocks();
            int steps = Math.max(arrayCount * 3, Math.max(1, length / Math.max(1, spacing)));
            for (int lane = 0; lane < lanes; lane++) {
                int crossOffset = (lane - lanes / 2) * Math.max(8, spacing);
                for (int step = 0; step <= steps; step++) {
                    int along = Math.min(length - 1, spacing / 2 + step * Math.max(8, spacing));
                    int x = xAxis ? b.minX() + along : b.center().x() + crossOffset;
                    int z = xAxis ? b.center().z() + crossOffset : b.minZ() + along;
                    points.add(new BlockPoint(clamp(x, b.minX(), b.maxX()), clamp(z, b.minZ(), b.maxZ())));
                }
            }
        }
    }

    private static void scattered(LinkedHashSet<BlockPoint> points,
                                  List<LandformPatchSummary> patches,
                                  PlanningGrid grid,
                                  int spacing,
                                  int arrayCount) {
        for (LandformPatchSummary patch : patches) {
            if (!patch.memberCells().isEmpty()) {
                for (PatchMemberCell cell : patch.memberCells()) {
                    points.add(new BlockPoint(cell.blockMinX() + grid.cellStepBlocks() / 2,
                            cell.blockMinZ() + grid.cellStepBlocks() / 2));
                }
                continue;
            }
            BlockBounds b = patch.blockBounds();
            int step = Math.max(1, grid.cellStepBlocks());
            for (int z = b.minZ() + step / 2; z <= b.maxZ(); z += step) {
                for (int x = b.minX() + step / 2; x <= b.maxX(); x += step) {
                    points.add(new BlockPoint(x, z));
                    if (points.size() > arrayCount * 32) {
                        return;
                    }
                }
            }
        }
    }

    private static int spacing(List<String> structureIds,
                               Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                               CityStructureEnvelopeFacts facts,
                               JsonObject options) {
        int max = 16;
        for (String structureId : structureIds) {
            CityStructureProfileCatalog.StructureProfile profile = profiles.get(structureId);
            if (profile == null) {
                continue;
            }
            max = Math.max(max, CityStructureCandidateEnvelope.automaticSpacing(profile, facts, options));
        }
        return max;
    }

    private static int configuredSpacing(JsonObject plan,
                                         List<String> structureIds,
                                         Map<String, CityStructureProfileCatalog.StructureProfile> profiles,
                                         CityStructureEnvelopeFacts facts) {
        int configured = compoundInt(plan, "spacingBlocks", 0);
        return configured > 0 ? configured : spacing(structureIds, profiles, facts, plan);
    }

    private static ShapeGrid shapeGrid(JsonObject plan, int requestedCount) {
        int requested = Math.max(1, requestedCount);
        int rows = compoundInt(plan, "rows", 0);
        int columns = compoundInt(plan, "columns", 0);
        if (rows <= 0 && columns <= 0) {
            columns = Math.max(1, (int) Math.ceil(Math.sqrt(requested)));
            rows = Math.max(1, (int) Math.ceil(requested / (double) columns));
        } else if (rows <= 0) {
            rows = Math.max(1, (int) Math.ceil(requested / (double) Math.max(1, columns)));
        } else if (columns <= 0) {
            columns = Math.max(1, (int) Math.ceil(requested / (double) rows));
        }
        return new ShapeGrid(Math.max(1, rows), Math.max(1, columns));
    }

    private static String compoundShape(JsonObject plan, String pattern) {
        if ("grid".equals(pattern) || "courtyard".equals(pattern)
                || "l_shape".equals(pattern) || "u_shape".equals(pattern)
                || "organic_compact".equals(pattern)) {
            return pattern;
        }
        String shape = compoundString(plan, "shape",
                compoundString(plan, "clusterShape", "organic_compact"));
        return switch (shape) {
            case "grid", "courtyard", "l_shape", "u_shape", "organic_compact" -> shape;
            default -> "organic_compact";
        };
    }

    private static int compoundInt(JsonObject plan, String key, int defaultValue) {
        JsonObject compound = optionalObject(plan, "compoundCluster");
        return intValue(compound, key, intValue(plan, key, defaultValue));
    }

    private static String compoundString(JsonObject plan, String key, String defaultValue) {
        JsonObject compound = optionalObject(plan, "compoundCluster");
        return stringValue(compound, key, stringValue(plan, key, defaultValue));
    }

    private static List<BlockPoint> memberCellCandidatePoints(List<LandformPatchSummary> patches,
                                                              PlanningGrid grid,
                                                              BlockBounds bounds,
                                                              BlockPoint start,
                                                              List<BlockPoint> guidePoints) {
        List<BlockPoint> memberPoints = memberCellCenters(patches, grid, bounds);
        if (memberPoints.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<BlockPoint> ordered = new LinkedHashSet<>();
        for (BlockPoint guide : guidePoints) {
            BlockPoint nearest = nearestUnused(memberPoints, ordered, guide);
            if (nearest != null) {
                ordered.add(nearest);
            }
        }
        memberPoints.stream()
                .sorted(Comparator
                        .comparingLong((BlockPoint point) -> distanceSquared(point, start))
                        .thenComparingInt(BlockPoint::x)
                        .thenComparingInt(BlockPoint::z))
                .forEach(ordered::add);
        return new ArrayList<>(ordered);
    }

    private static List<BlockPoint> memberCellCenters(List<LandformPatchSummary> patches,
                                                      PlanningGrid grid,
                                                      BlockBounds bounds) {
        LinkedHashSet<BlockPoint> centers = new LinkedHashSet<>();
        int step = Math.max(1, grid.cellStepBlocks());
        for (LandformPatchSummary patch : patches) {
            if (!patch.blockBounds().overlaps(bounds)) {
                continue;
            }
            if (!patch.memberCells().isEmpty()) {
                for (PatchMemberCell cell : patch.memberCells()) {
                    BlockBounds cellBounds = new BlockBounds(cell.blockMinX(), cell.blockMinZ(),
                            cell.blockMinX() + step - 1, cell.blockMinZ() + step - 1);
                    if (!cellBounds.overlaps(bounds)) {
                        continue;
                    }
                    BlockPoint point = new BlockPoint(
                            clamp(cell.blockMinX() + step / 2, bounds.minX(), bounds.maxX()),
                            clamp(cell.blockMinZ() + step / 2, bounds.minZ(), bounds.maxZ()));
                    if (grid.containsBlock(point.x(), point.z()) && patchContains(patch, grid, point)) {
                        centers.add(point);
                    }
                }
                continue;
            }
            int minX = Math.max(patch.blockBounds().minX(), bounds.minX());
            int minZ = Math.max(patch.blockBounds().minZ(), bounds.minZ());
            int maxX = Math.min(patch.blockBounds().maxX(), bounds.maxX());
            int maxZ = Math.min(patch.blockBounds().maxZ(), bounds.maxZ());
            for (int z = minZ; z <= maxZ; z += step) {
                for (int x = minX; x <= maxX; x += step) {
                    BlockPoint point = new BlockPoint(clamp(x + step / 2, minX, maxX),
                            clamp(z + step / 2, minZ, maxZ));
                    if (grid.containsBlock(point.x(), point.z()) && patchContains(patch, grid, point)) {
                        centers.add(point);
                    }
                }
            }
        }
        return new ArrayList<>(centers);
    }

    private static BlockBounds unionBounds(List<LandformPatchSummary> patches) {
        BlockBounds bounds = patches.get(0).blockBounds();
        for (int i = 1; i < patches.size(); i++) {
            bounds = CityStructureCandidateEnvelope.union(bounds, patches.get(i).blockBounds());
        }
        return bounds;
    }

    private static BlockPoint nearestUnused(List<BlockPoint> points, Set<BlockPoint> used, BlockPoint guide) {
        BlockPoint best = null;
        long bestDistance = Long.MAX_VALUE;
        for (BlockPoint point : points) {
            if (used.contains(point)) {
                continue;
            }
            long distance = distanceSquared(point, guide);
            if (distance < bestDistance
                    || (distance == bestDistance && best != null
                    && (point.x() < best.x() || point.x() == best.x() && point.z() < best.z()))) {
                best = point;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static long distanceSquared(BlockPoint a, BlockPoint b) {
        long dx = (long) a.x() - b.x();
        long dz = (long) a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static String structureIdForItem(JsonObject plan,
                                             String pattern,
                                             int candidateIndex,
                                             int itemIndex,
                                             List<String> structureIds) {
        if (structureIds.isEmpty()) {
            return "";
        }
        String mode = variantSelectionMode(plan);
        if (!"seeded_random".equals(mode) && !"weighted_random".equals(mode) && !"random".equals(mode)) {
            return structureIds.get((itemIndex - 1) % structureIds.size());
        }
        double totalWeight = 0.0;
        List<Double> weights = new ArrayList<>();
        JsonObject configuredWeights = optionalObject(plan, "structureWeights");
        for (String structureId : structureIds) {
            double weight = doubleValue(configuredWeights, structureId, 1.0);
            weight = weight > 0.0 ? weight : 0.0;
            weights.add(weight);
            totalWeight += weight;
        }
        if (totalWeight <= 0.0) {
            return structureIds.get((itemIndex - 1) % structureIds.size());
        }
        String arrayId = requiredString(plan, "arrayId");
        long seed = stableSeed(stringValue(plan, "variantSeed", "")
                + ":" + arrayId + ":" + pattern + ":" + candidateIndex + ":" + itemIndex);
        double pick = new SplittableRandom(seed).nextDouble(totalWeight);
        double cursor = 0.0;
        for (int i = 0; i < structureIds.size(); i++) {
            cursor += weights.get(i);
            if (pick < cursor) {
                return structureIds.get(i);
            }
        }
        return structureIds.get(structureIds.size() - 1);
    }

    private static String variantSelectionMode(JsonObject plan) {
        String mode = stringValue(plan, "variantSelectionMode",
                stringValue(plan, "selectionMode", "round_robin"));
        if ("weighted_random".equals(mode) || "seeded_random".equals(mode) || "random".equals(mode)) {
            return mode;
        }
        return "round_robin";
    }

    private static long stableSeed(String value) {
        long hash = 1125899906842597L;
        for (int i = 0; i < value.length(); i++) {
            hash = 31 * hash + value.charAt(i);
        }
        return hash;
    }

    private static JsonObject score(String pattern,
                                    LandformPatchSummary pivot,
                                    BlockBounds groupBounds,
                                    int placed,
                                    int requested) {
        JsonObject score = new JsonObject();
        double terrainFit = clamp01(1.0 - (pivot.metricsSummary().meanSlope() / 18.0));
        double countFit = requested <= 0 ? 0.0 : placed / (double) requested;
        double compactness = groupBounds == null ? 0.0
                : clamp01(1.0 - ((groupBounds.widthBlocks() + groupBounds.heightBlocks()) / 1024.0));
        double patternFit = switch (pattern) {
            case "patch_axis_band" -> 0.78;
            case "scattered" -> 0.72;
            default -> 0.82;
        };
        score.addProperty("total", terrainFit * 0.30 + countFit * 0.35 + compactness * 0.20 + patternFit * 0.15);
        score.addProperty("terrainFit", terrainFit);
        score.addProperty("countFit", countFit);
        score.addProperty("compactness", compactness);
        score.addProperty("patternFit", patternFit);
        score.addProperty("collisionSafety", placed == requested ? 1.0 : 0.0);
        return score;
    }

    private static JsonArray risks(LandformPatchSummary pivot, JsonArray rejected) {
        JsonArray risks = new JsonArray();
        if (pivot.metricsSummary().meanSlope() > 6.0) {
            risks.add("slope_medium");
        }
        if (!rejected.isEmpty()) {
            risks.add("candidate_repair_used");
        }
        return risks;
    }

    private static JsonObject quality(List<String> hardBlocks, List<String> warnings,
                                      List<String> needsReview, JsonArray groupCandidates) {
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() ? 100 : 0);
        quality.add("hardBlocks", stringArray(hardBlocks));
        quality.add("warnings", stringArray(warnings));
        quality.add("needsReview", stringArray(needsReview));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("arrayCandidateCount", groupCandidates.size());
        int itemCount = 0;
        for (JsonElement elem : groupCandidates) {
            itemCount += optionalArray(elem.getAsJsonObject(), "items").size();
        }
        metrics.addProperty("candidateItemCount", itemCount);
        quality.add("metrics", metrics);
        return quality;
    }

    private static JsonObject report(String pattern, LandformPatchSummary pivot, int placed, int requested,
                                     String status) {
        JsonObject obj = new JsonObject();
        obj.addProperty("pattern", pattern);
        obj.addProperty("pivotPatchId", pivot.landformPatchId());
        obj.addProperty("placedItemCount", placed);
        obj.addProperty("requestedItemCount", requested);
        obj.addProperty("status", status);
        return obj;
    }

    private static JsonObject rejection(int itemIndex, String structureId, BlockPoint point, String reasonCode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("itemIndex", itemIndex);
        obj.addProperty("structureId", structureId);
        obj.add("anchorBlock", point.asJson());
        obj.addProperty("reasonCode", reasonCode);
        return obj;
    }

    private static List<BlockBounds> occupiedBounds(JsonObject anchorMap, JsonArray explicit) {
        List<BlockBounds> result = new ArrayList<>();
        for (JsonElement elem : optionalArray(anchorMap, "anchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject anchor = elem.getAsJsonObject();
            if (anchor.has("collisionEnvelope") && anchor.get("collisionEnvelope").isJsonObject()) {
                result.add(CityStructureCandidateEnvelope.bounds(anchor.getAsJsonObject("collisionEnvelope")));
            }
        }
        for (JsonElement elem : explicit == null ? new JsonArray() : explicit) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            if (obj.has("blockBounds") && obj.get("blockBounds").isJsonObject()) {
                result.add(CityStructureCandidateEnvelope.bounds(obj.getAsJsonObject("blockBounds")));
            } else if (obj.has("minX") && obj.has("minZ") && obj.has("maxX") && obj.has("maxZ")) {
                result.add(CityStructureCandidateEnvelope.bounds(obj));
            }
        }
        return result;
    }

    private static JsonArray occupiedEnvelopeJson(List<BlockBounds> occupied) {
        JsonArray array = new JsonArray();
        for (BlockBounds bounds : occupied) {
            JsonObject obj = new JsonObject();
            obj.add("blockBounds", CityStructureCandidateEnvelope.boundsJson(bounds));
            array.add(obj);
        }
        return array;
    }

    private static Map<String, LandformPatchSummary> patchesByRef(CityLandformReviewPackage reviewPackage) {
        Map<String, LandformPatchSummary> result = new HashMap<>();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            result.put(patch.landformPatchId(), patch);
            result.put(patch.mapLabel(), patch);
        }
        return result;
    }

    private static List<LandformPatchSummary> sourcePatches(JsonObject plan,
                                                            Map<String, LandformPatchSummary> patches) {
        List<LandformPatchSummary> result = new ArrayList<>();
        for (JsonElement elem : requiredArray(plan, "candidatePatchRefs")) {
            String ref = elem.getAsString();
            LandformPatchSummary patch = patches.get(ref);
            if (patch != null && result.stream().noneMatch(existing ->
                    existing.landformPatchId().equals(patch.landformPatchId()))) {
                result.add(patch);
            }
        }
        result.sort(Comparator.comparing(LandformPatchSummary::landformPatchId));
        return result;
    }

    private static boolean patchContains(LandformPatchSummary patch, PlanningGrid grid, BlockPoint point) {
        if (!patch.memberCells().isEmpty()) {
            int cellX = grid.blockToCellX(point.x());
            int cellZ = grid.blockToCellZ(point.z());
            for (PatchMemberCell cell : patch.memberCells()) {
                if (cell.cellX() == cellX && cell.cellZ() == cellZ) {
                    return true;
                }
            }
            return false;
        }
        return patch.blockBounds().contains(point.x(), point.z());
    }

    private static boolean gridContains(PlanningGrid grid, BlockBounds bounds) {
        return grid.containsBlock(bounds.minX(), bounds.minZ())
                && grid.containsBlock(bounds.maxX(), bounds.maxZ());
    }

    private static boolean overlapsAny(List<BlockBounds> existing, BlockBounds candidate) {
        for (BlockBounds bounds : existing) {
            if (bounds.overlaps(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> structureIds(JsonObject plan) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : strings(requiredArray(plan, "structureIds"))) {
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> patterns(JsonObject plan) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        JsonArray array = optionalArray(plan, "patterns");
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                String value = elem.getAsString();
                if (SUPPORTED_PATTERNS.contains(value)) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            values.addAll(DEFAULT_PATTERNS);
        }
        return new ArrayList<>(values);
    }

    private static JsonArray patchRefs(LandformPatchSummary patch) {
        JsonArray refs = new JsonArray();
        refs.add(patch.landformPatchId());
        if (!patch.mapLabel().isBlank()) {
            refs.add(patch.mapLabel());
        }
        return refs;
    }

    private static JsonArray intentTerms(String arrayId, String displayRole, String pattern) {
        JsonArray terms = new JsonArray();
        terms.add("array." + arrayId);
        terms.add("array_pattern." + pattern);
        if (!displayRole.isBlank()) {
            terms.add(displayRole);
        }
        return terms;
    }

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", (System.nanoTime() - started) / 1_000_000L);
        return timing;
    }

    private static void rejectLegacyPayload(JsonObject obj) {
        if (obj == null) {
            return;
        }
        for (String field : List.of("patchGroupPlan", "groups", "zoneChoices", "functionType",
                "functionTag", "functionTags", "function_candidates", "targetVisibleAreaRatio")) {
            if (obj.has(field)) {
                throw CityStructureProfileCatalog.legacyFlow("D4 array candidate flow does not accept " + field + ".");
            }
        }
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray optionalArray(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject optionalObject(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject()
                ? obj.getAsJsonObject(key) : new JsonObject();
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

    private static double doubleValue(JsonObject obj, String key, double defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : defaultValue;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                values.add(elem.getAsString());
            }
        }
        return values;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Result(JsonObject arrayCandidatePlan, JsonObject arrayCandidateSet, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.add("arrayCandidatePlan", arrayCandidatePlan.deepCopy());
            obj.add("arrayCandidateSet", arrayCandidateSet.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            obj.add("timingMs", arrayCandidateSet.getAsJsonObject("timingMs").deepCopy());
            return obj;
        }
    }

    private record GroupBuildResult(JsonObject candidate, JsonObject report) {
        static GroupBuildResult failed(String pattern, LandformPatchSummary pivot, String reasonCode,
                                       JsonArray rejected) {
            JsonObject report = CityStructureArrayCandidatePlanner.report(pattern, pivot, 0, 0, reasonCode);
            report.add("rejectedPoints", rejected);
            return new GroupBuildResult(null, report);
        }
    }

    private record ShapeGrid(int rows, int columns) {
    }
}
