package com.rinsing.realmfolk.server;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.infrastructure.world.CityReservationMaskRegistry.PlannedStructure;
import com.rinsing.realmfolk.Realmfolk;
import com.rinsing.realmfolk.life.*;
import com.rinsing.realmfolk.model.Society;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.*;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static com.rinsing.realmfolk.server.SocietyRuntime.*;

/** Facility discovery is bounded to proven, already-loaded buildings. No chunk loads or building edits. */
public final class FacilityRuntime {
    private static final Set<Block> WORK=Set.of(Blocks.BLAST_FURNACE,Blocks.SMOKER,Blocks.CARTOGRAPHY_TABLE,
        Blocks.BREWING_STAND,Blocks.COMPOSTER,Blocks.BARREL,Blocks.FLETCHING_TABLE,Blocks.CAULDRON,
        Blocks.LECTERN,Blocks.STONECUTTER,Blocks.LOOM,Blocks.SMITHING_TABLE,Blocks.GRINDSTONE,
        Blocks.CRAFTING_TABLE,Blocks.FURNACE,Blocks.ANVIL,Blocks.CHIPPED_ANVIL,Blocks.DAMAGED_ANVIL);
    private static final Map<String,JsonObject> CATALOG=new HashMap<>();
    private static final Set<String> INVALID=new HashSet<>();
    private static MinecraftServer owner;
    private static int cursor;
    private static final Map<String,Long> LAST_SCAN=new HashMap<>();
    public static void start(MinecraftServer server){
        owner=server;cursor=0;CATALOG.clear();INVALID.clear();LAST_SCAN.clear();
        LivingBehaviorRuntime.reload(server);
        Path root=FMLPaths.CONFIGDIR.get();
        var files=new TreeSet<Path>();files.add(root.resolve("realmfolk/facilities.json"));
        Path packs=root.resolve("structureTemplate/terrasense");
        try(var dirs=Files.isDirectory(packs)?Files.list(packs):java.util.stream.Stream.<Path>empty()){
            dirs.filter(Files::isDirectory).forEach(d->files.add(d.resolve("realmfolk_facilities.json")));
        }catch(Exception ex){Realmfolk.LOG.warn("Cannot list living facility catalogs",ex);}
        String source=System.getProperty("geomantia.providerPlanningSourceDir","");
        if(!source.isBlank())files.add(Path.of(source).resolve("realmfolk_facilities.json"));
        for(Path file:files)if(Files.isRegularFile(file))try{
            JsonObject rootJson=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(!text(rootJson,"schema","").equals("realmfolk_facility_catalog.v1"))throw new IllegalArgumentException("Unknown facility catalog schema");
            for(var entry:rootJson.getAsJsonArray("templates")){
                var row=entry.getAsJsonObject();String key=text(row,"templateRef","")+"/"+text(row,"templateHash","");
                if(INVALID.contains(key))continue;
                JsonObject old=CATALOG.putIfAbsent(key,row);
                if(old!=null&&!old.equals(row)){CATALOG.remove(key);INVALID.add(key);Realmfolk.LOG.warn("Conflicting facility metadata: {}",key);}
            }
        }catch(Exception ex){Realmfolk.LOG.warn("Invalid living facility catalog {}",file,ex);}
    }
    public static boolean loaded(ServerLevel level,Bounds b){
        for(int x=Math.floorDiv(b.minX()-1,16);x<=Math.floorDiv(b.maxX()+1,16);x++)
            for(int z=Math.floorDiv(b.minZ()-1,16);z<=Math.floorDiv(b.maxZ()+1,16);z++)if(!level.hasChunk(x,z))return false;
        return true;
    }
    private static boolean passable(ServerLevel level,BlockPos p){
        BlockState s=level.getBlockState(p);
        return s.getCollisionShape(level,p).isEmpty()||(s.getBlock() instanceof DoorBlock&&!s.is(Blocks.IRON_DOOR));
    }
    public static boolean standable(ServerLevel level,Point point){
        if(point==null)return false;BlockPos p=pos(point);
        if(!level.hasChunkAt(p)||!passable(level,p)||!passable(level,p.above())||!level.getFluidState(p).isEmpty())return false;
        var shape=level.getBlockState(p.below()).getCollisionShape(level,p.below());
        return !shape.isEmpty()&&shape.max(Direction.Axis.Y)>=.5;
    }
    private static Point approach(ServerLevel level,Point object){
        for(Direction d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,1,-1}){
            Point p=object.offset(d.getStepX(),dy,d.getStepZ());if(standable(level,p))return p;
        }
        return standable(level,object)?object:null;
    }
    public static boolean valid(ServerLevel level,LivingSite site){
        return present(level,site)&&LivingBehaviorRuntime.supported(site)&&standable(level,site.approach);
    }
    private static boolean present(ServerLevel level,LivingSite site){
        if(site==null||site.object==null||!level.hasChunkAt(pos(site.object)))return false;
        BlockState actual=level.getBlockState(pos(site.object));
        if(site.kind.equals("bed")&&site.behaviorId().equals("realmfolk:bed"))return nativeBed(level,pos(site.object),actual);
        if(site.block.equals("minecraft:air"))return !Set.of("bed","seat").contains(site.kind); // Activity locations cannot invent sleeping furniture.
        if(!ForgeRegistries.BLOCKS.getKey(actual.getBlock()).toString().equals(site.block)||!matchesState(actual,site.requiredState))return false;
        for(var part:site.requiredParts){
            BlockPos p=pos(part.position());if(!level.hasChunkAt(p))return false;var other=level.getBlockState(p);
            if(part.sameBlock()&&!other.is(actual.getBlock()))return false;
            if(!part.blocks().isEmpty()&&!matches(other,part.blocks()))return false;
            if(!matchesState(other,part.state()))return false;
        }
        return true;
    }
    public static boolean nativeBed(ServerLevel level,BlockPos head,BlockState state){
        return state.is(BlockTags.BEDS)&&state.hasProperty(BedBlock.PART)&&state.hasProperty(BedBlock.FACING)
                &&state.getValue(BedBlock.PART)==BedPart.HEAD&&completeBed(level,head,state);
    }
    private static boolean completeBed(ServerLevel level,BlockPos head,BlockState state){
        BlockPos foot=head.relative(state.getValue(BedBlock.FACING).getOpposite());
        var other=level.getBlockState(foot);
        return other.is(state.getBlock())&&other.hasProperty(BedBlock.PART)&&other.getValue(BedBlock.PART)==BedPart.FOOT
            &&other.getValue(BedBlock.FACING)==state.getValue(BedBlock.FACING);
    }
    private static String key(String kind,Point p){return kind+":"+p.x()+","+p.y()+","+p.z();}
    private static LivingSite site(ServerLevel level,String kind,Point p){
        var state=level.getBlockState(pos(p));var site=new LivingSite(key(kind,p),kind,p,approach(level,p),ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString());
        if(state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))site.facing=state.getValue(BlockStateProperties.HORIZONTAL_FACING).getName();
        if(kind.equals("bed"))site.adapter="bed";
        return site;
    }
    public static void discover(ServerLevel level,Property property,PlannedStructure placement){
        if(!loaded(level,property.bounds)||property.bounds.volume()>2_000_000)return;
        var sites=new LinkedHashMap<String,LivingSite>();var entrances=new LinkedHashSet<Point>();var configured=new HashSet<String>();
        boolean authored=false;
        if(placement==null&&property.livingSites!=null){
            // Rechecking a furnished building must retain authored room/use/approach metadata and claims.
            property.livingSites.forEach(s->sites.put(s.id,s));
            if(property.entrances!=null)entrances.addAll(property.entrances);
            authored=property.facilitySource!=null&&property.facilitySource.startsWith("export:");
        }
        if(placement!=null)try{
            var plan=placement.templatePlan();String ref=text(plan,"templateRef",placement.templateId());
            var row=CATALOG.get(ref+"/"+text(plan,"templateHash",""));
            if(row!=null){
                var size=row.getAsJsonArray("size");
                var geometry=CityTemplatePlacementGeometry.of(new CityTemplatePlacementGeometry.Size(size.get(0).getAsInt(),size.get(1).getAsInt(),size.get(2).getAsInt()),
                    CityTemplatePlacementGeometry.Rotation.valueOf(placement.rotation()),CityTemplatePlacementGeometry.Mirror.valueOf(text(plan,"mirror","NONE")),List.of());
                if(geometry.transformedSize().width()!=property.bounds.maxX()-property.bounds.minX()+1||geometry.transformedSize().depth()!=property.bounds.maxZ()-property.bounds.minZ()+1)
                    throw new IllegalArgumentException("Facility size disagrees with frozen placement");
                for(var entry:row.getAsJsonArray("facilities")){
                    var f=entry.getAsJsonObject();Point p=transform(f.getAsJsonArray("pos"),geometry,property);
                    String kind=text(f,"kind","");LivingSite site=site(level,kind,p);
                    site.block=text(f,"block",site.block);site.use=text(f,"use","building");site.room=text(f,"room","");site.name=text(f,"name",kind);
                    site.adapter=text(f,"adapter",site.adapter);if(f.has("seatHeight"))site.seatHeight=f.get("seatHeight").getAsDouble();
                    site.authored=true;site.behavior=text(f,"behavior","");site.sourceRule=text(f,"sourceRule","");
                    if(f.has("surfaceHeight"))site.surfaceHeight=f.get("surfaceHeight").getAsDouble();
                    if(f.has("useOffset")){var a=f.getAsJsonArray("useOffset");site.useOffset=transformOffset(new double[]{a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()},geometry,property);}
                    if(f.has("requiredState"))site.requiredState=stateMap(f.getAsJsonObject("requiredState"));
                    if(f.has("requiredParts"))for(var part:f.getAsJsonArray("requiredParts")){
                        var q=part.getAsJsonObject();var selectors=new ArrayList<String>();q.getAsJsonArray("blocks").forEach(v->selectors.add(v.getAsString()));
                        site.requiredParts.add(new LivingSite.Part(transform(q.getAsJsonArray("pos"),geometry,property),selectors,stateMap(q.getAsJsonObject("state")),q.get("sameBlock").getAsBoolean()));
                    }
                    if(f.has("slot")&&!f.get("slot").getAsString().equals("default"))site.id+=":"+f.get("slot").getAsString();
                    if(f.has("approach"))site.approach=transform(f.getAsJsonArray("approach"),geometry,property);
                    if(kind.equals("entrance")){if(site.approach!=null)entrances.add(site.approach);else entrances.add(p);}
                    else sites.put(site.id,site);
                }
                authored=true;property.facilitySource="export:"+text(row,"templateHash","");
            }
        }catch(Exception ex){Realmfolk.LOG.warn("Cannot apply living metadata for {}",property.id,ex);}
        Bounds b=property.bounds;
        // Physical discovery also handles old templates and furnishings edited after placement.
        for(BlockPos p:BlockPos.betweenClosed(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ())){
            var state=level.getBlockState(p);if(state.isAir())continue;String kind="";
            for(FacilityRule rule:LivingBehaviorRuntime.rules())if(matches(state,rule.blocks())&&matchesState(state,rule.state())){
                for(var slot:rule.slots()){
                    LivingSite fresh=site(level,rule.kind(),point(p));if(!slot.id().equals("default"))fresh.id+=":"+slot.id();
                    if(!configured.add(fresh.id))continue; // First matching rule owns a physical slot.
                    fresh.sourceRule=rule.id();fresh.behavior=rule.behavior();fresh.use=rule.use();fresh.surfaceHeight=rule.height();
                    if(!rule.facing().isEmpty())fresh.facing=rule.facing();
                    fresh.useOffset=rotate(slot.offset(),fresh.facing);fresh.requiredState=rule.state();
                    if(slot.approach()!=null){var o=rotate(new double[]{slot.approach()[0],slot.approach()[1],slot.approach()[2]},fresh.facing);fresh.approach=point(p.offset((int)o[0],(int)o[1],(int)o[2]));}
                    for(var part:rule.parts()){var o=rotate(new double[]{part.offset()[0],part.offset()[1],part.offset()[2]},fresh.facing);fresh.requiredParts.add(new LivingSite.Part(point(p.offset((int)o[0],(int)o[1],(int)o[2])),part.blocks(),partState(part.state(),state),part.sameBlock()));}
                    preserveAuthor(fresh,sites.get(fresh.id));sites.put(fresh.id,fresh);
                }
            }
            if(nativeBed(level,p,state))kind="bed";
            else if(WORK.contains(state.getBlock())||state.is(TagKey.create(Registries.BLOCK,new ResourceLocation("realmfolk:workstations"))))kind="work";
            if(!kind.isEmpty()){
                LivingSite s=site(level,kind,point(p));var old=sites.get(s.id);
                boolean hasRule=configured.stream().anyMatch(id->id.equals(s.id)||id.startsWith(s.id+":"));
                if(!hasRule&&(old==null||!old.sourceRule.isEmpty())){preserveAuthor(s,old);sites.put(s.id,s);}
                else if(hasRule&&!configured.contains(s.id))sites.remove(s.id);
            }
            if(state.getBlock() instanceof DoorBlock&&state.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER&&standable(level,point(p)))entrances.add(point(p));
        }
        if(entrances.isEmpty())for(int y=b.minY();y<=Math.min(b.maxY(),b.minY()+4);y++){
            for(int x=b.minX();x<=b.maxX();x++)for(int z:new int[]{b.minZ(),b.maxZ()})if(standable(level,new Point(x,y,z)))entrances.add(new Point(x,y,z));
            for(int z=b.minZ();z<=b.maxZ();z++)for(int x:new int[]{b.minX(),b.maxX()})if(standable(level,new Point(x,y,z)))entrances.add(new Point(x,y,z));
            if(!entrances.isEmpty())break;
        }
        sites.entrySet().removeIf(e->!e.getValue().sourceRule.isEmpty()&&!configured.contains(e.getKey()));
        property.livingSites=new ArrayList<>(sites.values());property.entrances=new ArrayList<>(entrances);
        if(!authored)property.facilitySource="loaded_world";
        verify(level,property);LAST_SCAN.put(property.id,level.getGameTime());dirty(level.getServer());
    }
    private static boolean matches(BlockState state,List<String> selectors){
        for(String id:selectors){
            if(id.startsWith("#")?state.is(TagKey.create(Registries.BLOCK,new ResourceLocation(id.substring(1)))):ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString().equals(id))return true;
        }return false;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static boolean matchesState(BlockState state,Map<String,String> required){
        for(var e:required.entrySet()){
            net.minecraft.world.level.block.state.properties.Property p=state.getBlock().getStateDefinition().getProperty(e.getKey());
            if(p==null||!p.getName(state.getValue(p)).equals(e.getValue()))return false;
        }return true;
    }
    private static Map<String,String> stateMap(JsonObject json){var values=new LinkedHashMap<String,String>();json.entrySet().forEach(e->values.put(e.getKey(),e.getValue().getAsString()));return values;}
    @SuppressWarnings({"rawtypes","unchecked"})
    private static Map<String,String> partState(Map<String,String> conditions,BlockState anchor){
        var result=new LinkedHashMap<String,String>();
        conditions.forEach((key,value)->{
            if(value.startsWith("$")){
                net.minecraft.world.level.block.state.properties.Property property=anchor.getBlock().getStateDefinition().getProperty(value.substring(1));
                result.put(key,property==null?"<missing anchor property>":property.getName(anchor.getValue(property)));
            }else result.put(key,value);
        });return result;
    }
    private static void preserveAuthor(LivingSite fresh,LivingSite previous){
        fresh.inheritAuthorMetadata(previous);
    }
    static double[] rotate(double[] p,String facing){return switch(facing){case "east"->new double[]{-p[2],p[1],p[0]};case "south"->new double[]{-p[0],p[1],-p[2]};case "west"->new double[]{p[2],p[1],-p[0]};default->p.clone();};}
    private static double[] transformOffset(double[] p,CityTemplatePlacementGeometry geometry,Property property){
        var origin=geometry.worldPosition(new BlockPoint(property.bounds.minX(),property.bounds.minZ()),new BlockPoint(0,0));
        var x=geometry.worldPosition(new BlockPoint(property.bounds.minX(),property.bounds.minZ()),new BlockPoint(1,0));
        var z=geometry.worldPosition(new BlockPoint(property.bounds.minX(),property.bounds.minZ()),new BlockPoint(0,1));
        return new double[]{p[0]*(x.x()-origin.x())+p[2]*(z.x()-origin.x()),p[1],p[0]*(x.z()-origin.z())+p[2]*(z.z()-origin.z())};
    }
    public static void invalidateScans(){LAST_SCAN.clear();}
    private static Point transform(JsonArray a,CityTemplatePlacementGeometry geometry,Property p){
        var point=geometry.worldPosition(new BlockPoint(p.bounds.minX(),p.bounds.minZ()),new BlockPoint(a.get(0).getAsInt(),a.get(2).getAsInt()));
        return new Point(point.x(),p.bounds.minY()+a.get(1).getAsInt(),point.z());
    }
    public static void verify(ServerLevel level,Property property){
        if(property.livingSites==null||!loaded(level,property.bounds))return;
        Bounds b=property.bounds;Set<Point> reachable=new HashSet<>();ArrayDeque<Point> pending=new ArrayDeque<>();
        // Interior bedroom doors do not prove that someone can enter from the street.
        // Start at the outside edge near the building's ground datum, never on its roof.
        for(int y=b.minY()-1;y<=Math.min(b.maxY(),b.minY()+4);y++){
            for(int x=b.minX()-1;x<=b.maxX()+1;x++)for(int z:new int[]{b.minZ()-1,b.maxZ()+1}){
                Point p=new Point(x,y,z);if(standable(level,p)&&reachable.add(p))pending.add(p);
            }
            for(int z=b.minZ()-1;z<=b.maxZ()+1;z++)for(int x:new int[]{b.minX()-1,b.maxX()+1}){
                Point p=new Point(x,y,z);if(standable(level,p)&&reachable.add(p))pending.add(p);
            }
        }
        while(!pending.isEmpty()&&reachable.size()<100000){
            Point p=pending.remove();for(Direction d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,1,-1}){
                Point n=p.offset(d.getStepX(),dy,d.getStepZ());
                if(n.x()<b.minX()-1||n.x()>b.maxX()+1||n.z()<b.minZ()-1||n.z()>b.maxZ()+1||n.y()<b.minY()-1||n.y()>b.maxY()+1)continue;
                if(dy>0&&!passable(level,pos(p).above(2)))continue;
                if(dy<0&&!passable(level,pos(n).above(2)))continue;
                if(!reachable.contains(n)&&standable(level,n)){reachable.add(n);pending.add(n);}
            }
        }
        for(LivingSite site:property.livingSites){
            site.present=present(level,site);
            site.available=site.present&&site.approach!=null&&reachable.contains(site.approach)&&standable(level,site.approach)
                &&LivingBehaviorRuntime.supported(site);
        }
        property.slots=FacilityAssignments.beds(property).stream().map(s->s.approach).distinct().toList();
        if(!property.slots.isEmpty())property.home=property.slots.get(0);
        var jobs=FacilityAssignments.jobs(property);if(!jobs.isEmpty())property.work=jobs.get(0).approach;
        var publicPoints=new LinkedHashSet<Point>();
        property.livingSites.stream().filter(s->s.available&&(s.kind.equals("seat")||s.kind.equals("meeting"))).forEach(s->publicPoints.add(s.approach));
        property.entrances.stream().filter(p->reachable.contains(p)&&standable(level,p)).forEach(publicPoints::add);
        property.publicPoints=new ArrayList<>(publicPoints.stream().filter(p->p.y()>=property.bounds.minY()).limit(32).toList());
    }
    public static void refresh(MinecraftServer server){
        if(owner!=server)start(server);var society=society(server);var properties=society.properties.values().stream().filter(p->{
            Town t=society.towns.get(p.town);return t!=null&&t.dimension.equals(dimension(server.overworld()))&&loaded(server.overworld(),p.bounds);
        }).toList();
        // One physical rescan per refresh, oldest/changed building first. Existing sites still get
        // cheap validity checks below. This also detects command/mod-added furniture without events.
        long now=server.overworld().getGameTime();
        Property rescan=properties.stream().filter(p->now-LAST_SCAN.getOrDefault(p.id,now-1200)>=1200)
                .min(Comparator.comparingLong((Property p)->LAST_SCAN.getOrDefault(p.id,Long.MIN_VALUE)).thenComparing(p->p.id)).orElse(null);
        if(rescan!=null)discover(server.overworld(),rescan,null);
        for(int i=0;i<Math.min(4,properties.size());i++){
            Property p=properties.get(Math.floorMod(cursor++,properties.size()));Town t=society.towns.get(p.town);
            if(t==null||!t.dimension.equals(dimension(server.overworld()))||!loaded(server.overworld(),p.bounds))continue;
            if(p==rescan)continue;
            if(p.livingSites!=null)verify(server.overworld(),p);
        }
        if(FacilityAssignments.repair(society))dirty(server);
    }
    public static void changed(ServerLevel level,BlockPos position){
        var world=society(level.getServer());String dimension=dimension(level);
        for(Property p:world.properties.values()){
            Town town=world.towns.get(p.town);
            if(town!=null&&dimension.equals(town.dimension)&&p.bounds.contains(point(position)))
                LAST_SCAN.put(p.id,level.getGameTime()-1200);
        }
    }
    private static String text(JsonObject o,String key,String fallback){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():fallback;}
}
