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
        if (district == null || district.natural().isEmpty() && district.cores().isEmpty()) return source;
        Set<BlockPoint> available = new HashSet<>(district.natural());
        // Designed districts also own gardens on their shared platform, outside building lots.
        if (!district.cores().isEmpty()) available.addAll(district.construction());
        for (var area : landUse.areas()) if (district.cores().isEmpty()
                || !area.sourceGroupIds().stream().anyMatch(id -> id.endsWith("::foundation")))
            for (var span : area.memberSpans()) for (int x = span.minX(); x <= span.maxX(); x++) available.remove(new BlockPoint(x, span.z()));
        for (var p : district.structures()) for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)
            available.remove(new BlockPoint(p.x()+dx,p.z()+dz));
        Set<BlockPoint> circulation = new HashSet<>();
        Map<BlockPoint,CityLandUseSurfacePrintPlan.FeatureCell> connectionGround = new HashMap<>();
        for (var f : source.featureCells()) {
            var p=new BlockPoint(f.x(),f.z());
            if(f.kind()==CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_GROUND) {
                connectionGround.put(p,f);available.add(p);continue;
            }
            for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++) circulation.add(new BlockPoint(f.x()+dx,f.z()+dz));
        }
        available.removeAll(circulation);
        for (var corridor : landUse.corridorExclusions()) available.removeIf(p -> corridor.blockBounds().contains(p.x(), p.z()));
        for (var corridor : reservations) available.removeIf(p -> corridor.blockBounds().contains(p.x(), p.z()));
        List<CityLandUseSurfacePrintPlan.FeatureCell> features = new ArrayList<>(source.featureCells().stream()
                .filter(f -> f.kind()!=CityLandUseSurfacePrintPlan.FeatureKind.TERRACE_GROUND
                        || !available.contains(new BlockPoint(f.x(),f.z()))).toList());
        String id = SOURCE + source.cityId();
        Map<BlockPoint,Integer> depths = new HashMap<>();ArrayDeque<BlockPoint> queue=new ArrayDeque<>();
        for(var p:available) if(neighbors(p).stream().anyMatch(q->!available.contains(q))){depths.put(p,1);queue.add(p);}
        while(!queue.isEmpty()){var p=queue.removeFirst();for(var q:neighbors(p))if(available.contains(q)&&!depths.containsKey(q)){
            depths.put(q,depths.get(p)+1);queue.add(q);}}
        Set<BlockPoint> paths=new HashSet<>();
        // Repeatable planting beds with a walkable cross between them, aligned to each district core.
        for(var p:available) if(!district.cores().isEmpty()) {
            var core=district.cores().entrySet().stream().min(Comparator
                    .<Map.Entry<String,com.rinsing.geomantia.systems.city.domain.model.BlockBounds>>comparingLong(e->{
                        long dx=(long)e.getValue().center().x()-p.x(),dz=(long)e.getValue().center().z()-p.z();return dx*dx+dz*dz;})
                    .thenComparing(Map.Entry::getKey)).orElseThrow().getValue().center();
            if(Math.abs(p.x()-core.x())<=2||Math.abs(p.z()-core.z())<=2)paths.add(p);
        }
        for (var point : available.stream().sorted(Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x)).toList()) {
            int depth=depths.get(point);boolean path=paths.contains(point);
            String ground=path?source.materialField().at("accessSurface",point.x(),point.z(),"minecraft:polished_andesite"):preset.ground();
            var original=connectionGround.get(point);
            features.add(original==null?cell(id,point,ground,0,path?CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PATH:CityLandUseSurfacePrintPlan.FeatureKind.GREEN_GROUND)
                    :new CityLandUseSurfacePrintPlan.FeatureCell(original.sourceId(),point.x(),point.z(),ground,0,original.kind(),original.facing(),original.targetSurfaceY()));
            if(path||preset.density()==0)continue;
            boolean hedge=depth==preset.edgeClearance()+1;
            int spacing=Math.max(2,(int)Math.ceil(1/Math.sqrt(preset.density())));
            boolean flower=depth>preset.edgeClearance()+1&&Math.floorMod(point.x(),spacing)==0&&Math.floorMod(point.z(),spacing)==0;
            if(hedge||flower) {
                String block=hedge?source.materialField().at("hedge",point.x(),point.z(),"minecraft:oak_leaves")
                        :preset.plants().get(Math.floorMod(Math.floorDiv(point.x(),spacing)+Math.floorDiv(point.z(),spacing),preset.plants().size()));
                features.add(cell(original==null?id:original.sourceId(),point,block,1,CityLandUseSurfacePrintPlan.FeatureKind.GREEN_PLANT));
            }
        }
        var planted=new CityLandUseSurfacePrintPlan(source.schema(),source.cityId(),source.sourceLandUsePlanHash(),"",
                source.areas(),source.sharedBoundarySpans(),features,source.materialField());
        features=new ArrayList<>(new CityDesignedPublicSpacePlanner().plan(planted,district.publicSpaces(),available,
                preset.density()>0,CityDesignedPublicSpacePlanner.Palette.load()));
        features.sort(Comparator.comparingInt(CityLandUseSurfacePrintPlan.FeatureCell::z)
                .thenComparingInt(CityLandUseSurfacePrintPlan.FeatureCell::x)
                .thenComparingInt(CityLandUseSurfacePrintPlan.FeatureCell::surfaceOffset));
        return new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(
                source.schema(), source.cityId(), source.sourceLandUsePlanHash(), "", source.areas(),
                source.sharedBoundarySpans(), features, source.materialField()));
    }
    private static List<BlockPoint> neighbors(BlockPoint p){return List.of(new BlockPoint(p.x()+1,p.z()),new BlockPoint(p.x()-1,p.z()),new BlockPoint(p.x(),p.z()+1),new BlockPoint(p.x(),p.z()-1));}
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
