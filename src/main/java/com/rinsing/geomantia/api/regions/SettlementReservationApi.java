package com.rinsing.geomantia.api.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Shared, atomic occupancy for procedural settlements and adventure sites. Bounds include safety margins. */
@Mod.EventBusSubscriber(modid="geomantia", bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class SettlementReservationApi {
    public record Reservation(String id,String dimension,String kind,RegionBounds bounds) {}
    private static final Map<MinecraftServer,Store> STORES=new ConcurrentHashMap<>();
    private SettlementReservationApi() {}
    @SubscribeEvent public static void start(ServerStartingEvent e) { initialize(e.getServer()); }
    @SubscribeEvent public static void stop(ServerStoppedEvent e) { STORES.remove(e.getServer()); }
    public static void initialize(MinecraftServer server) {
        if(!server.isSameThread()) throw new IllegalStateException("Initialize reservations on server thread");
        STORES.computeIfAbsent(server,s->s.overworld().getDataStorage().computeIfAbsent(Store::load,Store::new,"geomantia_spatial_reservations"));
    }
    /** Thread-safe; false means a competing placement already owns part of this envelope. */
    public static boolean reserve(MinecraftServer server,Reservation value) {
        if(!Set.of("city","suburb","village","road","adventure").contains(value.kind()) || value.id().isBlank()) throw new IllegalArgumentException("Invalid reservation");
        Store store=STORES.get(server); if(store==null) return false;
        return store.reserve(value);
    }
    public static List<Reservation> snapshot(MinecraftServer server) {
        Store store=STORES.get(server);return store==null?List.of():store.snapshot();
    }
    public static List<Reservation> settlements(MinecraftServer server) {
        Store store=STORES.get(server);return store==null?List.of():store.settlements;
    }
    public static boolean intersects(MinecraftServer server,String dimension,RegionBounds bounds,String exceptId) {
        Store store=STORES.get(server);return store!=null && store.intersects(dimension,bounds,exceptId);
    }
    /** Release only a failed, unmaterialized attempt, never a completed settlement/site. */
    public static void releaseFailed(MinecraftServer server,String id) { Store store=STORES.get(server);if(store!=null) store.remove(id); }
    private static boolean overlap(RegionBounds a,RegionBounds b) {return a.minX()<=b.maxX() && a.maxX()>=b.minX() && a.minZ()<=b.maxZ() && a.maxZ()>=b.minZ();}
    static final class Store extends SavedData {
        private final Map<String,Reservation> values=new TreeMap<>();
        private volatile List<Reservation> settlements=List.of();
        private void refreshSettlements() {settlements=values.values().stream().filter(r->!r.kind().equals("adventure")).toList();}
        synchronized boolean reserve(Reservation r) {
            Reservation old=values.get(r.id());if(old!=null)return old.equals(r);
            if(intersects(r.dimension(),r.bounds(),r.id()))return false;
            values.put(r.id(),r);if(!r.kind().equals("adventure"))refreshSettlements();setDirty();return true;
        }
        synchronized List<Reservation> snapshot() {return List.copyOf(values.values());}
        synchronized boolean intersects(String dimension,RegionBounds bounds,String except) {
            for(var r:values.values()) if(!r.id().equals(except) && r.dimension().equals(dimension) && overlap(r.bounds(),bounds))return true;
            return false;
        }
        synchronized void remove(String id) {if(values.remove(id)!=null){refreshSettlements();setDirty();}}
        @Override public synchronized CompoundTag save(CompoundTag tag) {
            ListTag list=new ListTag();
            for(var r:values.values()) {CompoundTag v=new CompoundTag();v.putString("id",r.id());v.putString("dimension",r.dimension());v.putString("kind",r.kind());
                v.putIntArray("bounds",new int[]{r.bounds().minX(),r.bounds().minZ(),r.bounds().maxX(),r.bounds().maxZ()});list.add(v);}
            tag.put("reservations",list);return tag;
        }
        static Store load(CompoundTag tag) {
            Store store=new Store();for(var t:tag.getList("reservations",Tag.TAG_COMPOUND)) {
                CompoundTag v=(CompoundTag)t;int[] b=v.getIntArray("bounds");if(b.length!=4)throw new IllegalArgumentException("Invalid persisted reservation");
                Reservation r=new Reservation(v.getString("id"),v.getString("dimension"),v.getString("kind"),new RegionBounds(b[0],b[1],b[2],b[3]));
                if(store.values.putIfAbsent(r.id(),r)!=null)throw new IllegalArgumentException("Duplicate persisted reservation");
            }store.refreshSettlements();return store;
        }
    }
}
