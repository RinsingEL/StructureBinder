package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSeedGroup;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule;
import com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LandUseSourceResolver {
    public Resolution resolve(JsonObject structureMaterializationPlan,
                              JsonObject landUseIntentPlan,
                              LandUseRuleCatalog rules) {
        return resolve(structureMaterializationPlan, landUseIntentPlan, null, rules);
    }

    public Resolution resolve(JsonObject structureMaterializationPlan,
                              JsonObject landUseIntentPlan,
                              JsonObject functionalArrayZones,
                              LandUseRuleCatalog rules) {
        if (structureMaterializationPlan == null) {
            throw new IllegalArgumentException("D6 structure_materialization_plan.json is required");
        }
        if (!booleanValue(structureMaterializationPlan, "locked", false)) {
            throw new IllegalArgumentException("LAND_USE_REQUIRES_LOCKED_D6_PLAN");
        }
        String cityId = requiredString(structureMaterializationPlan, "cityId");
        LandUseIntentPlan intent = new LandUseIntentPlanCodec().parse(landUseIntentPlan, cityId);
        if (!cityId.equals(intent.cityId())) throw new IllegalArgumentException("LAND_USE_INTENT_CITY_ID_MISMATCH");
        LandUseRuleCatalog catalog = rules == null ? LandUseRuleCatalog.defaults() : rules;
        validateRuleRefs(intent, catalog);

        Map<String, AnchorData> anchors = readAnchors(structureMaterializationPlan);
        Map<String, String> overrideMembership = overrideMembership(intent, anchors.keySet());
        Map<String, List<AnchorData>> grouped = new LinkedHashMap<>();
        for (AnchorData anchor : anchors.values().stream().sorted(Comparator.comparing(AnchorData::anchorId)).toList()) {
            String groupId = overrideMembership.getOrDefault(anchor.anchorId(), anchor.placementGroupId());
            grouped.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(anchor);
        }
        Map<String, LandUseIntentPlan.GroupOverride> groupOverrides = new HashMap<>();
        intent.groupOverrides().forEach(value -> groupOverrides.put(value.groupId(), value));
        Map<String, LandUseIntentPlan.SubjectOverride> groupSubjects = subjectMap(intent,
                LandUseIntentPlan.TargetType.GROUP);
        Map<String, LandUseIntentPlan.SubjectOverride> anchorSubjects = subjectMap(intent,
                LandUseIntentPlan.TargetType.ANCHOR);

        List<String> warnings = new ArrayList<>();
        List<BlockBounds> allFootprints = anchors.values().stream().map(AnchorData::footprint).toList();
        Map<String, List<LandUseAreaPlan.GateSlot>> zoneGates = zoneGates(functionalArrayZones, grouped);
        List<LandUseAreaPlan.CorridorExclusion> corridors = new ArrayList<>();
        List<LandUseSeedGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<AnchorData>> entry : grouped.entrySet()) {
            String groupId = entry.getKey();
            List<AnchorData> members = new ArrayList<>(entry.getValue());
            LandUseIntentPlan.SubjectOverride groupSubject = groupSubjects.get(groupId);
            if (groupSubject != null && groupSubject.mode() == LandUseIntentPlan.Mode.EXCLUDE) continue;
            for (LandUseIntentPlan.SubjectOverride anchorSubject : anchorSubjects.values()) {
                if (anchorSubject.mode() == LandUseIntentPlan.Mode.EXCLUDE) {
                    members.removeIf(member -> member.anchorId().equals(anchorSubject.targetId()));
                }
            }
            if (members.isEmpty()) continue;
            List<LandUseIntentPlan.SubjectOverride> memberRuleOverrides = members.stream()
                    .map(member -> anchorSubjects.get(member.anchorId())).filter(value -> value != null
                            && value.mode() == LandUseIntentPlan.Mode.SET_RULE).toList();
            if (!memberRuleOverrides.isEmpty() && members.size() > 1) {
                throw new IllegalArgumentException("LAND_USE_GROUPED_ANCHOR_OVERRIDE_AMBIGUOUS: " + groupId);
            }
            String explicitRule = groupSubject != null && groupSubject.mode() == LandUseIntentPlan.Mode.SET_RULE
                    ? groupSubject.ruleRef() : "";
            if (explicitRule.isBlank() && !memberRuleOverrides.isEmpty()) explicitRule = memberRuleOverrides.get(0).ruleRef();
            LandUseIntentPlan.GroupOverride groupOverride = groupOverrides.get(groupId);
            if (explicitRule.isBlank() && groupOverride != null) explicitRule = groupOverride.ruleRef();
            LandUseRule rule;
            if (explicitRule.isBlank()) {
                rule = catalog.resolveSemantic(semanticTerms(groupId, members)).orElse(null);
            } else {
                rule = catalog.byRef(explicitRule).orElseThrow(() ->
                        new IllegalArgumentException("LAND_USE_RULE_REF_UNKNOWN: " + groupId));
            }
            if (rule == null) {
                warnings.add("LAND_USE_SEMANTIC_UNKNOWN_SKIPPED:" + groupId);
                continue;
            }
            List<LandUseAreaPlan.GateSlot> gates = new ArrayList<>();
            for (AnchorData member : members) gates.addAll(member.gates());
            gates.addAll(zoneGates.getOrDefault(groupId, List.of()));
            gates = gates.stream().collect(java.util.stream.Collectors.toMap(
                    LandUseAreaPlan.GateSlot::gateId, value -> value, (left, right) -> left, LinkedHashMap::new))
                    .values().stream().toList();
            if (gates.isEmpty()) warnings.add("LAND_USE_ROAD_ENTRANCE_MISSING:" + groupId);
            for (LandUseAreaPlan.GateSlot gate : gates) corridors.add(corridor(gate));
            List<BlockBounds> footprints = members.stream().map(AnchorData::footprint).toList();
            int footprintArea = footprints.stream().mapToInt(bounds -> bounds.widthBlocks() * bounds.heightBlocks()).sum();
            int preferred = rule.preferredArea(footprintArea);
            int min = Math.min(preferred, Math.max(rule.minAreaBlocks(), (int) Math.round(preferred * 0.6)));
            int max = Math.max(preferred, Math.min(rule.maxAreaBlocks(), (int) Math.round(preferred * 1.6)));
            List<BlockPoint> seeds = perimeterSeeds(footprints);
            groups.add(new LandUseSeedGroup(groupId, rule, members.stream().map(AnchorData::anchorId).toList(),
                    allFootprints, seeds, gates, min, preferred, max, rule.actionBudget(), rule.competitionWeight()));
        }
        validateSubjectTargets(intent, grouped, anchors);
        return new Resolution(List.copyOf(groups), List.copyOf(corridors), List.copyOf(warnings), intent.seedSalt());
    }

    private static Map<String, AnchorData> readAnchors(JsonObject plan) {
        JsonArray items = requiredArray(plan, "plannedWorldgenStructures");
        if (items.isEmpty()) throw new IllegalArgumentException("LAND_USE_D6_STRUCTURES_EMPTY");
        Map<String, AnchorData> anchors = new LinkedHashMap<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("D6 plannedWorldgenStructures must contain objects");
            JsonObject item = element.getAsJsonObject();
            String anchorId = requiredString(item, "anchorId");
            JsonObject footprintJson = object(item, "lockedActualFootprint");
            if (footprintJson.size() == 0) footprintJson = object(item, "actualFootprint");
            if (footprintJson.size() == 0) {
                throw new IllegalArgumentException("LAND_USE_D6_ACTUAL_FOOTPRINT_MISSING: " + anchorId);
            }
            if (!item.has("placementGroupId") || !item.has("placementProvenance")
                    || !item.get("placementProvenance").isJsonObject()) {
                throw new IllegalArgumentException("LAND_USE_D4_V02_PROVENANCE_REQUIRED: " + anchorId);
            }
            String groupId = requiredString(item, "placementGroupId");
            List<String> terms = new ArrayList<>();
            for (String key : List.of("semanticTerms", "functionTerms", "intentTerms", "usageTerms")) {
                terms.addAll(strings(array(item, key)));
            }
            for (String key : List.of("displayRole", "structureId", "arrayId")) {
                String value = stringValue(item, key, "");
                if (!value.isBlank()) terms.add(value);
            }
            JsonObject provenance = item.getAsJsonObject("placementProvenance");
            for (String key : List.of("slotId", "arrayId", "parentArrayId", "subZoneId")) {
                String value = stringValue(provenance, key, "");
                if (!value.isBlank()) terms.add(value);
            }
            List<LandUseAreaPlan.GateSlot> gates = templateGates(item, anchorId);
            AnchorData previous = anchors.put(anchorId,
                    new AnchorData(anchorId, groupId, bounds(footprintJson), List.copyOf(terms), gates));
            if (previous != null) throw new IllegalArgumentException("LAND_USE_D6_ANCHOR_DUPLICATE: " + anchorId);
        }
        return anchors;
    }

    private static void validateRuleRefs(LandUseIntentPlan intent, LandUseRuleCatalog catalog) {
        for (LandUseIntentPlan.GroupOverride override : intent.groupOverrides()) {
            if (!override.ruleRef().isBlank() && catalog.byRef(override.ruleRef()).isEmpty()) {
                throw new IllegalArgumentException("LAND_USE_RULE_REF_UNKNOWN: " + override.ruleRef());
            }
        }
        for (LandUseIntentPlan.SubjectOverride override : intent.subjectOverrides()) {
            if (override.mode() == LandUseIntentPlan.Mode.SET_RULE
                    && catalog.byRef(override.ruleRef()).isEmpty()) {
                throw new IllegalArgumentException("LAND_USE_RULE_REF_UNKNOWN: " + override.ruleRef());
            }
        }
    }

    private static Map<String, String> overrideMembership(LandUseIntentPlan intent, Set<String> anchorIds) {
        Map<String, String> membership = new HashMap<>();
        for (LandUseIntentPlan.GroupOverride group : intent.groupOverrides()) {
            for (String anchorId : group.memberAnchorIds()) {
                if (!anchorIds.contains(anchorId)) {
                    throw new IllegalArgumentException("LAND_USE_GROUP_OVERRIDE_ANCHOR_UNKNOWN: " + anchorId);
                }
                membership.put(anchorId, group.groupId());
            }
        }
        return membership;
    }

    private static Map<String, LandUseIntentPlan.SubjectOverride> subjectMap(LandUseIntentPlan intent,
                                                                             LandUseIntentPlan.TargetType type) {
        Map<String, LandUseIntentPlan.SubjectOverride> values = new HashMap<>();
        intent.subjectOverrides().stream().filter(value -> value.targetType() == type)
                .forEach(value -> values.put(value.targetId(), value));
        return values;
    }

    private static void validateSubjectTargets(LandUseIntentPlan intent, Map<String, List<AnchorData>> groups,
                                               Map<String, AnchorData> anchors) {
        for (LandUseIntentPlan.SubjectOverride value : intent.subjectOverrides()) {
            boolean exists = value.targetType() == LandUseIntentPlan.TargetType.GROUP
                    ? groups.containsKey(value.targetId()) : anchors.containsKey(value.targetId());
            if (!exists) throw new IllegalArgumentException("LAND_USE_SUBJECT_OVERRIDE_TARGET_UNKNOWN: " + value.targetId());
        }
    }

    private static List<String> semanticTerms(String groupId, List<AnchorData> members) {
        List<String> terms = new ArrayList<>();
        terms.add(groupId);
        members.forEach(member -> terms.addAll(member.semanticTerms()));
        return terms;
    }

    private static List<BlockPoint> perimeterSeeds(List<BlockBounds> footprints) {
        Set<BlockPoint> points = new LinkedHashSet<>();
        for (BlockBounds bounds : footprints) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                points.add(new BlockPoint(x, bounds.minZ() - 1));
                points.add(new BlockPoint(x, bounds.maxZ() + 1));
            }
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                points.add(new BlockPoint(bounds.minX() - 1, z));
                points.add(new BlockPoint(bounds.maxX() + 1, z));
            }
        }
        return points.stream().sorted(Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x)).toList();
    }

    private static List<LandUseAreaPlan.GateSlot> templateGates(JsonObject item, String anchorId) {
        JsonObject plan = object(item, "templatePlacementPlan");
        JsonObject transformed = object(plan, "transformed");
        List<LandUseAreaPlan.GateSlot> gates = new ArrayList<>();
        int ordinal = 0;
        for (JsonElement element : array(transformed, "roadEntrances")) {
            if (!element.isJsonObject()) continue;
            JsonObject entrance = element.getAsJsonObject();
            JsonObject point = object(entrance, "worldPosition");
            if (point.size() == 0) continue;
            CardinalDirection direction = CardinalDirection.from(stringValue(entrance, "direction", ""), null);
            if (direction == null) continue;
            BlockPoint block = point(point);
            gates.add(new LandUseAreaPlan.GateSlot(stringValue(entrance, "entranceId",
                    anchorId + "_entrance_" + (++ordinal)), block, direction, anchorId));
        }
        return gates;
    }

    private static Map<String, List<LandUseAreaPlan.GateSlot>> zoneGates(JsonObject source,
                                                                         Map<String, List<AnchorData>> groups) {
        Map<String, List<LandUseAreaPlan.GateSlot>> result = new HashMap<>();
        if (source == null) return result;
        List<JsonObject> zones = new ArrayList<>();
        collectZones(source, zones);
        for (JsonObject zone : zones) {
            String arrayId = stringValue(zone, "arrayId", "");
            String groupId = stringValue(zone, "parentArrayId", arrayId);
            if (!groups.containsKey(groupId) && groups.containsKey(arrayId)) groupId = arrayId;
            if (!groups.containsKey(groupId)) continue;
            BlockPoint center = union(groups.get(groupId).stream().map(AnchorData::footprint).toList()).center();
            int ordinal = 0;
            for (JsonElement element : array(zone, "roadAccessPoints")) {
                if (!element.isJsonObject()) continue;
                JsonObject access = element.getAsJsonObject();
                JsonObject point = object(access, "anchorBlock");
                if (point.size() == 0) point = object(access, "worldPosition");
                if (point.size() == 0) continue;
                BlockPoint block = point(point);
                CardinalDirection direction = CardinalDirection.from(stringValue(access, "direction", ""),
                        outward(center, block));
                String gateId = stringValue(access, "roadAccessPointId", groupId + "_gateway_" + (++ordinal));
                result.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(
                        new LandUseAreaPlan.GateSlot(gateId, block, direction, ""));
            }
        }
        return result;
    }

    private static void collectZones(JsonObject obj, List<JsonObject> result) {
        if (obj.has("arrayId") && obj.has("roadAccessPoints")) result.add(obj);
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (entry.getValue().isJsonObject()) collectZones(entry.getValue().getAsJsonObject(), result);
            else if (entry.getValue().isJsonArray()) {
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (element.isJsonObject()) collectZones(element.getAsJsonObject(), result);
                }
            }
        }
    }

    private static LandUseAreaPlan.CorridorExclusion corridor(LandUseAreaPlan.GateSlot gate) {
        int endX = gate.block().x() + gate.direction().dx() * 3;
        int endZ = gate.block().z() + gate.direction().dz() * 3;
        return new LandUseAreaPlan.CorridorExclusion(gate.gateId() + "_corridor",
                new BlockBounds(Math.min(gate.block().x(), endX), Math.min(gate.block().z(), endZ),
                        Math.max(gate.block().x(), endX), Math.max(gate.block().z(), endZ)), gate.gateId());
    }

    private static CardinalDirection outward(BlockPoint center, BlockPoint point) {
        int dx = point.x() - center.x();
        int dz = point.z() - center.z();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? CardinalDirection.EAST : CardinalDirection.WEST;
        return dz >= 0 ? CardinalDirection.SOUTH : CardinalDirection.NORTH;
    }

    private static BlockBounds union(List<BlockBounds> bounds) {
        int minX = bounds.stream().mapToInt(BlockBounds::minX).min().orElseThrow();
        int minZ = bounds.stream().mapToInt(BlockBounds::minZ).min().orElseThrow();
        int maxX = bounds.stream().mapToInt(BlockBounds::maxX).max().orElseThrow();
        int maxZ = bounds.stream().mapToInt(BlockBounds::maxZ).max().orElseThrow();
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static BlockPoint point(JsonObject obj) {
        return new BlockPoint(intValue(obj, "x", 0), intValue(obj, "z", 0));
    }

    private static JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject()
                ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required");
        }
        return obj.getAsJsonArray(key);
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) if (!element.isJsonNull()) values.add(element.getAsString());
        return values;
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback;
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    public record Resolution(List<LandUseSeedGroup> seedGroups,
                             List<LandUseAreaPlan.CorridorExclusion> corridorExclusions,
                             List<String> warnings,
                             String seedSalt) {
    }

    private record AnchorData(String anchorId,
                              String placementGroupId,
                              BlockBounds footprint,
                              List<String> semanticTerms,
                              List<LandUseAreaPlan.GateSlot> gates) {
    }
}
