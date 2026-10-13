package com.rinsing.geomantia.adventure;

import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

/** Five author files form one validated configuration; never partially activate a bundle. */
public record AdventureConfig(Distribution distribution, Map<String,MobPool> mobs, Map<String,StructurePool> structures,
                              Settlements settlements, Controls controls, String fingerprint) {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ID=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public record Selection(List<String> mobs,List<String> structures) {}
    public record Tier(Selection land,Selection nearSea) {}
    public record Distribution(int version,List<Tier> tiers,Selection ocean) {}
    public record Conditions(Set<String> biomes,int minY,int maxY) {
        public boolean allows(String biome,int y) { return y>=minY && y<=maxY && (biomes.isEmpty() || biomes.contains(biome)); }
    }
    public record MobEntry(String entity,int weight,int minCount,int maxCount,Conditions conditions) {}
    public record MobPool(String id,double chance,int cooldownTicks,int nearbyLimit,int nearbyRadius,List<MobEntry> entries) {}
    public record MobFile(int version,List<MobPool> pools) {}
    public record StructureEntry(String structure,int weight,Conditions conditions) {}
    public record StructurePool(String id,int spacingChunks,int separationChunks,double chance,List<StructureEntry> entries) {}
    public record StructureFile(int version,List<StructurePool> pools) {}
    public record Rule(int bufferBlocks,boolean blockMobs,boolean blockStructures,Set<String> allowedMobs,Set<String> allowedStructures) {}
    public record Zone(String dimension,String kind,int minX,int minZ,int maxX,int maxZ) {}
    public record Settlements(int version,Map<String,Rule> rules,List<Zone> zones) {}
    /** true preserves the original generator; false replaces natural generation in managed geography. */
    public record Controls(int version,Map<String,Boolean> mobs,Map<String,Boolean> structures) {}
    public AdventureConfig { mobs=Map.copyOf(mobs); structures=Map.copyOf(structures); }
    public Selection select(String terrain,int tier) {
        if(terrain.equals("ocean")) return distribution.ocean();
        Tier t=distribution.tiers().get(Math.min(tier,distribution.tiers().size())-1);
        return terrain.equals("land")?t.land():t.nearSea();
    }
    public static AdventureConfig load(Path dir) throws IOException {
        Files.createDirectories(dir);
        String[] names={"distribution.json","mob_pools.json","structure_pools.json","settlement_rules.json","generation_control.json"};
        Map<String,String> texts=new LinkedHashMap<>();
        for(String name:names) {
            Path p=dir.resolve(name);
            if(!Files.exists(p)) try(InputStream in=AdventureConfig.class.getResourceAsStream("/adventure_defaults/"+name)) {
                if(in==null) throw new IOException("Missing bundled "+name);Files.copy(in,p);
            }
            texts.put(name,Files.readString(p));
        }
        return parse(texts);
    }
    public static AdventureConfig parse(Map<String,String> text) {
        try {
            Distribution d=read(text,"distribution.json",Distribution.class,Set.of("version","tiers","ocean"));
            MobFile m=read(text,"mob_pools.json",MobFile.class,Set.of("version","pools"));
            StructureFile s=read(text,"structure_pools.json",StructureFile.class,Set.of("version","pools"));
            Settlements settlements=read(text,"settlement_rules.json",Settlements.class,Set.of("version","rules","zones"));
            Controls controls=read(text,"generation_control.json",Controls.class,Set.of("version","mobs","structures"));
            require(d.version()==1 && m.version()==1 && s.version()==1 && settlements.version()==1 && controls.version()==1,"Unsupported version");
            require(d.tiers()!=null && !d.tiers().isEmpty(),"tiers must not be empty");
            Map<String,MobPool> mobs=new LinkedHashMap<>();Map<String,StructurePool> structures=new LinkedHashMap<>();
            for(MobPool pool:m.pools()) {
                require(pool.id()!=null && !pool.id().isBlank() && mobs.putIfAbsent(pool.id(),pool)==null,"Duplicate/empty mob pool");
                chance(pool.chance());require(pool.cooldownTicks()>=0 && pool.nearbyLimit()>0 && pool.nearbyRadius()>0 && pool.nearbyRadius()<=128,"Invalid mob limits");
                require(!pool.entries().isEmpty(),"Empty mob pool");Set<String> seen=new HashSet<>();
                for(var e:pool.entries()) { id(e.entity());require(seen.add(e.entity()),"Duplicate entity in pool");require(e.weight()>0 && e.weight()<=100000 && e.minCount()>0 && e.maxCount()>=e.minCount() && e.maxCount()<=64,"Invalid mob entry");condition(e.conditions()); }
            }
            for(StructurePool pool:s.pools()) {
                require(pool.id()!=null && !pool.id().isBlank() && structures.putIfAbsent(pool.id(),pool)==null,"Duplicate/empty structure pool");
                chance(pool.chance());require(pool.spacingChunks()>0 && pool.spacingChunks()<=4096 && pool.separationChunks()>=0 && pool.separationChunks()<pool.spacingChunks(),"Invalid structure spacing");
                require(!pool.entries().isEmpty(),"Empty structure pool");Set<String> seen=new HashSet<>();
                for(var e:pool.entries()) { id(e.structure());require(seen.add(e.structure()),"Duplicate structure in pool");require(e.weight()>0 && e.weight()<=100000,"Invalid structure weight");condition(e.conditions()); }
            }
            List<Selection> selections=new ArrayList<>();selections.add(d.ocean());for(var t:d.tiers()) {selections.add(t.land());selections.add(t.nearSea());}
            for(var v:selections) {require(v!=null,"Missing terrain selection");for(String p:v.mobs()) require(mobs.containsKey(p),"Unknown mob pool "+p);for(String p:v.structures()) require(structures.containsKey(p),"Unknown structure pool "+p);}
            controls.mobs().forEach((key,value)->{id(key);require(value!=null,"Missing mob control");});
            controls.structures().forEach((key,value)->{id(key);require(value!=null,"Missing structure control");});
            for(var pool:mobs.values()) for(var e:pool.entries()) require(controls.mobs().containsKey(e.entity()),"Mob missing generation control: "+e.entity());
            for(var pool:structures.values()) for(var e:pool.entries()) require(controls.structures().containsKey(e.structure()),"Structure missing generation control: "+e.structure());
            for(var e:settlements.rules().entrySet()) {require(Set.of("city","suburb","village","road").contains(e.getKey()),"Unknown settlement kind");var r=e.getValue();require(r.bufferBlocks()>=0 && r.bufferBlocks()<=4096,"Invalid settlement buffer");r.allowedMobs().forEach(AdventureConfig::id);r.allowedStructures().forEach(AdventureConfig::id);}
            for(var z:settlements.zones()) {id(z.dimension());require(settlements.rules().containsKey(z.kind()) && z.minX()<=z.maxX() && z.minZ()<=z.maxZ(),"Invalid settlement zone");}
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new TreeMap<>(text).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new AdventureConfig(d,mobs,structures,settlements,controls,digest);
        } catch(Exception e) {throw new IllegalArgumentException("Invalid adventure configuration: "+e.getMessage(),e);}
    }
    private static <T> T read(Map<String,String> texts,String name,Class<T> type,Set<String> keys) {
        JsonObject object=JsonParser.parseString(Objects.requireNonNull(texts.get(name),name)).getAsJsonObject();
        for(String key:object.keySet()) require(keys.contains(key),name+": unknown field "+key);
        validateShape(object,type,name);
        return GSON.fromJson(object,type);
    }
    private static void validateShape(JsonElement value,java.lang.reflect.Type type,String path) {
        require(value!=null && !value.isJsonNull(),path+": required value missing");
        if(type instanceof java.lang.reflect.ParameterizedType p) {
            var raw=p.getRawType();var args=p.getActualTypeArguments();
            if(raw==Map.class) {require(value.isJsonObject(),path+": expected object");for(var e:value.getAsJsonObject().entrySet())validateShape(e.getValue(),args[1],path+"."+e.getKey());}
            else {require(value.isJsonArray(),path+": expected array");int i=0;for(var v:value.getAsJsonArray())validateShape(v,args[0],path+"["+(i++)+"]");}
        } else if(type instanceof Class<?> c && c.isRecord()) {
            require(value.isJsonObject(),path+": expected object");var obj=value.getAsJsonObject();Set<String> keys=new HashSet<>();
            for(var component:c.getRecordComponents()){keys.add(component.getName());validateShape(obj.get(component.getName()),component.getGenericType(),path+"."+component.getName());}
            for(String key:obj.keySet())require(keys.contains(key),path+": unknown field "+key);
        } else {
            require(value.isJsonPrimitive(),path+": expected primitive");var v=value.getAsJsonPrimitive();
            if(type==String.class)require(v.isString(),path+": expected string");
            else if(type==boolean.class || type==Boolean.class)require(v.isBoolean(),path+": expected boolean");
            else {require(v.isNumber(),path+": expected number");if(type==int.class)v.getAsBigDecimal().intValueExact();else require(Double.isFinite(v.getAsDouble()),path+": nonfinite number");}
        }
    }
    private static void condition(Conditions c) {require(c!=null && c.biomes()!=null && c.minY()<=c.maxY(),"Invalid conditions");c.biomes().forEach(AdventureConfig::id);}
    private static void id(String s) {require(s!=null && ID.matcher(s).matches(),"Invalid resource ID: "+s);}
    private static void chance(double d) {require(Double.isFinite(d) && d>=0 && d<=1,"Invalid chance");}
    private static void require(boolean b,String message) {if(!b) throw new IllegalArgumentException(message);}
}
