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
    private static JsonArray RULES=new JsonArray();
    private static MinecraftServer owner;
    private static int cursor;
    public static void start(MinecraftServer server){
        owner=server;cursor=0;CATALOG.clear();INVALID.clear();RULES=new JsonArray();
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
        Path rules=root.resolve("realmfolk/facility_rules.json");
        if(Files.isRegularFile(rules))try{RULES=JsonParser.parseString(Files.readString(rules)).getAsJsonObject().getAsJsonArray("facilities");}
        catch(Exception ex){throw new IllegalStateException("Invalid facility rules: "+rules,ex);}
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
        return present(level,site)&&standable(level,site.approach);
    }
    private static boolean present(ServerLevel level,LivingSite site){
        if(site==null||site.object==null||!level.hasChunkAt(pos(site.object)))return false;
        BlockState actual=level.getBlockState(pos(site.object));
        if(site.kind.equals("bed"))return actual.is(BlockTags.BEDS)&&actual.hasProperty(BedBlock.PART)
            &&actual.getValue(BedBlock.PART)==BedPart.HEAD&&completeBed(level,pos(site.object),actual);
        if(site.block.equals("minecraft:air"))return true; // An authored activity location survives temporary obstacles.
        return ForgeRegistries.BLOCKS.getKey(actual.getBlock()).toString().equals(site.block);
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
        var sites=new LinkedHashMap<String,LivingSite>();var entrances=new LinkedHashSet<Point>();
        boolean authored=false;
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
            var state=level.getBlockState(p);String kind="";
            if(state.is(BlockTags.BEDS)&&state.hasProperty(BedBlock.PART)&&state.getValue(BedBlock.PART)==BedPart.HEAD&&completeBed(level,p,state))kind="bed";
            else if(WORK.contains(state.getBlock())||state.is(TagKey.create(Registries.BLOCK,new ResourceLocation("realmfolk:workstations"))))kind="work";
            if(!kind.isEmpty()){LivingSite s=site(level,kind,point(p));sites.putIfAbsent(s.id,s);}
            if(state.getBlock() instanceof DoorBlock&&state.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER&&standable(level,point(p)))entrances.add(point(p));
            for(var value:RULES){var rule=value.getAsJsonObject();if(!matches(state,rule))continue;
                String type=text(rule,"kind","");if(!Set.of("work","seat").contains(type))throw new IllegalArgumentException("Facility kind must be work or seat");
                LivingSite s=site(level,type,point(p));s.adapter=text(rule,"adapter","stand");s.use=text(rule,"use","building");
                if(rule.has("seatHeight"))s.seatHeight=rule.get("seatHeight").getAsDouble();
                if(!Double.isFinite(s.seatHeight)||s.seatHeight<0||s.seatHeight>2)throw new IllegalArgumentException("seatHeight outside 0..2");
                sites.putIfAbsent(s.id,s);
            }
        }
        if(entrances.isEmpty())for(int y=b.minY();y<=Math.min(b.maxY(),b.minY()+4);y++){
            for(int x=b.minX();x<=b.maxX();x++)for(int z:new int[]{b.minZ(),b.maxZ()})if(standable(level,new Point(x,y,z)))entrances.add(new Point(x,y,z));
            for(int z=b.minZ();z<=b.maxZ();z++)for(int x:new int[]{b.minX(),b.maxX()})if(standable(level,new Point(x,y,z)))entrances.add(new Point(x,y,z));
            if(!entrances.isEmpty())break;
        }
        property.livingSites=new ArrayList<>(sites.values());property.entrances=new ArrayList<>(entrances);
        if(!authored)property.facilitySource="loaded_world";
        verify(level,property);dirty(level.getServer());
    }
    private static boolean matches(BlockState state,JsonObject rule){
        for(var selector:rule.getAsJsonArray("blocks")){
            String id=selector.getAsString();
            if(id.startsWith("#")?state.is(TagKey.create(Registries.BLOCK,new ResourceLocation(id.substring(1)))):ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString().equals(id))return true;
        }return false;
    }
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
                &&(!site.kind.equals("seat")||site.adapter.equals("marker_seat"));
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
        for(int i=0;i<Math.min(4,properties.size());i++){
            Property p=properties.get(Math.floorMod(cursor++,properties.size()));Town t=society.towns.get(p.town);
            if(t==null||!t.dimension.equals(dimension(server.overworld()))||!loaded(server.overworld(),p.bounds))continue;
            if(p.livingSites==null)discover(server.overworld(),p,null);else verify(server.overworld(),p);
        }
        if(FacilityAssignments.repair(society))dirty(server);
    }
    private static String text(JsonObject o,String key,String fallback){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():fallback;}
}
