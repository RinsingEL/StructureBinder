package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class CityFunctionZoneBuilder {
    public Result build(CityLandformReviewPackage reviewPackage, PatchGroupPlan plan) {
        if (reviewPackage == null) {
            throw new IllegalArgumentException("reviewPackage is required");
        }
        if (plan == null) {
            throw new IllegalArgumentException("patchGroupPlan is required");
        }

        List<String> hardBlocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> needsReview = new ArrayList<>();

        if (!PatchGroupPlan.CURRENT_SCHEMA_VERSION.equals(plan.schemaVersion())) {
            warnings.add("PatchGroupPlan schemaVersion is not current: " + plan.schemaVersion());
        }
        if (!reviewPackage.cityId().equals(plan.cityId())) {
            hardBlocks.add("PatchGroupPlan cityId does not match D3 package: " + plan.cityId());
        }

        Map<String, LandformPatchSummary> byLabel = reviewPackage.landformPatches().stream()
                .collect(Collectors.toMap(LandformPatchSummary::mapLabel, p -> p, (a, b) -> a, LinkedHashMap::new));
        Map<String, LandformPatchSummary> byId = reviewPackage.landformPatches().stream()
                .collect(Collectors.toMap(LandformPatchSummary::landformPatchId, p -> p, (a, b) -> a, LinkedHashMap::new));

        List<FunctionZonePatch> zones = new ArrayList<>();
        List<FunctionZoneTerrainStats> stats = new ArrayList<>();
        List<FunctionZoneMap.CellAssignment> assignments = new ArrayList<>();

        int splitRequests = 0;
        Set<String> zoneIds = new HashSet<>();
        int groupIndex = 1;
        for (PatchGroupPlan.Group group : plan.groups()) {
            CityFunctionType functionType = CityFunctionType.fromContractName(group.functionType());
            if (functionType == null) {
                hardBlocks.add("Unknown functionType for group " + group.groupId() + ": " + group.functionType());
                continue;
            }
            if (group.splitRequested()) {
                splitRequests++;
                warnings.add("splitRequested is recorded but not applied in D4 v0.1: " + group.groupId());
            }

            List<LandformPatchSummary> resolved = resolvePatches(group, byLabel, byId, hardBlocks);
            if (resolved.isEmpty()) {
                continue;
            }
            validateAdjacency(group, resolved, needsReview);

            String zonePatchId = stableZoneId(functionType, groupIndex++, zoneIds);
            BlockBounds zoneBounds = unionBounds(resolved);
            List<PatchMemberCell> zoneCells = unionMemberCells(resolved);
            int areaBlocks = resolved.stream().mapToInt(LandformPatchSummary::areaBlocks).sum();
            String statsRef = zonePatchId + "_terrain_stats";
            FunctionZonePatch zone = new FunctionZonePatch(
                    zonePatchId,
                    group.groupId(),
                    group.zoneName(),
                    functionType,
                    resolved.stream().map(LandformPatchSummary::landformPatchId).distinct().toList(),
                    zoneBounds,
                    zoneCells,
                    areaBlocks,
                    group.mainBuildingRole(),
                    statsRef,
                    group.groupReason(),
                    zoneCells.isEmpty()
                            ? "D4 v0.1 fell back to patch envelopes because memberCells were unavailable."
                            : "D4 v0.1 merged GIS patch member cells selected from the multimodal review map.");
            zones.add(zone);
            stats.add(buildStats(zonePatchId, resolved));
            for (LandformPatchSummary patch : resolved) {
                assignments.add(new FunctionZoneMap.CellAssignment(
                        zonePatchId, patch.landformPatchId(), patch.geometryMode(),
                        patch.blockBounds(), patch.memberCells()));
            }
        }

        if (splitRequests > 1) {
            warnings.add("More than one splitRequested group in D4 v0.1: " + splitRequests);
        }
        boolean duplicateRefs = assignments.stream()
                .collect(Collectors.groupingBy(FunctionZoneMap.CellAssignment::landformPatchId, Collectors.counting()))
                .values().stream().anyMatch(count -> count > 1);
        if (duplicateRefs) {
            warnings.add("One or more GIS patches are assigned to multiple function zones.");
        }

        JsonObject metrics = new JsonObject();
        metrics.addProperty("groupCount", plan.groups().size());
        metrics.addProperty("zoneCount", zones.size());
        metrics.addProperty("sourcePatchCount", reviewPackage.landformPatches().size());
        metrics.addProperty("assignedPatchRefs", assignments.size());
        metrics.addProperty("splitRequests", splitRequests);
        metrics.addProperty("geometryMode", reviewPackage.landformPatches().stream()
                .anyMatch(p -> !p.memberCells().isEmpty()) ? "patch_member_cells" : "patch_envelope");

        boolean passed = hardBlocks.isEmpty();
        int score = Math.max(0, 100 - hardBlocks.size() * 40 - warnings.size() * 5 - needsReview.size() * 3);
        CityQualityReport quality = new CityQualityReport(passed, score, hardBlocks, warnings, needsReview, metrics);
        FunctionZoneMap map = new FunctionZoneMap(
                FunctionZoneMap.CURRENT_SCHEMA_VERSION,
                reviewPackage.cityId(),
                reviewPackage.grid(),
                passed ? zones : List.of(),
                passed ? assignments : List.of(),
                quality);
        return new Result(plan, map, passed ? zones : List.of(), passed ? stats : List.of(), quality);
    }

    private List<LandformPatchSummary> resolvePatches(PatchGroupPlan.Group group,
                                                       Map<String, LandformPatchSummary> byLabel,
                                                       Map<String, LandformPatchSummary> byId,
                                                       List<String> hardBlocks) {
        LinkedHashSet<LandformPatchSummary> resolved = new LinkedHashSet<>();
        for (String label : group.patchLabels()) {
            LandformPatchSummary patch = byLabel.get(label);
            if (patch == null) {
                hardBlocks.add("Group " + group.groupId() + " references unknown patch label: " + label);
            } else {
                resolved.add(patch);
            }
        }
        for (String ref : group.landformPatchRefs()) {
            LandformPatchSummary patch = byId.get(ref);
            if (patch == null) {
                hardBlocks.add("Group " + group.groupId() + " references unknown landformPatchId: " + ref);
            } else {
                resolved.add(patch);
            }
        }
        if (!group.patchLabels().isEmpty() && !group.landformPatchRefs().isEmpty()) {
            Set<String> labelIds = group.patchLabels().stream()
                    .map(byLabel::get)
                    .filter(p -> p != null)
                    .map(LandformPatchSummary::landformPatchId)
                    .collect(Collectors.toSet());
            Set<String> explicitIds = group.landformPatchRefs().stream()
                    .filter(byId::containsKey)
                    .collect(Collectors.toSet());
            if (!labelIds.equals(explicitIds)) {
                hardBlocks.add("Group " + group.groupId() + " patchLabels and landformPatchRefs do not match.");
            }
        }
        return new ArrayList<>(resolved);
    }

    private void validateAdjacency(PatchGroupPlan.Group group,
                                   List<LandformPatchSummary> resolved,
                                   List<String> needsReview) {
        if (resolved.size() <= 1) {
            return;
        }
        Set<String> ids = resolved.stream().map(LandformPatchSummary::landformPatchId).collect(Collectors.toSet());
        boolean anyNeighbor = resolved.stream()
                .flatMap(p -> p.neighborLandformPatchIds().stream())
                .anyMatch(ids::contains);
        if (!anyNeighbor && group.groupReason().isBlank()) {
            needsReview.add("Group " + group.groupId()
                    + " combines non-adjacent patches without groupReason.");
        }
    }

    private FunctionZoneTerrainStats buildStats(String zonePatchId, List<LandformPatchSummary> patches) {
        int area = patches.stream().mapToInt(LandformPatchSummary::areaBlocks).sum();
        double heightMin = patches.stream().mapToDouble(p -> p.metricsSummary().minElevation()).min().orElse(0);
        double heightMax = patches.stream().mapToDouble(p -> p.metricsSummary().maxElevation()).max().orElse(0);
        double heightMean = weightedMean(patches, MetricKind.MEAN_ELEVATION);
        double slopeMean = weightedMean(patches, MetricKind.MEAN_SLOPE);
        double slopeMax = patches.stream().mapToDouble(p -> p.metricsSummary().meanSlope()).max().orElse(0);
        int waterTouches = (int) patches.stream()
                .filter(p -> p.landformTags().contains("waterfront") || p.overlayTags().contains("near_water"))
                .count();
        double waterContactRatio = patches.isEmpty() ? 0 : waterTouches / (double) patches.size();
        int shoreline = patches.stream()
                .filter(p -> p.landformTags().contains("waterfront"))
                .mapToInt(p -> Math.max(0, p.blockBounds().widthBlocks() + p.blockBounds().heightBlocks()) / 2)
                .sum();
        List<String> landforms = patches.stream()
                .map(p -> p.landformType().contractName())
                .distinct()
                .toList();
        List<String> flags = patches.stream()
                .flatMap(p -> p.overlayTags().stream())
                .filter(tag -> tag.equals("edge_dirty") || tag.equals("fragment") || tag.equals("low_confidence"))
                .distinct()
                .toList();
        return new FunctionZoneTerrainStats(
                zonePatchId,
                area,
                shapeClass(unionBounds(patches)),
                heightMin,
                heightMax,
                heightMean,
                0,
                0,
                0,
                slopeMean,
                slopeMean,
                slopeMax,
                shoreline,
                waterContactRatio,
                landforms,
                flags,
                capacityRange(area));
    }

    private enum MetricKind {
        MEAN_ELEVATION,
        MEAN_SLOPE
    }

    private double weightedMean(List<LandformPatchSummary> patches, MetricKind kind) {
        double totalWeight = patches.stream().mapToDouble(p -> Math.max(1, p.areaBlocks())).sum();
        if (totalWeight <= 0) {
            return 0;
        }
        double total = 0;
        for (LandformPatchSummary patch : patches) {
            double value = switch (kind) {
                case MEAN_ELEVATION -> patch.metricsSummary().meanElevation();
                case MEAN_SLOPE -> patch.metricsSummary().meanSlope();
            };
            total += value * Math.max(1, patch.areaBlocks());
        }
        return total / totalWeight;
    }

    private String capacityRange(int areaBlocks) {
        int low = Math.max(1, areaBlocks / 12000);
        int high = Math.max(low, areaBlocks / 5000);
        return low + "-" + high;
    }

    private String shapeClass(BlockBounds bounds) {
        int w = Math.max(1, bounds.widthBlocks());
        int h = Math.max(1, bounds.heightBlocks());
        double ratio = Math.max(w, h) / (double) Math.min(w, h);
        if (ratio > 3.0) {
            return "elongated_envelope";
        }
        return "compact_envelope";
    }

    private BlockBounds unionBounds(List<LandformPatchSummary> patches) {
        int minX = patches.stream().mapToInt(p -> p.blockBounds().minX()).min().orElse(0);
        int minZ = patches.stream().mapToInt(p -> p.blockBounds().minZ()).min().orElse(0);
        int maxX = patches.stream().mapToInt(p -> p.blockBounds().maxX()).max().orElse(0);
        int maxZ = patches.stream().mapToInt(p -> p.blockBounds().maxZ()).max().orElse(0);
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private List<PatchMemberCell> unionMemberCells(List<LandformPatchSummary> patches) {
        Map<String, PatchMemberCell> cells = new LinkedHashMap<>();
        for (LandformPatchSummary patch : patches) {
            for (PatchMemberCell cell : patch.memberCells()) {
                cells.put(cell.cellX() + "," + cell.cellZ(), cell);
            }
        }
        return new ArrayList<>(cells.values());
    }

    private String stableZoneId(CityFunctionType type, int index, Set<String> used) {
        String base = type.contractName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        String candidate = base + "_" + String.format("%02d", index);
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + String.format("%02d", index) + "_" + suffix++;
        }
        return candidate;
    }

    public record Result(
            PatchGroupPlan patchGroupPlan,
            FunctionZoneMap functionZoneMap,
            List<FunctionZonePatch> functionZonePatches,
            List<FunctionZoneTerrainStats> functionZoneTerrainStats,
            CityQualityReport qualityReport) {

        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.passed());
            obj.add("qualityReport", qualityReport.asJson());
            obj.add("patchGroupPlan", patchGroupPlan.asJson());
            obj.add("functionZoneMap", functionZoneMap.asJson());
            obj.add("functionZonePatches", array(functionZonePatches.stream()
                    .map(FunctionZonePatch::asJson).toList()));
            obj.add("functionZoneTerrainStats", array(functionZoneTerrainStats.stream()
                    .map(FunctionZoneTerrainStats::asJson).toList()));
            return obj;
        }

        private static com.google.gson.JsonArray array(List<JsonObject> objects) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            objects.forEach(array::add);
            return array;
        }
    }
}
