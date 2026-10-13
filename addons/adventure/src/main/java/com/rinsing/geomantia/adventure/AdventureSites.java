package com.rinsing.geomantia.adventure;

import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Quest consumers must distinguish registered starts from completed chunk materialization. */
public final class AdventureSites extends SavedData {
    public record Site(String id,String dimension,String structure,String region,int tier,int x,int y,int z,
                       int minX,int minZ,int maxX,int maxZ,boolean complete,Set<Long> completedChunks) {
        public Site {completedChunks=Set.copyOf(completedChunks);}
    }
    private final Map<String,Site> sites=new TreeMap<>();
    public record PendingChunk(String dimension,int x,int z) {}
    private final Map<PendingChunk,Set<String>> pending=new HashMap<>();
    private final ArrayDeque<PendingChunk> polling=new ArrayDeque<>();
    private void index(Site site) {
        if(site.complete())return;
        for(int z=Math.floorDiv(site.minZ(),16);z<=Math.floorDiv(site.maxZ(),16);z++)for(int x=Math.floorDiv(site.minX(),16);x<=Math.floorDiv(site.maxX(),16);x++) {
            long key=((long)x<<32)^(z&0xffffffffL);if(site.completedChunks().contains(key))continue;
            var chunk=new PendingChunk(site.dimension(),x,z);
            var ids=pending.get(chunk);if(ids==null){ids=new HashSet<>();pending.put(chunk,ids);polling.add(chunk);}ids.add(site.id());
        }
    }
    /** Bounded, rotating inspection of loaded chunks, independent of total site count. */
    public synchronized List<PendingChunk> pendingChunks(int limit) {
        List<PendingChunk> result=new ArrayList<>();int scanned=0,size=polling.size();
        while(result.size()<limit && scanned++<size){var chunk=polling.remove();if(pending.containsKey(chunk)){result.add(chunk);polling.add(chunk);}}
        return result;
    }
    public synchronized List<Site> snapshot() {return List.copyOf(sites.values());}
    public synchronized void register(Site site) {if(sites.putIfAbsent(site.id(),site)==null){index(site);setDirty();}}
    public synchronized void completedChunk(String dimension,int cx,int cz) {
        long key=((long)cx<<32)^(cz&0xffffffffL);
        var ids=pending.remove(new PendingChunk(dimension,cx,cz));if(ids==null)return;
        for(String id:ids) {
            Site s=sites.get(id);if(s.complete() || !s.dimension().equals(dimension) || cx<Math.floorDiv(s.minX(),16) || cx>Math.floorDiv(s.maxX(),16) || cz<Math.floorDiv(s.minZ(),16) || cz>Math.floorDiv(s.maxZ(),16))continue;
            Set<Long> chunks=new HashSet<>(s.completedChunks());if(!chunks.add(key))continue;
            long count=(long)(Math.floorDiv(s.maxX(),16)-Math.floorDiv(s.minX(),16)+1)*(Math.floorDiv(s.maxZ(),16)-Math.floorDiv(s.minZ(),16)+1);
            sites.put(id,new Site(s.id(),s.dimension(),s.structure(),s.region(),s.tier(),s.x(),s.y(),s.z(),s.minX(),s.minZ(),s.maxX(),s.maxZ(),chunks.size()==count,chunks));setDirty();
        }
    }
    @Override public synchronized CompoundTag save(CompoundTag tag) {
        ListTag list=new ListTag();for(Site s:sites.values()) {
            CompoundTag v=new CompoundTag();v.putString("id",s.id());v.putString("dimension",s.dimension());v.putString("structure",s.structure());v.putString("region",s.region());v.putInt("tier",s.tier());
            v.putIntArray("position",new int[]{s.x(),s.y(),s.z()});v.putIntArray("bounds",new int[]{s.minX(),s.minZ(),s.maxX(),s.maxZ()});v.putBoolean("complete",s.complete());v.putLongArray("completedChunks",new ArrayList<>(s.completedChunks()));list.add(v);
        }tag.put("sites",list);return tag;
    }
    public static AdventureSites load(CompoundTag tag) {
        AdventureSites result=new AdventureSites();for(var t:tag.getList("sites",Tag.TAG_COMPOUND)) {
            var v=(CompoundTag)t;int[] p=v.getIntArray("position"),b=v.getIntArray("bounds");if(p.length!=3 || b.length!=4)throw new IllegalArgumentException("Invalid adventure site");
            Set<Long> chunks=new HashSet<>();for(long c:v.getLongArray("completedChunks"))chunks.add(c);
            Site s=new Site(v.getString("id"),v.getString("dimension"),v.getString("structure"),v.getString("region"),v.getInt("tier"),p[0],p[1],p[2],b[0],b[1],b[2],b[3],v.getBoolean("complete"),chunks);
            if(result.sites.putIfAbsent(s.id(),s)!=null)throw new IllegalArgumentException("Duplicate adventure site");
        }result.sites.values().forEach(result::index);return result;
    }
}
