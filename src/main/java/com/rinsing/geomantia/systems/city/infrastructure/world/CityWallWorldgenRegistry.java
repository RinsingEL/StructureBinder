package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.CityWallChunkPlan;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable plans, first-FEATURES owner writes only. This registry never requests chunks. */
public final class CityWallWorldgenRegistry {
    private record Key(String dimension,String run,String city) {}
    private record Active(Key key,String hash,JsonObject plan,CityWallModuleConfig.Loaded modules,Set<CityWallChunkPlan.Owner> owners) {}
    private static final Map<Key,Active> ACTIVE=new ConcurrentHashMap<>();
    private static final Map<String,JsonObject> COMPLETED=new ConcurrentHashMap<>();
    private static final Map<ChunkAccess,Set<String>> OBSERVED=Collections.synchronizedMap(new WeakHashMap<>());
    private static Path root;

    public static synchronized void load(Path world) {
        ACTIVE.clear();COMPLETED.clear();OBSERVED.clear();root=world.resolve("geomantia_city_masks");
        try {
            Path plans=root.resolve("active_city_wall_plans.json");
            if(Files.isRegularFile(plans)) for(var value:read(plans).getAsJsonArray("plans")) {
                var item=value.getAsJsonObject();var plan=item.getAsJsonObject("plan");
                var key=new Key(item.get("dimension").getAsString(),item.get("runId").getAsString(),item.get("cityId").getAsString());
                ACTIVE.put(key,active(key,plan));
            }
            Path ledger=root.resolve("city_wall_worldgen_ledger.json");
            if(Files.isRegularFile(ledger)) for(var entry:read(ledger).getAsJsonObject("owners").entrySet()) COMPLETED.put(entry.getKey(),entry.getValue().getAsJsonObject());
        } catch(IOException|RuntimeException failure) { throw new IllegalStateException("WALL_WORLDGEN_RESTORE_FAILED",failure); }
    }
    public static synchronized JsonObject activate(Path world,String dimension,String run,String city,JsonObject plan) throws IOException {
        if(root==null)load(world);
        Key key=new Key(dimension,run,city);String hash=hash(plan);
        Active prior=ACTIVE.get(key);
        if(prior==null || !prior.hash.equals(hash)) {
            Active next=active(key,plan.deepCopy());
            Map<Key,Active> proposed=new HashMap<>(ACTIVE);proposed.put(key,next);
            persistPlans(proposed);ACTIVE.put(key,next);
        }
        return status(ACTIVE.get(key));
    }
    private static Active active(Key key,JsonObject plan) throws IOException {
        if(!"chunk_worldgen".equals(string(plan,"placementMode")))throw new IOException("WALL_WORLDGEN_PLAN_REQUIRED");
        String conflict=CityWallPlacementBackend.geometryConflict(plan);
        if(!conflict.isEmpty())throw new IOException(conflict);
        return new Active(key,hash(plan),plan,CityWallModuleConfig.fromFrozen(plan),CityWallChunkPlan.owners(plan));
    }
    public static void apply(WorldGenLevel world,ChunkAccess chunk) {
        String dimension=world.getLevel().dimension().location().toString();
        var owner=new CityWallChunkPlan.Owner(chunk.getPos().x,chunk.getPos().z);
        for(Active active:ACTIVE.values()) {
            if(!active.key.dimension.equals(dimension)||!active.owners.contains(owner))continue;
            String id=ownerId(active,owner);
            synchronized(chunk) {
                synchronized(OBSERVED) { if(OBSERVED.getOrDefault(chunk,Set.of()).contains(id))continue; }
                // A disk ledger never suppresses regeneration of a chunk lost before its save completed.
                JsonObject report=new CityWallPlacementBackend().executeFragment(world,
                        CityWallChunkPlan.fragment(active.plan,owner),owner.bounds(),active.modules);
                if(!report.get("ok").getAsBoolean())
                    org.slf4j.LoggerFactory.getLogger(CityWallWorldgenRegistry.class).error("Wall owner failed: {} {},{} {}",active.key.city,owner.x(),owner.z(),report);
                report.addProperty("cityId",active.key.city);report.addProperty("planHash",active.hash);
                report.addProperty("chunkX",owner.x());report.addProperty("chunkZ",owner.z());
                try { record(id,report); }
                catch(IOException failure){throw new IllegalStateException("WALL_OWNER_LEDGER_WRITE_FAILED",failure);}
                synchronized(OBSERVED){OBSERVED.computeIfAbsent(chunk,c->new HashSet<>()).add(id);}
            }
        }
    }
    private static synchronized void record(String id,JsonObject report) throws IOException {
        COMPLETED.put(id,report);
        JsonObject entries=new JsonObject();new TreeMap<>(COMPLETED).forEach(entries::add);
        JsonObject ledger=new JsonObject();ledger.addProperty("schema","city_wall_worldgen_ledger");ledger.add("owners",entries);
        write(root.resolve("city_wall_worldgen_ledger.json"),ledger);
    }
    private static JsonObject status(Active active) {
        long observed=active.owners.stream().filter(o->COMPLETED.containsKey(ownerId(active,o))).count();
        long done=active.owners.stream().map(o->COMPLETED.get(ownerId(active,o))).filter(Objects::nonNull)
                .filter(r->r.get("ok").getAsBoolean()).count();
        JsonObject result=new JsonObject();result.addProperty("ok",true);result.addProperty("status","activated");
        result.addProperty("reasonCode","CITY_WALL_WORLDGEN_ACTIVE");result.addProperty("planHash",active.hash);
        result.addProperty("totalOwnerChunks",active.owners.size());result.addProperty("observedOwnerChunks",done);
        result.addProperty("failedOwnerChunks",observed-done);
        result.addProperty("pendingOwnerChunks",active.owners.size()-observed);result.addProperty("wallComplete",done==active.owners.size());
        result.addProperty("placementMode","chunk_worldgen");return result;
    }
    private static String ownerId(Active a,CityWallChunkPlan.Owner o){return a.key.dimension+"|"+a.key.run+"|"+a.key.city+"|"+a.hash+"|"+o.x()+","+o.z();}
    private static void persistPlans(Map<Key,Active> plans) throws IOException {
        JsonArray entries=new JsonArray();for(Active active:plans.values()) {
            JsonObject item=new JsonObject();item.addProperty("dimension",active.key.dimension);item.addProperty("runId",active.key.run);item.addProperty("cityId",active.key.city);item.add("plan",active.plan);entries.add(item);
        }
        JsonObject document=new JsonObject();document.addProperty("schema","city_wall_worldgen_registry");document.add("plans",entries);write(root.resolve("active_city_wall_plans.json"),document);
    }
    private static String hash(JsonObject plan) { try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(plan.toString().getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    private static String string(JsonObject j,String key){return j.has(key)?j.get(key).getAsString():"";}
    private static JsonObject read(Path path)throws IOException{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    private static void write(Path path,JsonObject value)throws IOException {
        Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"wall-",".tmp");
        try {Files.writeString(temp,value.toString());try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temp);}
    }
}
