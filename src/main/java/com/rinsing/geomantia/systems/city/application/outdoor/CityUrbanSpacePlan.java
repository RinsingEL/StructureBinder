package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/** Frozen explanation of the city envelope and every non-structure residual inside it. */
public record CityUrbanSpacePlan(
        String schemaVersion,
        String cityId,
        String planHash,
        boolean enabled,
        int closeRadiusBlocks,
        BlockBounds workingBounds,
        List<LandUseAreaPlan.ScanlineSpan> envelopeSpans,
        List<ResidualRegion> residualRegions,
        CoverageSummary coverageSummary) {

    public static final String SCHEMA_VERSION = "city_urban_space_plan.v0.1";

    public CityUrbanSpacePlan {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported city urban space plan schema: " + schemaVersion);
        }
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        planHash = planHash == null ? "" : planHash;
        if (closeRadiusBlocks < 0) throw new IllegalArgumentException("closeRadiusBlocks must not be negative");
        envelopeSpans = List.copyOf(envelopeSpans == null ? List.of() : envelopeSpans);
        residualRegions = List.copyOf(residualRegions == null ? List.of() : residualRegions);
        if (coverageSummary == null) throw new IllegalArgumentException("coverageSummary is required");
    }

    public CityUrbanSpacePlan withComputedHash() {
        CityUrbanSpacePlan withoutHash = new CityUrbanSpacePlan(schemaVersion, cityId, "", enabled,
                closeRadiusBlocks, workingBounds, envelopeSpans, residualRegions, coverageSummary);
        JsonObject canonical = canonical(withoutHash.toJson()).getAsJsonObject();
        canonical.remove("planHash");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            return new CityUrbanSpacePlan(schemaVersion, cityId, hash, enabled, closeRadiusBlocks,
                    workingBounds, envelopeSpans, residualRegions, coverageSummary);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", schemaVersion);
        root.addProperty("cityId", cityId);
        if (!planHash.isBlank()) root.addProperty("planHash", planHash);
        root.addProperty("enabled", enabled);
        root.addProperty("closeRadiusBlocks", closeRadiusBlocks);
        if (workingBounds == null) root.add("workingBounds", null);
        else root.add("workingBounds", bounds(workingBounds));
        root.add("envelopeSpans", spans(envelopeSpans));
        JsonArray residualValues = new JsonArray();
        for (ResidualRegion region : residualRegions) {
            JsonObject value = new JsonObject();
            value.addProperty("residualId", region.residualId());
            value.addProperty("residualClass", region.residualClass().name());
            value.addProperty("disposition", region.disposition().name());
            value.add("memberSpans", spans(region.memberSpans()));
            JsonArray adjacent = new JsonArray();
            region.adjacentGroupIds().forEach(adjacent::add);
            value.add("adjacentGroupIds", adjacent);
            value.addProperty("absorbedGroupId", region.absorbedGroupId());
            value.addProperty("blockCount", region.blockCount());
            value.addProperty("terrainDominated", region.terrainDominated());
            value.addProperty("touchesEnvelopeEdge", region.touchesEnvelopeEdge());
            residualValues.add(value);
        }
        root.add("residualRegions", residualValues);
        JsonObject coverage = new JsonObject();
        coverage.addProperty("envelopeBlocks", coverageSummary.envelopeBlocks());
        coverage.addProperty("landUseBlocks", coverageSummary.landUseBlocks());
        coverage.addProperty("structureBlocks", coverageSummary.structureBlocks());
        coverage.addProperty("corridorBlocks", coverageSummary.corridorBlocks());
        coverage.addProperty("absorbedResidualBlocks", coverageSummary.absorbedResidualBlocks());
        coverage.addProperty("explicitResidualBlocks", coverageSummary.explicitResidualBlocks());
        coverage.addProperty("unknownResidualBlocks", coverageSummary.unknownResidualBlocks());
        root.add("coverageSummary", coverage);
        return root;
    }

    private static JsonArray spans(List<LandUseAreaPlan.ScanlineSpan> spans) {
        JsonArray result = new JsonArray();
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            JsonObject value = new JsonObject();
            value.addProperty("z", span.z());
            value.addProperty("minX", span.minX());
            value.addProperty("maxX", span.maxX());
            result.add(value);
        }
        return result;
    }

    private static JsonObject bounds(BlockBounds bounds) {
        JsonObject value = new JsonObject();
        value.addProperty("minX", bounds.minX());
        value.addProperty("minZ", bounds.minZ());
        value.addProperty("maxX", bounds.maxX());
        value.addProperty("maxZ", bounds.maxZ());
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

    public static CityUrbanSpacePlan disabled(String cityId) {
        return new CityUrbanSpacePlan(SCHEMA_VERSION, cityId, "", false, 0, null, List.of(), List.of(),
                new CoverageSummary(0, 0, 0, 0, 0, 0, 0)).withComputedHash();
    }

    public record ResidualRegion(
            String residualId,
            ResidualClass residualClass,
            ResidualDisposition disposition,
            List<LandUseAreaPlan.ScanlineSpan> memberSpans,
            List<String> adjacentGroupIds,
            String absorbedGroupId,
            int blockCount,
            boolean terrainDominated,
            boolean touchesEnvelopeEdge) {
        public ResidualRegion {
            if (residualId == null || residualId.isBlank()) {
                throw new IllegalArgumentException("residualId is required");
            }
            if (residualClass == null || disposition == null) {
                throw new IllegalArgumentException("residual class and disposition are required");
            }
            memberSpans = List.copyOf(memberSpans == null ? List.of() : memberSpans);
            adjacentGroupIds = List.copyOf(adjacentGroupIds == null ? List.of() : adjacentGroupIds);
            absorbedGroupId = absorbedGroupId == null ? "" : absorbedGroupId;
            if (blockCount < 0) throw new IllegalArgumentException("blockCount must not be negative");
        }
    }

    public record CoverageSummary(
            int envelopeBlocks,
            int landUseBlocks,
            int structureBlocks,
            int corridorBlocks,
            int absorbedResidualBlocks,
            int explicitResidualBlocks,
            int unknownResidualBlocks) {
        public CoverageSummary {
            if (envelopeBlocks < 0 || landUseBlocks < 0 || structureBlocks < 0 || corridorBlocks < 0
                    || absorbedResidualBlocks < 0 || explicitResidualBlocks < 0 || unknownResidualBlocks < 0) {
                throw new IllegalArgumentException("coverage counts must not be negative");
            }
        }
    }

    public enum ResidualClass {
        SMALL_ENCLOSED,
        NARROW_GAP,
        MEDIUM_ENCLOSED,
        LARGE_ENCLOSED,
        EXTERIOR_CONNECTED,
        NATURAL_FEATURE
    }

    public enum ResidualDisposition {
        ABSORB_NEIGHBOR,
        PATH_OR_VERGE,
        COMMON_GREEN,
        SERVICE_GROUND,
        NATURAL_RESERVE
    }
}
