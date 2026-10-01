package com.rinsing.geomantia.systems.realm_planning;

import com.google.gson.*;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Independent native climate layer. Never rewrites sealed terrain or planning artifacts. */
public final class WorldClimateSurvey {
    public static final String MANIFEST = "world_climate_manifest.json";
    public static final String GRID = "world_climate_grid.json";
    public static final String TEMPERATURE = "world_temperature_preview.png";
    public static final String MOISTURE = "world_moisture_preview.png";
    private static final String SCHEMA = "geomantia_world_climate.v1";
    private static final Gson GSON = new Gson();

    /** An optional climate supplement must never invalidate an already sealed terrain survey. */
    public static JsonObject supplement(Path run, String worldSeed, String dimensionId,
            TerrainSamplingProvenance provenance, TerrainClimateSampler sampler,
            BooleanSupplier cancelled) {
        try {
            JsonObject result = ensure(run, worldSeed, dimensionId, provenance, sampler, cancelled);
            Files.deleteIfExists(run.resolve("world_climate_failure.json"));
            return result;
        } catch (java.util.concurrent.CancellationException stopped) {
            throw stopped;
        } catch (Exception error) {
            JsonObject failure = new JsonObject();
            failure.addProperty("status", "climate_supplement_failed");
            failure.addProperty("createdAt", Instant.now().toString());
            failure.addProperty("error", error.toString());
            failure.addProperty("worldSeed", worldSeed);
            failure.addProperty("dimensionId", dimensionId);
            failure.add("currentProvider", provenance.asJson());
            try {
                failure.add("sourceDetails", sampler.climateSourceDetails());
                failure.add("sealedConfig", read(run.resolve("world_survey_manifest.json")).get("config"));
                Files.writeString(run.resolve("world_climate_failure.json"), GSON.toJson(failure));
            } catch (Exception diagnosticError) { error.addSuppressed(diagnosticError); }
            org.slf4j.LoggerFactory.getLogger(WorldClimateSurvey.class)
                    .warn("Optional climate supplement failed; sealed W remains usable: {}", run, error);
            return null;
        }
    }

    public static synchronized JsonObject ensure(Path run, String worldSeed, String dimensionId,
            TerrainSamplingProvenance provenance, TerrainClimateSampler sampler,
            BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        JsonObject source = read(run.resolve("world_survey_manifest.json"));
        JsonObject config = source.getAsJsonObject("config");
        if (!"sealed".equals(source.get("status").getAsString()))
            throw new IOException("CLIMATE_REQUIRES_SEALED_W");
        if (!worldSeed.equals(config.get("worldSeed").getAsString())
                || !dimensionId.equals(config.get("dimensionId").getAsString()))
            throw new IOException("CLIMATE_WORLD_IDENTITY_MISMATCH");
        String fingerprint = config.getAsJsonObject("terrainProvider").get("sourceFingerprint").getAsString();
        if (!fingerprint.equals(provenance.sourceFingerprint()))
            throw new IOException("CLIMATE_PROVIDER_FINGERPRINT_MISMATCH");
        if (sampler == null || !sampler.climateAvailable()) return null;
        String configHash = source.get("configHash").getAsString();
        JsonObject existing = validManifest(run, configHash, fingerprint);
        if (existing != null) return existing;
        long started = System.nanoTime();
        JsonObject grid = source.getAsJsonObject("grid");
        int width = grid.get("width").getAsInt(), height = grid.get("height").getAsInt();
        int ox = grid.get("originBlockX").getAsInt(), oz = grid.get("originBlockZ").getAsInt();
        int step = config.get("cellStepBlocks").getAsInt();
        if (width <= 0 || height <= 0 || step <= 0) throw new IOException("CLIMATE_GRID_INVALID");
        BufferedImage heat = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage wet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        JsonArray rows = new JsonArray();
        double tmin=Double.POSITIVE_INFINITY,tmax=Double.NEGATIVE_INFINITY;
        double mmin=Double.POSITIVE_INFINITY,mmax=Double.NEGATIVE_INFINITY;
        for (int z=0;z<height;z++) {
            check(cancelled);
            for (int x=0;x<width;x++) {
                int bx=ox+x*step+step/2, bz=oz+z*step+step/2;
                TerrainClimateSample s=Objects.requireNonNull(sampler.sampleClimate(bx,bz));
                JsonArray row=new JsonArray();
                row.add(Math.floorDiv(bx,step)); row.add(Math.floorDiv(bz,step)); row.add(bx); row.add(bz);
                row.add(s.regionTemperature()); row.add(s.regionMoisture());
                row.add(s.temperature()); row.add(s.moisture()); row.add(s.water()); rows.add(row);
                tmin=Math.min(tmin,s.regionTemperature());tmax=Math.max(tmax,s.regionTemperature());
                mmin=Math.min(mmin,s.regionMoisture());mmax=Math.max(mmax,s.regionMoisture());
                heat.setRGB(x,z,s.water()?0x2a60a4:color(s.regionTemperature(),false));
                wet.setRGB(x,z,s.water()?0x2a60a4:color(s.regionMoisture(),true));
            }
        }
        JsonObject data=new JsonObject();
        data.addProperty("schema",SCHEMA);data.addProperty("runId",source.get("runId").getAsString());
        data.addProperty("worldSeed",worldSeed);data.addProperty("dimensionId",dimensionId);
        data.addProperty("configHash",configHash);data.addProperty("sourceFingerprint",fingerprint);
        data.add("grid",grid.deepCopy());data.add("scanBounds",source.get("scanBounds").deepCopy());
        data.addProperty("cellStepBlocks",step);data.addProperty("sampleOffsetBlocks",step/2);
        data.addProperty("sampleCount",rows.size());
        data.addProperty("samplingSemantics","rtf_native_heightmap_cell_center_single_sample");
        data.addProperty("previewTemperatureField","regionTemperature");
        data.addProperty("previewMoistureField","regionMoisture");
        data.addProperty("units","RTF dimensionless climate parameters; not Celsius, rainfall or biome-derived estimates");
        data.addProperty("waterDisplay","masked blue in previews; all water climate values retained in grid");
        JsonArray columns=new JsonArray();
        for(String name:List.of("gridX","gridZ","blockX","blockZ","regionTemperature","regionMoisture","temperature","moisture","water")) columns.add(name);
        data.add("columns",columns);
        JsonObject manifest=data.deepCopy();data.add("cells",rows);
        manifest.addProperty("sealed",true);manifest.addProperty("createdAt",Instant.now().toString());
        manifest.addProperty("regionTemperatureMin",tmin);manifest.addProperty("regionTemperatureMax",tmax);
        manifest.addProperty("regionMoistureMin",mmin);manifest.addProperty("regionMoistureMax",mmax);
        manifest.add("terrainProvider",provenance.asJson());
        Path staging=Files.createTempDirectory(run,"climate-staging-");
        try {
            Files.writeString(staging.resolve(GRID),GSON.toJson(data));
            ImageIO.write(preview(heat,"RTF native temperature",false,ox,oz,step),"png",staging.resolve(TEMPERATURE).toFile());
            ImageIO.write(preview(wet,"RTF native moisture",true,ox,oz,step),"png",staging.resolve(MOISTURE).toFile());
            JsonObject hashes=new JsonObject();
            for(String name:List.of(GRID,TEMPERATURE,MOISTURE)) hashes.addProperty(name,sha(staging.resolve(name)));
            manifest.add("artifactSha256",hashes);
            manifest.addProperty("durationMs",(System.nanoTime()-started)/1_000_000L);
            Files.writeString(staging.resolve(MANIFEST),GSON.toJson(manifest));
            check(cancelled);
            for(String name:List.of(GRID,TEMPERATURE,MOISTURE,MANIFEST)) move(staging.resolve(name),run.resolve(name));
        } finally {
            for(String name:List.of(GRID,TEMPERATURE,MOISTURE,MANIFEST)) Files.deleteIfExists(staging.resolve(name));
            Files.deleteIfExists(staging);
        }
        return manifest;
    }

