package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CityStructureClusterGroupCandidatePlanner {
    public static final String CANDIDATE_SET_SCHEMA =
            "city_d4_structure_cluster_group_candidate_set.v0.1";

    private static final int DEFAULT_GROUP_COUNT = 5;
    private static final int DEFAULT_CANDIDATES_PER_SLOT = 5;

    public Result plan(Path baseDirectory,
                       CityLandformReviewPackage reviewPackage,
                       JsonObject terraSenseProfileSource,
                       JsonObject designSlotPlan,
                       CityStructureEnvelopeFacts envelopeFacts,
                       Options options) throws IOException {
        long started = System.nanoTime();
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4 structure cluster groups.");
        }
        Options normalized = options == null ? Options.defaults() : options.normalized();
        CityStructureAnchorCandidatePlanner slotPlanner = new CityStructureAnchorCandidatePlanner();
        CityStructureAnchorCandidatePlanner.SessionResult seed = slotPlanner.createSession(
                baseDirectory, reviewPackage, terraSenseProfileSource, designSlotPlan,
                reviewPackage.cityId() + "_cluster_group_seed");

        JsonArray groupCandidates = new JsonArray();
        JsonArray generationReports = new JsonArray();
        List<String> hardBlocks = strings(array(seed.qualityReport(), "hardBlocks"));
        List<String> warnings = strings(array(seed.qualityReport(), "warnings"));
        List<String> needsReview = strings(array(seed.qualityReport(), "needsReview"));
        int rejectedExtensionCount = 0;

        if (hardBlocks.isEmpty()) {
            List<PartialGroup> partials = List.of(PartialGroup.seed(seed.session()));
            int slotCount = array(seed.session(), "placementOrder").size();
            for (int depth = 0; depth < slotCount && !partials.isEmpty(); depth++) {
                List<PartialGroup> extensions = new ArrayList<>();
                for (PartialGroup partial : partials) {
                    if (isComplete(partial.session())) {
                        extensions.add(partial);
                        continue;
                    }
                    CityStructureAnchorCandidatePlanner.NextCandidateResult next = slotPlanner.planNext(
                            baseDirectory, reviewPackage, partial.session(), envelopeFacts);
                    JsonObject slotCandidateSet = next.slotCandidateSet();
                    String slotId = stringValue(slotCandidateSet, "currentSlotId", "");
                    JsonArray candidates = currentSlotCandidates(slotCandidateSet);
                    int limit = Math.min(normalized.candidatesPerSlot(), candidates.size());
                    JsonObject report = generationReport(slotId, partial.signature(), candidates.size(), limit);
                    generationReports.add(report);
                    for (int i = 0; i < limit; i++) {
                        JsonObject candidate = candidates.get(i).getAsJsonObject();
                        String candidateId = stringValue(candidate, "candidateId", "");
                        try {
                            CityStructureAnchorCandidatePlanner.SelectionResult selected =
                                    slotPlanner.selectSession(partial.session(), slotCandidateSet, slotId,
                                            candidateId, anchorId(slotId), "D4 structure cluster group beam search",
                                            false);
                            extensions.add(partial.extend(selected.session(), candidate));
                        } catch (IllegalArgumentException ex) {
                            rejectedExtensionCount++;
                            report.getAsJsonArray("rejectedExtensions")
                                    .add(rejectedExtension(slotId, candidateId, ex.getMessage()));
                        }
                    }
                    if (limit == 0) {
                        warnings.add(slotId + ": no candidates available for structure cluster group search.");
                    }
                }
                partials = prune(extensions, normalized.beamWidth());
            }

            List<PartialGroup> complete = partials.stream()
                    .filter(group -> isComplete(group.session()))
                    .sorted(Comparator.comparingDouble(PartialGroup::score).reversed())
                    .limit(normalized.groupCount())
                    .toList();
            int index = 0;
            for (PartialGroup group : complete) {
                index++;
                groupCandidates.add(groupCandidate(slotPlanner, group, index));
            }
            if (groupCandidates.isEmpty()) {
                hardBlocks.add("D4_STRUCTURE_CLUSTER_GROUP_UNSATISFIED: no complete non-overlapping group candidates.");
            } else if (groupCandidates.size() < normalized.groupCount()) {
                warnings.add("D4_STRUCTURE_CLUSTER_GROUP_PARTIAL: requested " + normalized.groupCount()
                        + " groups but only generated " + groupCandidates.size() + ".");
            }
        }

        JsonObject candidateSet = new JsonObject();
        candidateSet.addProperty("schemaVersion", CANDIDATE_SET_SCHEMA);
        candidateSet.addProperty("cityId", reviewPackage.cityId());
        candidateSet.addProperty("planningMode", "structure_cluster_group_candidates");
        candidateSet.addProperty("generatedAt", Instant.now().toString());
        candidateSet.addProperty("requestedGroupCount", normalized.groupCount());
        candidateSet.addProperty("candidatesPerSlot", normalized.candidatesPerSlot());
        candidateSet.addProperty("beamWidth", normalized.beamWidth());
        candidateSet.add("grid", reviewPackage.grid().asJson());
        candidateSet.add("sourceDesignSlotPlan", designSlotPlan == null ? new JsonObject() : designSlotPlan.deepCopy());
        candidateSet.add("sourceTerraSenseProfileSource",
                terraSenseProfileSource == null ? new JsonObject() : terraSenseProfileSource.deepCopy());
        candidateSet.add("structureProfileCatalog", object(seed.session(), "structureProfileCatalog").deepCopy());
        candidateSet.add("groupCandidates", groupCandidates);
        candidateSet.add("generationReports", generationReports);
        JsonObject quality = quality(hardBlocks, warnings, needsReview, groupCandidates,
                normalized.groupCount(), rejectedExtensionCount);
        candidateSet.add("quality", quality);
        candidateSet.add("timingMs", timing(started));
        return new Result(designSlotPlan == null ? new JsonObject() : designSlotPlan.deepCopy(), candidateSet, quality);
    }

    private static JsonObject groupCandidate(CityStructureAnchorCandidatePlanner slotPlanner,
                                             PartialGroup group,
                                             int groupIndex) {
        String groupId = "cluster_group_" + String.format(Locale.ROOT, "%02d", groupIndex);
        CityStructureAnchorCandidatePlanner.FinalizeResult finalized = slotPlanner.finalizeSession(group.session());
        JsonArray items = new JsonArray();
        BlockBounds groupCollision = null;
        BlockBounds groupMask = null;
        BlockBounds groupSafety = null;
        int itemIndex = 0;
        for (JsonElement elem : array(group.session(), "selectedAnchors")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            itemIndex++;
            JsonObject selected = elem.getAsJsonObject();
            JsonObject item = itemJson(groupId, itemIndex, selected);
            items.add(item);
            groupCollision = unionIfPresent(groupCollision, selected, "estimatedCollisionEnvelope");
            groupMask = unionIfPresent(groupMask, selected, "estimatedMaskEnvelope");
            groupSafety = unionIfPresent(groupSafety, selected, "estimatedSafetyEnvelope");
        }

        JsonObject expandedPlan = finalized.structureAnchorPlan().deepCopy();
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", CANDIDATE_SET_SCHEMA);
        trace.addProperty("planningMode", "structure_cluster_group_candidates");
        trace.addProperty("groupCandidateId", groupId);
        trace.addProperty("groupSignature", group.signature());
        trace.addProperty("itemCount", items.size());
        expandedPlan.add("structureClusterGroupTrace", trace);

        JsonObject candidate = new JsonObject();
        candidate.addProperty("groupCandidateId", groupId);
        candidate.addProperty("displayName", "Group " + groupIndex);
        candidate.addProperty("groupSignature", group.signature());
        candidate.addProperty("placedItemCount", items.size());
        candidate.addProperty("requestedItemCount", array(group.session(), "placementOrder").size());
        candidate.add("items", items);
        if (groupCollision != null) {
            candidate.add("groupCollisionEnvelope", boundsJson(groupCollision));
        }
        if (groupMask != null) {
            candidate.add("groupMaskEnvelope", boundsJson(groupMask));
        }
        if (groupSafety != null) {
            candidate.add("groupSafetyEnvelope", boundsJson(groupSafety));
        }
        candidate.add("scoreBreakdown", groupScore(items, groupCollision));
        candidate.add("risks", groupRisks(items, group.rejectedExtensionCount()));
        candidate.addProperty("rejectedExtensionCount", group.rejectedExtensionCount());
        candidate.add("rejectedExtensions", group.rejectedExtensions().deepCopy());
        candidate.add("expandedStructureAnchorPlan", expandedPlan);
        return candidate;
    }

    private static JsonObject itemJson(String groupId, int itemIndex, JsonObject selected) {
        JsonObject item = new JsonObject();
        item.addProperty("itemId", groupId + "_item_" + String.format(Locale.ROOT, "%02d", itemIndex));
        item.addProperty("itemIndex", itemIndex);
        copyString(selected, item, "slotId");
        copyString(selected, item, "anchorId");
        copyString(selected, item, "candidateId");
        copyString(selected, item, "displayRole");
        copyString(selected, item, "structureId");
        copyString(selected, item, "rotation");
        copyString(selected, item, "geometryStatus");
        copyString(selected, item, "envelopeMode");
        copyString(selected, item, "selectedEnvelopeGroupKey");
        copyString(selected, item, "roadAccessIntent");
        if (selected.has("smallClearanceBlocks")) {
            item.add("smallClearanceBlocks", selected.get("smallClearanceBlocks").deepCopy());
        }
        copyObject(selected, item, "anchorBlock");
        copyArray(selected, item, "sourcePatchRefs");
        copyArray(selected, item, "intentTerms");
        copyObject(selected, item, "estimatedCollisionEnvelope");
        copyObject(selected, item, "estimatedMaskEnvelope");
        copyObject(selected, item, "estimatedSafetyEnvelope");
        copyObject(selected, item, "scoreBreakdown");
        copyArray(selected, item, "risks");
        return item;
    }

    private static JsonObject groupScore(JsonArray items, BlockBounds groupCollision) {
        double slotTotal = 0.0;
        double relation = 0.0;
        int scoreCount = 0;
        for (JsonElement elem : items) {
            JsonObject item = elem.getAsJsonObject();
            JsonObject score = object(item, "scoreBreakdown");
            if (score.has("total")) {
                slotTotal += score.get("total").getAsDouble();
                scoreCount++;
            }
            if (score.has("relationFit")) {
                relation += score.get("relationFit").getAsDouble();
            }
        }
        double averageSlotScore = scoreCount == 0 ? 0.0 : slotTotal / scoreCount;
        double averageRelation = scoreCount == 0 ? 0.0 : relation / scoreCount;
        double compactness = groupCollision == null ? 0.0
                : clamp01(1.0 - ((groupCollision.widthBlocks() + groupCollision.heightBlocks()) / 1024.0));
        double collisionSafety = 1.0;
        double total = averageSlotScore * 0.78 + compactness * 0.12 + collisionSafety * 0.10;
        JsonObject score = new JsonObject();
        score.addProperty("total", total);
        score.addProperty("averageSlotScore", averageSlotScore);
        score.addProperty("averageRelationFit", averageRelation);
        score.addProperty("compactness", compactness);
        score.addProperty("collisionSafety", collisionSafety);
        return score;
    }

    private static JsonArray groupRisks(JsonArray items, int rejectedExtensionCount) {
        Set<String> risks = new LinkedHashSet<>();
        for (JsonElement elem : items) {
            for (JsonElement risk : array(elem.getAsJsonObject(), "risks")) {
                if (!risk.isJsonNull()) {
                    risks.add(risk.getAsString());
                }
            }
        }
        if (rejectedExtensionCount > 0) {
            risks.add("beam_repair_used");
        }
        JsonArray array = new JsonArray();
        risks.forEach(array::add);
        return array;
    }

    private static List<PartialGroup> prune(List<PartialGroup> groups, int beamWidth) {
        List<PartialGroup> sorted = groups.stream()
                .sorted(Comparator.comparingDouble(PartialGroup::score).reversed())
                .toList();
        List<PartialGroup> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PartialGroup group : sorted) {
            if (seen.add(group.signature())) {
                result.add(group);
            }
            if (result.size() >= beamWidth) {
                break;
            }
        }
        return result;
    }

    private static JsonArray currentSlotCandidates(JsonObject slotCandidateSet) {
        String currentSlotId = stringValue(slotCandidateSet, "currentSlotId", "");
        for (JsonElement slotElem : array(slotCandidateSet, "slotCandidates")) {
            if (!slotElem.isJsonObject()) {
                continue;
            }
            JsonObject slot = slotElem.getAsJsonObject();
            if (currentSlotId.equals(stringValue(slot, "slotId", ""))) {
                return array(slot, "candidates");
            }
        }
        return new JsonArray();
    }

    private static JsonObject generationReport(String slotId, String partialSignature,
                                               int candidateCount, int extendedCount) {
        JsonObject report = new JsonObject();
        report.addProperty("slotId", slotId);
        report.addProperty("partialSignature", partialSignature);
        report.addProperty("candidateCount", candidateCount);
        report.addProperty("extendedCount", extendedCount);
        report.add("rejectedExtensions", new JsonArray());
        return report;
    }

    private static JsonObject rejectedExtension(String slotId, String candidateId, String reason) {
        JsonObject obj = new JsonObject();
        obj.addProperty("slotId", slotId);
        obj.addProperty("candidateId", candidateId);
        obj.addProperty("reasonCode", firstReason(reason));
        obj.addProperty("message", reason == null ? "" : reason);
        return obj;
    }

    private static JsonObject quality(List<String> hardBlocks, List<String> warnings,
                                      List<String> needsReview, JsonArray groupCandidates,
                                      int requestedGroupCount, int rejectedExtensionCount) {
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty() && !groupCandidates.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() && !groupCandidates.isEmpty() ? 100 : 0);
        quality.add("hardBlocks", stringArray(hardBlocks));
        quality.add("warnings", stringArray(warnings));
        quality.add("needsReview", stringArray(needsReview));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("requestedGroupCount", requestedGroupCount);
        metrics.addProperty("groupCandidateCount", groupCandidates.size());
        metrics.addProperty("rejectedExtensionCount", rejectedExtensionCount);
        int itemCount = 0;
        for (JsonElement groupElem : groupCandidates) {
            itemCount += array(groupElem.getAsJsonObject(), "items").size();
        }
        metrics.addProperty("candidateItemCount", itemCount);
        quality.add("metrics", metrics);
        return quality;
    }

    private static boolean isComplete(JsonObject session) {
        return intValue(session, "remainingSlotCount", 0) <= 0
                || stringValue(session, "currentSlotId", "").isBlank();
    }

    private static String anchorId(String slotId) {
        return slotId == null || slotId.isBlank() ? "cluster_anchor" : slotId;
    }

    private static double candidateScore(JsonObject candidate) {
        JsonObject score = object(candidate, "scoreBreakdown");
        return score.has("total") ? score.get("total").getAsDouble() : 0.0;
    }

    private static BlockBounds unionIfPresent(BlockBounds current, JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            return current;
        }
        BlockBounds next = bounds(obj.getAsJsonObject(key));
        return current == null ? next : CityStructureCandidateEnvelope.union(current, next);
    }

    private static void copyString(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && !from.get(key).isJsonNull()) {
            to.addProperty(key, from.get(key).getAsString());
        }
    }

    private static void copyObject(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && from.get(key).isJsonObject()) {
            to.add(key, from.getAsJsonObject(key).deepCopy());
        }
    }

    private static void copyArray(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && from.get(key).isJsonArray()) {
            to.add(key, from.getAsJsonArray(key).deepCopy());
        }
    }

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", (System.nanoTime() - started) / 1_000_000L);
        return timing;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
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

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static String firstReason(String message) {
        if (message == null || message.isBlank()) {
            return "D4_STRUCTURE_CLUSTER_EXTENSION_REJECTED";
        }
        int colon = message.indexOf(':');
        if (colon > 0) {
            return message.substring(0, colon);
        }
        return message.length() > 64 ? message.substring(0, 64) : message;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Options(int groupCount, int candidatesPerSlot, int beamWidth) {
        public static Options defaults() {
            return new Options(DEFAULT_GROUP_COUNT, DEFAULT_CANDIDATES_PER_SLOT,
                    DEFAULT_GROUP_COUNT * DEFAULT_CANDIDATES_PER_SLOT);
        }

        Options normalized() {
            int groups = Math.max(1, groupCount <= 0 ? DEFAULT_GROUP_COUNT : groupCount);
            int perSlot = Math.max(1, candidatesPerSlot <= 0 ? DEFAULT_CANDIDATES_PER_SLOT : candidatesPerSlot);
            int width = Math.max(groups, beamWidth <= 0 ? groups * perSlot : beamWidth);
            return new Options(groups, perSlot, width);
        }
    }

    public record Result(JsonObject designSlotPlan, JsonObject structureClusterGroupCandidateSet,
                         JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.add("designSlotPlan", designSlotPlan.deepCopy());
            obj.add("structureClusterGroupCandidateSet", structureClusterGroupCandidateSet.deepCopy());
            obj.add("qualityReport", qualityReport.deepCopy());
            obj.add("timingMs", structureClusterGroupCandidateSet.getAsJsonObject("timingMs").deepCopy());
            return obj;
        }
    }

    private record PartialGroup(JsonObject session, double score, int rejectedExtensionCount,
                                JsonArray rejectedExtensions, String signature) {
        static PartialGroup seed(JsonObject session) {
            return new PartialGroup(session.deepCopy(), 0.0, 0, new JsonArray(), "seed");
        }

        PartialGroup extend(JsonObject updatedSession, JsonObject candidate) {
            JsonArray rejected = rejectedExtensions.deepCopy();
            String slotId = stringValue(candidate, "slotId", "");
            String candidateId = stringValue(candidate, "candidateId", "");
            String nextSignature = signature + "|" + slotId + "=" + candidateId;
            return new PartialGroup(updatedSession.deepCopy(), score + candidateScore(candidate),
                    rejectedExtensionCount, rejected, nextSignature);
        }
    }
}
