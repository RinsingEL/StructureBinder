package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.config.CityPlanningConfig;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.domain.landform.LandformPatch;

import java.util.*;
import java.util.stream.Collectors;

public final class CityLandformReviewBuilder {

    private final CityPlanningConfig config;

    public CityLandformReviewBuilder(CityPlanningConfig config) {
        this.config = config;
    }

    public CityPlanningConfig config() {
        return config;
    }

    public CityLandformReviewPackage build(CitySiteContext context, List<LandformPatch> patches) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(patches, "patches");

        List<LandformPatch> filtered = filterPatchesByBounds(context.bounds(), patches);
        List<LandformPatchSummary> summaries = buildSummaries(filtered);
        assignLabels(summaries);
        detectNeighborsForAll(filtered, summaries);
        buildFactsForAll(filtered, summaries);

        TargetScale targetScale = new TargetScale(
                context.scaleClass(),
                context.planningRadiusBlocks(),
                context.grid().cellStepBlocks());

        List<String> planningContext = buildPlanningContext(context, summaries);
        List<CityLandformReviewPackage.LegendEntry> legend = buildLegend(summaries);
        String aiPrompt = buildAiPromptContext(context, summaries);

        return new CityLandformReviewPackage(
                CityLandformReviewPackage.CURRENT_SCHEMA_VERSION,
                context.cityId(),
                context.grid(),
                targetScale,
                "",
                legend,
                summaries,
                planningContext,
                aiPrompt,
                List.of());
    }

    public List<LandformPatch> filterPatchesByBounds(BlockBounds bounds, List<LandformPatch> allPatches) {
        return allPatches.stream()
                .filter(p -> bounds.overlaps(new BlockBounds(
                        p.blockMinX(), p.blockMinZ(), p.blockMaxX(), p.blockMaxZ())))
                .collect(Collectors.toList());
    }

    public List<LandformPatchSummary> buildSummaries(List<LandformPatch> patches) {
        List<LandformPatchSummary> summaries = new ArrayList<>();
        for (LandformPatch p : patches) {
            AreaClass areaClass = classifyArea(p.cellCount());
            String displayName = config.landformDisplayNames()
                    .getOrDefault(p.landformType(), p.landformType().contractName());
            List<String> tags = buildTags(p);
            List<String> overlays = buildOverlays(p);
            LandformPatchSummary summary = LandformPatchSummary.fromGisPatch(
                    p, "", displayName, areaClass, List.of());
            summary = summary.withTags(tags, overlays);
            summaries.add(summary);
        }
        return summaries;
    }

    public void assignLabels(List<LandformPatchSummary> summaries) {
        // Contract order keeps map labels stable across locales and display-name changes.
        Map<LandformType, List<LandformPatchSummary>> grouped = new LinkedHashMap<>();
        Map<LandformType, String> displayNames = config.landformDisplayNames();
        List<LandformType> sortedTypes = summaries.stream()
                .map(LandformPatchSummary::landformType)
                .distinct()
                .sorted(Comparator.comparingInt(this::landformOrderIndex))
                .collect(Collectors.toList());

        for (LandformType lt : sortedTypes) {
            List<LandformPatchSummary> group = summaries.stream()
                    .filter(s -> s.landformType() == lt)
                    .sorted(Comparator.comparingInt(LandformPatchSummary::cellCount).reversed())
                    .collect(Collectors.toList());
            grouped.put(lt, group);
        }

        int globalIndex = 1;
        for (var entry : grouped.entrySet()) {
            String baseLabel = displayNames.getOrDefault(entry.getKey(), entry.getKey().contractName());
            for (LandformPatchSummary summary : entry.getValue()) {
                String mapLabel = baseLabel + String.format("%02d", globalIndex++);
                // Replace the summary with one that has the assigned label
                int idx = summaries.indexOf(summary);
                summaries.set(idx, new LandformPatchSummary(
                        summary.landformPatchId(), mapLabel, summary.displayLandformName(),
                        summary.centerBlock(), summary.areaBlocks(), summary.cellCount(),
                        summary.landformType(), summary.landformTags(), summary.overlayTags(),
                        summary.areaClass(), summary.metricsSummary(),
                        summary.summaryFacts(), summary.neighborLandformPatchIds()));
            }
        }
    }

    public AreaClass classifyArea(int cellCount) {
        CityPlanningConfig.AreaThresholds t = config.areaThresholds();
        if (cellCount <= t.tinyMax()) return AreaClass.TINY;
        if (cellCount <= t.smallMax()) return AreaClass.SMALL;
        if (cellCount <= t.mediumMax()) return AreaClass.MEDIUM;
        return AreaClass.LARGE;
    }

    public void detectNeighborsForAll(List<LandformPatch> patches, List<LandformPatchSummary> summaries) {
        for (int i = 0; i < patches.size(); i++) {
            LandformPatch pi = patches.get(i);
            List<String> neighbors = new ArrayList<>();
            for (int j = 0; j < patches.size(); j++) {
                if (i == j) continue;
                LandformPatch pj = patches.get(j);
                if (areAdjacent(pi, pj)) {
                    neighbors.add(pj.patchId());
                }
            }
            LandformPatchSummary updated = summaries.get(i).withNeighbors(neighbors);
            summaries.set(i, updated);
        }
    }

    public boolean areAdjacent(LandformPatch a, LandformPatch b) {
        boolean xOverlap = a.blockMinX() <= b.blockMaxX() && a.blockMaxX() >= b.blockMinX();
        boolean zOverlap = a.blockMinZ() <= b.blockMaxZ() && a.blockMaxZ() >= b.blockMinZ();
        boolean xTouch = a.blockMaxX() + 1 == b.blockMinX() || b.blockMaxX() + 1 == a.blockMinX();
        boolean zTouch = a.blockMaxZ() + 1 == b.blockMinZ() || b.blockMaxZ() + 1 == a.blockMinZ();
        return (xOverlap && zTouch) || (zOverlap && xTouch);
    }

    public void buildFactsForAll(List<LandformPatch> patches, List<LandformPatchSummary> summaries) {
        for (int i = 0; i < patches.size(); i++) {
            List<String> facts = buildFacts(patches.get(i), summaries.get(i).areaClass());
            LandformPatchSummary orig = summaries.get(i);
            summaries.set(i, new LandformPatchSummary(
                    orig.landformPatchId(), orig.mapLabel(), orig.displayLandformName(),
                    orig.centerBlock(), orig.areaBlocks(), orig.cellCount(),
                    orig.landformType(), orig.landformTags(), orig.overlayTags(),
                    orig.areaClass(), orig.metricsSummary(),
                    facts, orig.neighborLandformPatchIds()));
        }
    }

    public List<String> buildFacts(LandformPatch patch, AreaClass areaClass) {
        List<String> facts = new ArrayList<>();
        String displayName = config.landformDisplayNames()
                .getOrDefault(patch.landformType(), patch.landformType().contractName());

        facts.add(String.format("该区域为%s地貌，面积级别%s，平均海拔%.0f米",
                displayName, areaClass.contractName(), patch.meanElevation()));

        if (patch.touchesWater()) {
            facts.add(String.format("毗邻水域，平均距水距离%.0f格", patch.waterDistanceMean()));
        }

        if (areaClass == AreaClass.LARGE) {
            facts.add(String.format("是该选址内最大的%s区域", displayName));
        }

        if (patch.meanSlope() < 2.0) {
            facts.add("平均坡度较低，地势平缓");
        } else if (patch.meanSlope() > 10.0) {
            facts.add("平均坡度较高，后续落地阶段需复核地形处理");
        }

        if (patch.confidence() < 0.5) {
            facts.add("地貌置信度较低，需人工审核");
        }

        if (patch.flags().stream().anyMatch(f -> f.name().equals("FRAGMENT"))) {
            facts.add("该区域为碎片地块，面积可能不完整");
        }

        return facts;
    }

    public List<String> buildPlanningContext(CitySiteContext context, List<LandformPatchSummary> summaries) {
        List<String> ctx = new ArrayList<>();
        ctx.add(String.format("选址规模：%s，职能：%s，规划半径%d格",
                context.scaleClass().contractName(), context.cityRole(),
                context.planningRadiusBlocks()));
        ctx.add(String.format("包含%d个地貌区块", summaries.size()));

        Map<LandformType, Long> typeCounts = summaries.stream()
                .collect(Collectors.groupingBy(LandformPatchSummary::landformType, Collectors.counting()));
        String dominantTypes = typeCounts.entrySet().stream()
                .sorted(Map.Entry.<LandformType, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> config.landformDisplayNames().getOrDefault(e.getKey(), e.getKey().contractName()))
                .collect(Collectors.joining("、"));
        ctx.add("主要地貌类型：" + dominantTypes);

        String buildablePatches = summaries.stream()
                .filter(s -> s.metricsSummary().meanSlope() < 5.0 && s.areaClass() != AreaClass.TINY)
                .map(LandformPatchSummary::mapLabel)
                .collect(Collectors.joining("、"));
        if (!buildablePatches.isEmpty()) {
            ctx.add("低坡中大型地貌区：" + buildablePatches);
        }

        if (context.territoryCheckResult() == TerritoryCheckResult.BORDER) {
            ctx.add("警告：城市规划范围部分越出所属国度边界");
        }

        return ctx;
    }

    public List<CityLandformReviewPackage.LegendEntry> buildLegend(List<LandformPatchSummary> summaries) {
        Map<String, String> colorMap = buildColorMap();
        return summaries.stream()
                .map(LandformPatchSummary::landformType)
                .distinct()
                .map(lt -> {
                    String displayName = config.landformDisplayNames().getOrDefault(lt, lt.contractName());
                    String color = colorMap.getOrDefault(lt.contractName(), "#9E9E9E");
                    return new CityLandformReviewPackage.LegendEntry(color, displayName, lt.contractName());
                })
                .collect(Collectors.toList());
    }

    private Map<String, String> buildColorMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("plain", "#4CAF50");
        map.put("shore", "#FFEB3B");
        map.put("water", "#2196F3");
        map.put("terrace", "#8BC34A");
        map.put("slope", "#FF9800");
        map.put("cliff", "#795548");
        map.put("ridge", "#9C27B0");
        map.put("valley", "#00BCD4");
        map.put("basin", "#607D8B");
        map.put("unknown", "#9E9E9E");
        return map;
    }

    private int landformOrderIndex(LandformType type) {
        LandformType[] order = {
                LandformType.WATER,
                LandformType.SHORE,
                LandformType.PLAIN,
                LandformType.TERRACE,
                LandformType.SLOPE,
                LandformType.CLIFF,
                LandformType.RIDGE,
                LandformType.VALLEY,
                LandformType.BASIN,
                LandformType.UNKNOWN
        };
        for (int i = 0; i < order.length; i++) {
            if (order[i] == type) {
                return i;
            }
        }
        return order.length;
    }

    public String buildAiPromptContext(CitySiteContext context, List<LandformPatchSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("该城市为%s规模的%s，位于%s。",
                context.scaleClass().contractName(), context.cityRole(), context.realmId()));
        sb.append(String.format("规划范围%d x %d格，网格步长%d格。",
                context.grid().widthBlocks(), context.grid().heightBlocks(),
                context.grid().cellStepBlocks()));
        sb.append(String.format("共包含%d个地貌区块。", summaries.size()));
        sb.append("请仅引用下面列出的地貌区块编号（mapLabel）进行功能区划分。");
        return sb.toString();
    }

    private List<String> buildTags(LandformPatch patch) {
        List<String> tags = new ArrayList<>();
        if (patch.meanSlope() < 2.0) tags.add("flat");
        else if (patch.meanSlope() < 6.0) tags.add("gentle");
        else tags.add("steep");
        if (patch.touchesWater()) tags.add("waterfront");
        if (patch.confidence() < 0.5) tags.add("low_confidence");
        return tags;
    }

    private List<String> buildOverlays(LandformPatch patch) {
        List<String> overlays = new ArrayList<>();
        if (patch.waterDistanceMean() < 16.0) overlays.add("near_water");
        if (patch.flags().stream().anyMatch(f -> f.name().equals("EDGE_DIRTY")))
            overlays.add("edge_dirty");
        if (patch.flags().stream().anyMatch(f -> f.name().equals("FRAGMENT")))
            overlays.add("fragment");
        return overlays;
    }

    double meanSlope(List<LandformPatchSummary> summaries) {
        return summaries.stream()
                .mapToDouble(s -> s.metricsSummary().meanSlope())
                .average().orElse(0.0);
    }
}
