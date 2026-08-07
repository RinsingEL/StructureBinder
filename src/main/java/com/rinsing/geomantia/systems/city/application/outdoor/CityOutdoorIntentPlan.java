package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** Program-resolved outdoor intent; it contains decisions and derived budgets, not final geometry. */
public record CityOutdoorIntentPlan(
        String schemaVersion,
        String cityId,
        CityBlueprint.OutdoorMode mode,
        String ruleProfileHash,
        String sourceBlueprintHash,
        String sourceD6Hash,
        String sourceTerrainFieldHash,
        String sourceOutdoorCatalogHash,
        String planHash,
        List<SourceIntent> sources,
        EnvelopeIntent envelope,
        ResidualIntent residual) {

    public static final String SCHEMA_VERSION = "city_outdoor_intent_plan.v0.3";

    public CityOutdoorIntentPlan {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported city outdoor intent schema: " + schemaVersion);
        }
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        if (mode == null) throw new IllegalArgumentException("mode is required");
        ruleProfileHash = ruleProfileHash == null ? "" : ruleProfileHash;
        sourceBlueprintHash = requiredSourceHash(sourceBlueprintHash, "sourceBlueprintHash");
        sourceD6Hash = requiredSourceHash(sourceD6Hash, "sourceD6Hash");
        sourceTerrainFieldHash = requiredSourceHash(sourceTerrainFieldHash, "sourceTerrainFieldHash");
        sourceOutdoorCatalogHash = requiredSourceHash(sourceOutdoorCatalogHash, "sourceOutdoorCatalogHash");
        planHash = planHash == null ? "" : planHash;
        sources = List.copyOf(sources == null ? List.of() : sources);
        if (envelope == null || residual == null) {
            throw new IllegalArgumentException("envelope and residual are required");
        }
    }

    public CityOutdoorIntentPlan withComputedHash() {
        CityOutdoorIntentPlan withoutHash = new CityOutdoorIntentPlan(schemaVersion, cityId, mode,
                ruleProfileHash, sourceBlueprintHash, sourceD6Hash, sourceTerrainFieldHash,
                sourceOutdoorCatalogHash, "", sources, envelope, residual);
        JsonObject canonical = canonical(withoutHash.toJson()).getAsJsonObject();
        canonical.remove("planHash");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            return new CityOutdoorIntentPlan(schemaVersion, cityId, mode, ruleProfileHash,
                    sourceBlueprintHash, sourceD6Hash, sourceTerrainFieldHash, sourceOutdoorCatalogHash,
                    hash, sources, envelope, residual);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", schemaVersion);
        root.addProperty("cityId", cityId);
        root.addProperty("mode", mode.name());
        root.addProperty("ruleProfileHash", ruleProfileHash);
        root.addProperty("sourceBlueprintHash", sourceBlueprintHash);
        root.addProperty("sourceD6Hash", sourceD6Hash);
        root.addProperty("sourceTerrainFieldHash", sourceTerrainFieldHash);
        root.addProperty("sourceOutdoorCatalogHash", sourceOutdoorCatalogHash);
        if (!planHash.isBlank()) root.addProperty("planHash", planHash);
        JsonArray sourceValues = new JsonArray();
        for (SourceIntent source : sources) {
            JsonObject value = new JsonObject();
            value.addProperty("sourceId", source.sourceId());
            value.addProperty("sourceKind", source.sourceKind().name());
            value.addProperty("profileRef", source.profileRef());
            value.addProperty("landUseRuleRef", source.landUseRuleRef());
            value.addProperty("surfaceRecipeRef", source.surfaceRecipeRef());
            value.addProperty("membership", source.membership().name());
            if (source.extentClass() == null) value.add("extentClass", null);
            else value.addProperty("extentClass", source.extentClass().name());
            if (source.intensity() == null) value.add("intensity", null);
            else value.addProperty("intensity", source.intensity().name());
            if (source.continuity() == null) value.add("continuity", null);
            else value.addProperty("continuity", source.continuity().name());
            if (source.growthRelation() == null) value.add("growthRelation", null);
            else value.addProperty("growthRelation", source.growthRelation().name());
            value.addProperty("terrainPolicy", source.terrainPolicy().name());
            JsonArray groupIds = new JsonArray();
            source.sourceGroupIds().forEach(groupIds::add);
            value.add("sourceGroupIds", groupIds);
            JsonArray anchorIds = new JsonArray();
            source.sourceAnchorIds().forEach(anchorIds::add);
            value.add("sourceAnchorIds", anchorIds);
            JsonArray patchRefs = new JsonArray();
            source.preferredPatchRefs().forEach(patchRefs::add);
            value.add("preferredPatchRefs", patchRefs);
            value.addProperty("minAreaBlocks", source.minAreaBlocks());
            value.addProperty("preferredAreaBlocks", source.preferredAreaBlocks());
            value.addProperty("maxAreaBlocks", source.maxAreaBlocks());
            value.addProperty("growthBias", source.growthBias().name());
            if (source.referencePoint() == null) value.add("referencePoint", null);
            else value.add("referencePoint", point(source.referencePoint()));
            JsonArray seeds = new JsonArray();
            source.seedPoints().forEach(seed -> seeds.add(point(seed)));
            value.add("seedPoints", seeds);
            value.addProperty("required", source.required());
            sourceValues.add(value);
        }
        root.add("sources", sourceValues);
        JsonObject envelopeValue = new JsonObject();
        envelopeValue.addProperty("profile", envelope.profile().name());
        envelopeValue.addProperty("closeRadiusBlocks", envelope.closeRadiusBlocks());
        JsonArray urbanGroups = new JsonArray();
        envelope.urbanGroupIds().forEach(urbanGroups::add);
        envelopeValue.add("urbanGroupIds", urbanGroups);
        root.add("envelope", envelopeValue);
        JsonObject residualValue = new JsonObject();
        residualValue.addProperty("smallEnclosed", residual.smallEnclosed().name());
        residualValue.addProperty("narrowGap", residual.narrowGap().name());
        residualValue.addProperty("mediumEnclosed", residual.mediumEnclosed().name());
        residualValue.addProperty("largeEnclosed", residual.largeEnclosed().name());
        residualValue.addProperty("exteriorConnected", residual.exteriorConnected().name());
        root.add("residual", residualValue);
        return root;
    }

    private static JsonObject point(BlockPoint point) {
        JsonObject value = new JsonObject();
        value.addProperty("x", point.x());
        value.addProperty("z", point.z());
        return value;
    }

    private static JsonElement canonical(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value.deepCopy();
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(canonical(element)));
            return result;
        }
        JsonObject result = new JsonObject();
        for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) {
            result.add(key, canonical(value.getAsJsonObject().get(key)));
        }
        return result;
    }

    private static String requiredSourceHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must use sha256:<64 lowercase hex>");
        }
        return value;
    }

    public record SourceIntent(String sourceId,
                               SourceKind sourceKind,
                               String profileRef,
                               String landUseRuleRef,
                               String surfaceRecipeRef,
                               CityBlueprint.OutdoorMembership membership,
                               CityBlueprint.ExtentClass extentClass,
                               CityBlueprint.OutdoorIntensity intensity,
                               CityBlueprint.LandscapeContinuity continuity,
                               CityBlueprint.LandscapeGrowthRelation growthRelation,
                               CityBlueprint.TerrainPolicy terrainPolicy,
                               List<String> sourceGroupIds,
                               List<String> sourceAnchorIds,
                               List<String> preferredPatchRefs,
                               int minAreaBlocks,
                               int preferredAreaBlocks,
                               int maxAreaBlocks,
                               CityBlueprint.GrowthBias growthBias,
                               BlockPoint referencePoint,
                               List<BlockPoint> seedPoints,
                               boolean required) {
        public SourceIntent {
            sourceGroupIds = List.copyOf(sourceGroupIds == null ? List.of() : sourceGroupIds);
            sourceAnchorIds = List.copyOf(sourceAnchorIds == null ? List.of() : sourceAnchorIds);
            preferredPatchRefs = List.copyOf(preferredPatchRefs == null ? List.of() : preferredPatchRefs);
            seedPoints = List.copyOf(seedPoints == null ? List.of() : seedPoints);
        }
    }

    public record EnvelopeIntent(CityBlueprint.EnvelopeProfile profile,
                                 int closeRadiusBlocks,
                                 List<String> urbanGroupIds) {
        public EnvelopeIntent {
            urbanGroupIds = List.copyOf(urbanGroupIds == null ? List.of() : urbanGroupIds);
        }
    }

    public record ResidualIntent(CityUrbanSpacePlan.ResidualDisposition smallEnclosed,
                                 CityUrbanSpacePlan.ResidualDisposition narrowGap,
                                 CityUrbanSpacePlan.ResidualDisposition mediumEnclosed,
                                 CityUrbanSpacePlan.ResidualDisposition largeEnclosed,
                                 CityUrbanSpacePlan.ResidualDisposition exteriorConnected) {
    }

    public enum SourceKind {
        FOUNDATION,
        SPATIAL_GROUND,
        LANDSCAPE
    }
}
