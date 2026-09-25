package com.rinsing.geomantia.systems.realm_planning.application.access;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;

/** One survey identity per save. Re-entry reads sealed output; interruptions reuse the same run. */
public final class WorldEntrySurvey {
    @FunctionalInterface public interface Scanner { void scan(String runId) throws Exception; }
    public static Path prepare(Path root,long seed,int radius,Scanner scanner) throws Exception {
        Files.createDirectories(root);
        Path pointer=root.getParent().resolve("geomantia_world_entry.json");
        String runId;
        if(Files.isRegularFile(pointer)) {
            JsonObject saved=read(pointer);
            if(!Long.toString(seed).equals(saved.get("worldSeed").getAsString())) throw new IOException("WORLD_ENTRY_SEED_MISMATCH");
            runId=saved.get("runId").getAsString();
        } else {
            Path existing;
            try(var paths=Files.list(root)) {
                existing=paths.filter(Files::isDirectory).filter(p->matching(p,seed))
                        .max(Comparator.comparingLong(WorldEntrySurvey::modified)).orElse(null);
            }
            runId=existing==null?"provider_"+Long.toUnsignedString(seed,16)+"_r"+radius:existing.getFileName().toString();
            JsonObject saved=new JsonObject(); saved.addProperty("schema","geomantia_world_entry.v1");
            saved.addProperty("worldSeed",Long.toString(seed)); saved.addProperty("runId",runId);
            saved.addProperty("planningRadiusBlocks",radius);
            saved.addProperty("status","preparing");
            writeAtomic(pointer,saved);
        }
        if(!runId.matches("[A-Za-z0-9._-]+") || runId.equals(".") || runId.equals("..")) throw new IOException("WORLD_ENTRY_RUN_INVALID");
        Path run=root.resolve(runId);
        if(!sealed(run)) scanner.scan(runId);
        if(!sealed(run)) throw new IOException("WORLD_ENTRY_SURVEY_INCOMPLETE");
        return run;
    }
    public static boolean sealed(Path run) throws IOException {
        for(String name:List.of("world_survey_manifest.json","world_survey_context.json","world_feature_grid.json","world_patch_map.json","w_manifest.json"))
            if(!Files.isRegularFile(run.resolve(name))) return false;
        try {
            var manifest=read(run.resolve("world_survey_manifest.json"));
            var context=read(run.resolve("world_survey_context.json"));
            var exported=read(run.resolve("w_manifest.json"));
            return samplesSealed(run) && context.has("sealed") && context.get("sealed").getAsBoolean()
                    && exported.has("sealed") && exported.get("sealed").getAsBoolean()
                    && manifest.get("configHash").equals(exported.get("configHash"));
        } catch(RuntimeException interruptedWrite) { return false; }
    }
    public static boolean samplesSealed(Path run) throws IOException {
        if(!Files.isRegularFile(run.resolve("world_survey_manifest.json")) || !Files.isRegularFile(run.resolve("world_feature_grid.json"))) return false;
        try {
            var manifest=read(run.resolve("world_survey_manifest.json"));
            var grid=read(run.resolve("world_feature_grid.json"));
            return "sealed".equals(manifest.get("status").getAsString()) && manifest.has("configHash")
                    && manifest.get("configHash").equals(grid.get("configHash"));
        } catch(RuntimeException interruptedWrite) { return false; }
    }
    public static Path savedRun(Path root) throws IOException {
        Path pointer=root.toAbsolutePath().normalize().getParent().resolve("geomantia_world_entry.json");
        if(!Files.isRegularFile(pointer)) return null;
        String id=read(pointer).get("runId").getAsString();
        if(!id.matches("[A-Za-z0-9._-]+") || id.equals(".") || id.equals("..")) throw new IOException("WORLD_ENTRY_RUN_INVALID");
        return root.resolve(id);
    }
    public static int savedRadius(Path root,int fallback) throws IOException {
        Path pointer=root.getParent().resolve("geomantia_world_entry.json");
        if(!Files.isRegularFile(pointer)) return fallback;
        var saved=read(pointer);
        return saved.has("planningRadiusBlocks")?saved.get("planningRadiusBlocks").getAsInt():fallback;
    }
    public static void markReady(Path root) throws IOException {
        Path pointer=root.getParent().resolve("geomantia_world_entry.json");
        var saved=read(pointer); saved.addProperty("status","ready"); writeAtomic(pointer,saved);
    }
    private static boolean matching(Path run,long seed) {
        try {
            var file=run.resolve("world_survey_manifest.json");
            if(!Files.isRegularFile(file)) return false;
            var config=read(file).getAsJsonObject("config");
            return config!=null && Long.toString(seed).equals(config.get("worldSeed").getAsString())
                    && "minecraft:overworld".equals(config.get("dimensionId").getAsString());
        } catch(IOException|RuntimeException invalid) { return false; }
    }
    private static long modified(Path run) { try { return Files.getLastModifiedTime(run.resolve("world_survey_manifest.json")).toMillis(); } catch(IOException e) { return 0; } }
    public static JsonObject read(Path file) throws IOException { return JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
    public static void writeAtomic(Path file,JsonObject value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp=Files.createTempFile(file.getParent(),"world-entry-",".tmp");
        try {
            Files.writeString(temp,value.toString());
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private WorldEntrySurvey() {}
}