    public static JsonObject validManifest(Path run,String hash,String fingerprint) {
        try {
            JsonObject m=read(run.resolve(MANIFEST));
            if(!SCHEMA.equals(m.get("schema").getAsString()) || !m.get("sealed").getAsBoolean()
                    || !hash.equals(m.get("configHash").getAsString())
                    || !fingerprint.equals(m.get("sourceFingerprint").getAsString())) return null;
            JsonObject hashes=m.getAsJsonObject("artifactSha256");
            for(String name:List.of(GRID,TEMPERATURE,MOISTURE))
                if(!sha(run.resolve(name)).equals(hashes.get(name).getAsString())) return null;
            return m;
        } catch(IOException|RuntimeException invalid) { return null; }
    }

    private static BufferedImage preview(BufferedImage raw,String title,boolean moisture,int ox,int oz,int step) {
        int scale=Math.max(1,Math.min(4,1024/Math.max(raw.getWidth(),raw.getHeight())));
        int w=raw.getWidth()*scale,h=raw.getHeight()*scale;
        BufferedImage out=new BufferedImage(w+340,Math.max(h+100,520),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=out.createGraphics();
        try {
            g.setColor(new Color(0xf4f3ee));g.fillRect(0,0,out.getWidth(),out.getHeight());
            g.setColor(new Color(0x233042));g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20));
            g.drawString(title,16,27);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));
            g.drawString("W cell centers | step "+step+" blocks | north is -Z",16,50);
            g.drawImage(raw,0,65,w,h,null);
            int y=92;
            for(int i=0;i<5;i++) {
                g.setColor(new Color(color((i+.5)/5,moisture)));g.fillRect(w+15,y-13,20,20);
                g.setColor(new Color(0x233042));g.drawString(String.format(Locale.ROOT,"%.1f - %.1f",i/5.0,(i+1)/5.0),w+45,y+2);y+=34;
            }
            g.setColor(new Color(0x2a60a4));g.fillRect(w+15,y-13,20,20);g.setColor(new Color(0x233042));g.drawString("Water (climate retained in JSON)",w+45,y+2);
            y+=45;
            for(String line:List.of("Field: "+(moisture?"regionMoisture":"regionTemperature"),"RTF raw field, not biome-derived","0..1 parameters; not physical units","X: ["+ox+","+(ox+raw.getWidth()*step)+")","Z: ["+oz+","+(oz+raw.getHeight()*step)+")","Final temperature/moisture also saved")) {g.drawString(line,w+15,y);y+=25;}
        } finally {g.dispose();}
        return out;
    }
    private static int color(double value,boolean moisture) {
        int i=Math.max(0,Math.min(4,(int)(value*5)));
        return (moisture?new int[]{0xd6b786,0xc4c88f,0x9abd8b,0x63a687,0x287f79}:new int[]{0xa6bce7,0x76b7cf,0x8cbd8a,0xe3c579,0xcc6555})[i];
    }
    private static JsonObject read(Path p) throws IOException {return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    private static void move(Path a,Path b) throws IOException {
        try {Files.move(a,b,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e) {Files.move(a,b,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static String sha(Path p) throws IOException {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("WORLD_CLIMATE_CANCELLED");
    }
    private WorldClimateSurvey() {}
}
