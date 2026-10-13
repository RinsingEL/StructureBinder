package com.rinsing.geomantia.adventure;

import com.rinsing.geomantia.api.regions.*;
import com.google.gson.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;

/** Immutable geography/tier lookup. All disk IO belongs to initialization or explicit save. */
public final class AdventureWorld {
    public final WorldRegionSnapshot geography;
    public final AdventureConfig config;
    public final Map<String,Integer> tiers;
    public record Location(String region,String terrain,int tier,AdventureConfig.Selection selection) {}
    public AdventureWorld(WorldRegionSnapshot geography,AdventureConfig config,Path tierFile) throws IOException {
        this.geography=geography;this.config=config;
        Map<String,Integer> saved=new TreeMap<>();
        if(Files.isRegularFile(tierFile)) {
            var json=JsonParser.parseString(Files.readString(tierFile)).getAsJsonObject();
            if(!geography.identity().equals(json.get("identity").getAsString()))throw new IOException("Adventure geography changed; use a new world/planning run");
            if(config.distribution().tiers().size()==json.get("maximum").getAsInt()) {
                for(var e:json.getAsJsonObject("tiers").entrySet())saved.put(e.getKey(),e.getValue().getAsInt());
                if(!saved.keySet().equals(geography.continents().keySet()) || saved.get(geography.initialContinent())!=1
                        || saved.values().stream().anyMatch(v->v<1 || v>config.distribution().tiers().size()))throw new IOException("Invalid saved adventure tiers");
            }
        }
        tiers=saved.isEmpty()?ContinentTiers.compute(geography.continents(),geography.initialContinent(),config.distribution().tiers().size()):Map.copyOf(saved);
        JsonObject json=new JsonObject();json.addProperty("version",1);json.addProperty("identity",geography.identity());json.addProperty("maximum",config.distribution().tiers().size());
        json.add("tiers",new Gson().toJsonTree(new TreeMap<>(tiers)));
        Files.createDirectories(tierFile.getParent());Path temporary=Files.createTempFile(tierFile.getParent(),"tiers-",".tmp");
        try {Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(json));
            try {Files.move(temporary,tierFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,tierFile,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    public Location at(String dimension,int x,int z) {
        if(!geography.dimension().equals(dimension))return null;
        var c=geography.at(x,z);if(c==null)return null;
        int tier=c.ocean()?0:tiers.get(c.region());String terrain=c.ocean()?"ocean":c.land()?"land":"nearSea";
        return new Location(c.region(),terrain,tier,config.select(terrain,tier));
    }
    public static long hash(long seed,String salt,int x,int z) {
        long h=seed^((long)salt.hashCode()<<32)^((long)x*0x9e3779b97f4a7c15L)^((long)z*0xc2b2ae3d27d4eb4fL);
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;return h^(h>>>31);
    }
    public record Candidate(String pool,int chunkX,int chunkZ,long seed) {}
    public static Candidate candidate(long seed,AdventureConfig.StructurePool pool,int cellX,int cellZ) {
        long mixed=hash(seed,pool.id(),cellX,cellZ);var random=new Random(mixed);
        int range=pool.spacingChunks()-pool.separationChunks();
        int x=cellX*pool.spacingChunks()+random.nextInt(range),z=cellZ*pool.spacingChunks()+random.nextInt(range);
        return random.nextDouble()<pool.chance()?new Candidate(pool.id(),x,z,mixed):null;
    }
    public Candidate candidateAt(long seed,AdventureConfig.StructurePool pool,int cx,int cz) {
        Candidate c=candidate(seed,pool,Math.floorDiv(cx,pool.spacingChunks()),Math.floorDiv(cz,pool.spacingChunks()));
        if(c==null || c.chunkX()!=cx || c.chunkZ()!=cz)return null;
        // Matérialization order must not decide spacing. Competing pools use deterministic priority thinning.
        for(var other:config.structures().values()) {
            int radius=Math.min(pool.separationChunks(),other.separationChunks());
            int minX=Math.floorDiv(cx-radius,other.spacingChunks()),maxX=Math.floorDiv(cx+radius,other.spacingChunks());
            int minZ=Math.floorDiv(cz-radius,other.spacingChunks()),maxZ=Math.floorDiv(cz+radius,other.spacingChunks());
            for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
                Candidate n=candidate(seed,other,x,z);if(n==null || n.equals(c))continue;
                var loc=at(geography.dimension(),n.chunkX()*16+8,n.chunkZ()*16+8);
                if(loc==null || !loc.selection().structures().contains(other.id()))continue;
                long dx=(long)n.chunkX()-cx,dz=(long)n.chunkZ()-cz;
                if(dx*dx+dz*dz < (long)radius*radius && priority(n,c)<0)return null;
            }
        }
        return c;
    }
    private static int priority(Candidate a,Candidate b) {int c=Long.compareUnsigned(a.seed(),b.seed());return c!=0?c:a.pool().compareTo(b.pool());}
}
