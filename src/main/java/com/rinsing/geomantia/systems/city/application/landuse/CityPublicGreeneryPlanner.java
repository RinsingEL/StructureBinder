package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Managed ground in shared district gaps, independent of whether buildings use platforms. */
public final class CityPublicGreeneryPlanner {
    public static final String SOURCE = "public_greenery::";
    public record Preset(String ground, List<String> plants, double density, int edgeClearance) {
        public Preset {
            plants = List.copyOf(plants);
            if (ground == null || !ground.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                    || plants.isEmpty() || plants.stream().anyMatch(p -> !p.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))
                    || !Double.isFinite(density) || density < 0 || density > 1
                    || edgeClearance < 0 || edgeClearance > 8)
                throw new IllegalArgumentException("CITY_PUBLIC_GREENERY_PRESET_INVALID");
        }
        public static Preset load() {
            Path override = Path.of("config", "geomantia", "city_public_greenery.json");
            try (Reader reader = Files.isRegularFile(override) ? Files.newBufferedReader(override)
                    : new InputStreamReader(Objects.requireNonNull(CityPublicGreeneryPlanner.class.getResourceAsStream(
                    "/data/geomantia/city/public_greenery.json")), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                return new Preset(json.get("ground").getAsString(), json.getAsJsonArray("plants").asList()
                        .stream().map(JsonElement::getAsString).toList(), json.get("density").getAsDouble(),
                        json.get("edgeClearance").getAsInt());
            } catch (IOException e) { throw new IllegalStateException("CITY_PUBLIC_GREENERY_PRESET_UNREADABLE", e); }
        }
    }

    public CityLandUseSurfacePrintPlan append(CityLandUseSurfacePrintPlan source,
            CityDistrictPlanner.Result district, LandUseAreaPlan landUse, Preset preset) {
        return append(source, district, landUse, preset, List.of());
    }
    public CityLandUseSurfacePrintPlan append(CityLandUseSurfacePrintPlan source,
            CityDistrictPlanner.Result district, LandUseAreaPlan landUse, Preset preset,
            List<LandUseAreaPlan.CorridorExclusion> reservations) {
        if (district == null || district.natural().isEmpty()) return source;
        Set<BlockPoint> available = new HashSet<>(district.natural());
        for (var area : landUse.areas()) for (var span : area.memberSpans())
            for (int x = span.minX(); x <= span.maxX(); x++) available.remove(new BlockPoint(x, span.z()));
        Set<BlockPoint> circulation = new HashSet<>();
        for (var f : source.featureCells()) circulation.add(new BlockPoint(f.x(), f.z()));
        available.removeAll(circulation);
        for (var corridor : landUse.corridorExclusions()) available.removeIf(p -> corridor.blockBounds().contains(p.x(), p.z()));
        for (var corridor : reservations) available.removeIf(p -> corridor.blockBounds().contains(p.x(), p.z()));
        List<CityLandUseSurfacePrintPlan.FeatureCell> features = new ArrayList<>(source.featureCells());
        String id = SOURCE + source.cityId();
        long seed = source.cityId().hashCode();
        for (var point : available.stream().sorted(Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x)).toList()) {
            features.add(cell(id, point, preset.ground(), 0, CityLandUseSurfacePrintPlan.FeatureKind.GREEN_GROUND));
            boolean edge = false;
            for (int dz = -preset.edgeClearance(); dz <= preset.edgeClearance() && !edge; dz++)
                for (int dx = -preset.edgeClearance(); dx <= preset.edgeClearance(); dx++)
                    if (!available.contains(new BlockPoint(point.x() + dx, point.z() + dz))) { edge = true; break; }
            long random = mix(seed ^ ((long) point.x() << 32) ^ (point.z() & 0xffffffffL));
            if (!edge && (random >>> 11) * 0x1.0p-53 < preset.density())
                features.add(cell(id, point, preset.plants().get(Math.floorMod(mix(random), preset.plants().size())),
                        1, CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT));
        }
        features.sort(Comparator.comparingInt(CityLandUseSurfacePrintPlan.FeatureCell::z)
                .thenComparingInt(CityLandUseSurfacePrintPlan.FeatureCell::x)
                .thenComparingInt(CityLandUseSurfacePrintPlan.FeatureCell::surfaceOffset));
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(
                source.schema(), source.cityId(), source.sourceLandUsePlanHash(), "", source.areas(),
                source.sharedBoundarySpans(), features, source.materialField()));
    }
    private static CityLandUseSurfacePrintPlan.FeatureCell cell(String id, BlockPoint p, String block, int offset,
                                                                CityLandUseSurfacePrintPlan.FeatureKind kind) {
        return new CityLandUseSurfacePrintPlan.FeatureCell(id, p.x(), p.z(), block, offset, kind,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE);
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
