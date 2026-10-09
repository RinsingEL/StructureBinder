package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.application.outdoor.CityTerraceFeaturePlanner;
import com.rinsing.geomantia.systems.city.algorithm.landuse.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.*;
import java.nio.file.*;
import java.util.*;

/** Production planners and executor, against a confined in-memory D3 world. Never starts Minecraft. */
public final class CityCampusExperiment {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Comparator<BlockPoint> ORDER = Comparator.comparingInt(BlockPoint::z).thenComparingInt(BlockPoint::x);
    private final JsonObject input;
    private CityCampusExperiment(JsonObject input) { this.input=input; }

    private JsonObject run() throws Exception {
        JsonObject result=new CityArrayExperiment(input).run();
        LandUseTerrainField terrain=new LandUseTerrainFieldCodec().fromJson(JsonParser.parseString(Files.readString(Path.of(input.get("terrainPath").getAsString()))).getAsJsonObject());
        List<JsonObject> members=objects(result.getAsJsonArray("members"));
        if(members.size()>180)throw new IllegalArgumentException("Workbench supports at most 180 placed structures");
        int[] box=sceneBounds(members);
        if((long)(box[2]-box[0]+49)*(box[3]-box[1]+49)>200000)throw new IllegalArgumentException("Scene is too spread out for the lightweight preview (200000 terrain columns maximum)");
        VirtualWorld world=new VirtualWorld(terrain,input);
        world.requireCoverage(box);
        Map<String,JsonObject> assets=new HashMap<>(), byMember=new HashMap<>(), arrays=new HashMap<>();
        for(var a:objects(input.getAsJsonArray("assets")))assets.put(text(a,"assetId"),a);
        for(var a:objects(input.getAsJsonArray("arrays")))arrays.put(text(a,"arrayId"),a);
        for(var m:members) {
            boolean water=m.getAsJsonObject("terrainEvidence").get("waterAreaBlocks").getAsLong()>0;
            if(world.dem!=null){water=false;var footprint=footprint(m);for(int z=footprint.minZ();z<=footprint.maxZ();z++)for(int x=footprint.minX();x<=footprint.maxX();x++)if(world.sampleDesignColumn(x,z).surfaceBlockId().equals("minecraft:water"))water=true;}
            if(!m.getAsJsonArray("issues").isEmpty() || water)
                throw new IllegalArgumentException("Resolve structure collision, bounds or water before building: "+text(m,"memberId"));
            JsonObject asset=assets.get(text(m,"assetId"));
            if(asset.has("consumer")&&!text(asset,"consumer").equals("array"))
                throw new IllegalArgumentException("Program-owned infrastructure cannot be an array member: "+text(m,"assetId"));
            byMember.put(text(m,"memberId"),m);
        }
        List<JsonObject> districts=objects(input.getAsJsonArray("districts"));
        Map<String,String> arrayDistrict=new HashMap<>();
        for(var d:districts)for(var id:d.getAsJsonArray("arrayIds")) {
            if(!arrays.containsKey(id.getAsString())||arrayDistrict.putIfAbsent(id.getAsString(),text(d,"districtId"))!=null)
                throw new IllegalArgumentException("Unknown array or duplicate district owner: "+id);
        }
        if(arrayDistrict.size()!=arrays.size())throw new IllegalArgumentException("Each array requires one district owner");
        List<JsonObject> anchors=new ArrayList<>();
        for(var m:members)if(!assets.get(text(m,"assetId")).get("naturalGround").getAsBoolean())anchors.add(anchor(m,assets.get(text(m,"assetId")),text(m,"arrayId")));
        var streets=new CityInternalStreetPlanner();var geometry=new CityBlueprintGroupLayoutPlanner();
        List<JsonObject> skeleton=new ArrayList<>();
        for(var entry:arrays.entrySet()) {
            var node=entry.getValue();String algorithm=text(node,"algorithm");
            List<JsonObject> local=anchors.stream().filter(a->text(a,"placementGroupId").equals(entry.getKey())).toList();
            if(local.size()<2||algorithm.equals("CONTIGUOUS")||node.has("layout"))continue;
            int span=members.stream().filter(m->text(m,"arrayId").equals(entry.getKey())).mapToInt(m->Math.max(m.get("width").getAsInt(),m.get("depth").getAsInt())).max().orElse(1);
            var parameters=geometry.parameters(algorithm,CityBlueprint.DensityClass.valueOf(node.has("densityClass")?text(node,"densityClass"):"BALANCED"));
            BlockPoint origin=point(local.get(0).getAsJsonObject("originBlock"));
            skeleton.addAll(streets.planSkeleton(entry.getKey(),algorithm,parameters,local,true,local.size(),span,
                    new CityBlueprintGroupLayoutPlanner.Frame(origin,1,0),terrain.planningBounds()));
        }
        skeleton.addAll(DesignedStreetWorkbenchAdapter.plan(result.getAsJsonArray("layoutSpaces"),members,anchors,terrain.planningBounds()));
        // Nested leaf groups share the explicit district's circulation network, not a new road per hierarchy edge.
        for(var a:anchors)a.addProperty("placementGroupId",arrayDistrict.get(text(a,"placementGroupId")));
        for(var b:skeleton)b.addProperty("groupId",arrayDistrict.get(text(b,"groupId")));
        JsonObject profile=new JsonObject();profile.addProperty("profileRef","workbench:roads");profile.addProperty("hierarchy","HIERARCHICAL");
        JsonObject referenceJson=new JsonObject();JsonArray profiles=new JsonArray();profiles.add(profile);referenceJson.add("roadProfiles",profiles);
        var references=new CityBlueprintReferenceCatalog(referenceJson,Set.of(),Set.of(),Set.of(),Map.of(),Map.of(),Set.of(),Set.of(),Set.of("workbench:roads"),Set.of(),null,Map.of(),Map.of(),Map.of(),Map.of());
        List<CityBlueprint.Relation> links=new ArrayList<>();
        for(var l:objects(input.getAsJsonArray("connections")))links.add(new CityBlueprint.Relation(text(l,"from"),text(l,"to"),CityBlueprint.RelationKind.CONNECTION,CityBlueprint.RelationStrength.SOFT,CityBlueprint.DistancePreference.NONE,CityBlueprint.DirectionPreference.NONE));
        var blueprint=new CityBlueprint(CityBlueprint.SCHEMA,terrain.cityId(),null,null,input.has("seed")?input.get("seed").getAsLong():20261008L,null,null,List.of(),List.of(),links,new CityBlueprint.ProfileRef("workbench:roads"),null,null);
        var main=new CityMainRoadPlanner().plan(blueprint,references,terrain,anchors,skeleton);
        var finalized=streets.finalizeSkeleton(skeleton,main.streetBands(),anchors,terrain.planningBounds());
        if(input.has("publicSpaces"))for(var e:finalized.trace().getAsJsonArray("accessOutcomes")) {
            var outcome=e.getAsJsonObject();if(!text(outcome,"status").startsWith("CONNECTED"))
                throw new IllegalArgumentException("Entrance is not connected: "+text(outcome,"entranceId"));
        }
        List<JsonObject> bands=new ArrayList<>(main.streetBands());bands.addAll(finalized.streetBands());
        Map<String,Integer> bandIds=new HashMap<>();
        for(var band:bands){String id=text(band,"streetBandId");int occurrence=bandIds.merge(id,1,Integer::sum);
            if(occurrence>1){band.addProperty("originalStreetBandId",id);band.addProperty("streetBandId",id+"::occurrence_"+occurrence);}}
        List<LandUseSourceResolver.RoadBand> roadBands=new ArrayList<>();
        for(var b:bands)roadBands.add(new LandUseSourceResolver.RoadBand(text(b,"streetBandId"),"workbench",text(b,"roadKind"),point(b.getAsJsonObject("start")),point(b.getAsJsonObject("end")),bounds(b.getAsJsonObject("bounds")),b.get("widthBlocks").getAsInt(),"STAIR_SLAB_STAIR",
                "minecraft:polished_andesite_slab","minecraft:polished_andesite_stairs","minecraft:stone_brick_wall"));
        Map<String,List<BlockBounds>> buildings=new TreeMap<>(),platforms=new TreeMap<>();
        Map<String,BlockBounds> cores=new HashMap<>();
        List<JsonObject> terraceDefinitions=TerraceWorkbenchAdapter.definitions(input,districts);
        Map<String,JsonObject> terraceById=new TreeMap<>();Map<String,String> arrayTerrace=new HashMap<>();
        Set<BlockPoint> landscape=new HashSet<>();
        for(var d:districts) {
            String id=text(d,"districtId");List<JsonObject> local=members.stream().filter(m->id.equals(arrayDistrict.get(text(m,"arrayId")))).toList();
            var core=byMember.get(text(d,"coreMemberId"));
            if(core==null||!local.contains(core)||!text(core,"designRole").equals("CORE"))throw new IllegalArgumentException("District core must be an actual declared core in that district: "+id);
            if(!d.get("platform").getAsBoolean())buildings.put(id,local.stream().map(CityCampusExperiment::footprint).toList());
            for(var m:local)if(assets.get(text(m,"assetId")).has("naturalGround")&&assets.get(text(m,"assetId")).get("naturalGround").getAsBoolean())landscape.addAll(CityDistrictPlanner.rasterize(List.of(footprint(m)),terrain.planningBounds()));
        }
        for(var g:terraceDefinitions) {
            String id=text(g,"terraceId"),owner=text(g,"districtId");
            if(terraceById.putIfAbsent(id,g)!=null)throw new IllegalArgumentException("Duplicate terrace id: "+id);
            var d=districts.stream().filter(v->text(v,"districtId").equals(owner)).findFirst().orElseThrow();
            if(!d.get("platform").getAsBoolean())throw new IllegalArgumentException("Natural district cannot own a terrace");
            for(var a:g.getAsJsonArray("arrayIds"))if(!owner.equals(arrayDistrict.get(a.getAsString()))||arrayTerrace.putIfAbsent(a.getAsString(),id)!=null)
                throw new IllegalArgumentException("Terrace arrays need exclusive ownership inside their display district");
            var local=members.stream().filter(m->id.equals(arrayTerrace.get(text(m,"arrayId")))).toList();var core=byMember.get(text(g,"coreMemberId"));
            if(core==null||!local.contains(core)||!text(core,"designRole").equals("CORE"))throw new IllegalArgumentException("Terrace needs an actual core: "+id);
            var footprints=local.stream().map(CityCampusExperiment::footprint).toList();buildings.put(id,footprints);List<BlockBounds> support=new ArrayList<>(footprints);
            for(var space:objects(result.getAsJsonArray("layoutSpaces")))if(id.equals(arrayTerrace.get(text(space,"ownerArrayId")))) {
                int sx=space.get("x").getAsInt(),sz=space.get("z").getAsInt();support.add(new BlockBounds(sx,sz,sx+space.get("width").getAsInt()-1,sz+space.get("depth").getAsInt()-1));
            }
            platforms.put(id,support);cores.put(id,footprint(core));
        }
        for(var d:districts)if(d.get("platform").getAsBoolean())for(var a:d.getAsJsonArray("arrayIds"))
            if(!arrayTerrace.containsKey(a.getAsString()))throw new IllegalArgumentException("Missing terrace ownership: "+a);
        var district=new CityDistrictPlanner().plan(terrain.planningBounds(),terrain,buildings,platforms,roadBands.stream().map(LandUseSourceResolver.RoadBand::bounds).toList(),landscape,
                new LandUseSeedGroup.FoundationSettings(input.has("foundationMarginBlocks")?input.get("foundationMarginBlocks").getAsInt():4,8,24));
        var resolvedGroups=TerraceWorkbenchAdapter.resolveClaims(district.groups(),buildings);
        List<LandUseAreaPlan.Area> areas=new ArrayList<>();List<LandUseSeedGroup> seedGroups=new ArrayList<>();Map<String,BlockBounds> areaCores=new HashMap<>();Map<String,Integer> areaOffsets=new HashMap<>();
        for(var entry:resolvedGroups.entrySet()) {
            Set<BlockPoint> local=entry.getValue();
            String id=entry.getKey()+"::foundation";
            areas.add(new LandUseAreaPlan.Area(id,"workbench:foundation","foundation",List.of(id),List.of(),List.of(),spans(local),buildings.get(entry.getKey()),List.of(),List.of(),0,SurfacePolicy.PAVE,VegetationPolicy.CLEAR,BoundaryPolicy.OPEN));areaCores.put(id,cores.get(entry.getKey()));
            areaOffsets.put(id,TerraceWorkbenchAdapter.integer(terraceById.get(entry.getKey()),"datumOffsetBlocks",0));
            var rule=new com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRule("workbench:foundation","foundation",List.of(),1,0,0,1000000,1000000,1,1,1,1,0,1,true,SurfacePolicy.PAVE,VegetationPolicy.CLEAR,BoundaryPolicy.OPEN);
            seedGroups.add(new LandUseSeedGroup(id,rule,LandUseSurfaceSettings.defaults(SurfacePolicy.PAVE),List.of(),buildings.get(entry.getKey()),List.of(local.stream().sorted(ORDER).findFirst().orElseThrow()),List.of(),0,local.size(),local.size(),1000000,1));
        }
        var areaPlan=new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,"workbench",terrain.cityId(),"",terrain.planningBounds(),areas,List.of(),List.of(),List.of()));
        var surface=new CityLandUseSurfacePrintPlanner().plan(areaPlan,seedGroups,terrain,roadBands,List.of(),List.of(),areaCores,areaOffsets);
        if(!input.has("publicSpaces")&&(!input.has("greenery")||input.get("greenery").getAsBoolean()))surface=new CityPublicGreeneryPlanner().append(surface,district,areaPlan,new CityPublicGreeneryPlanner.Preset("minecraft:grass_block",List.of("minecraft:fern","minecraft:azalea","minecraft:allium"),0.025,3),
                roadBands.stream().map(b->new LandUseAreaPlan.CorridorExclusion(b.streetBandId(),b.bounds(),"road")).toList());
        Map<BlockPoint,Integer> targetHeights=new HashMap<>();
        JsonArray terraces=new JsonArray();
        for(var a:surface.areas())if(a.recipe() instanceof CityLandUseSurfacePrintPlan.UniformRecipe r) {
            String id=a.landUseAreaId().replace("::foundation","");var definition=terraceById.get(id);
            JsonObject record=new JsonObject();record.addProperty("terraceId",id);record.addProperty("districtId",text(definition,"districtId"));record.addProperty("coreMemberId",text(definition,"coreMemberId"));
            record.add("arrayIds",definition.get("arrayIds").deepCopy());record.add("spans",JSON.toJsonTree(r.platformSpans()));record.addProperty("datumY",r.platformSpans().get(0).targetY());terraces.add(record);
            for(var s:r.platformSpans())for(int x=s.minX();x<=s.maxX();x++)targetHeights.put(new BlockPoint(x,s.z()),s.targetY());
        }
        Map<BlockPoint,Integer> entrancePins=new HashMap<>();
        for(var anchor:anchors) {
            var member=byMember.get(text(anchor,"anchorId"));var bounds=footprint(member);
            Integer y=targetHeights.get(new BlockPoint((bounds.minX()+bounds.maxX())/2,(bounds.minZ()+bounds.maxZ())/2));
            if(y==null)continue;
            for(var entrance:objects(anchor.getAsJsonObject("templatePlacementPlan").getAsJsonObject("transformed").getAsJsonArray("roadEntrances"))) {
                var p=point(entrance.getAsJsonObject("worldPosition"));
                var outside=switch(text(entrance,"direction")){case "NORTH"->new BlockPoint(p.x(),p.z()-1);case "SOUTH"->new BlockPoint(p.x(),p.z()+1);
                    case "EAST"->new BlockPoint(p.x()+1,p.z());default->new BlockPoint(p.x()-1,p.z());};
                Integer old=entrancePins.putIfAbsent(outside,y);
                if(old!=null&&!old.equals(y))throw new IllegalArgumentException("Conflicting terrace entrance heights");
            }
        }
        var gradedFeatures=new CityTerraceRoadGradePlanner().plan(surface.featureCells(),targetHeights,entrancePins,world::groundY);
        surface=new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(surface.schema(),surface.cityId(),surface.sourceLandUsePlanHash(),"",surface.areas(),surface.sharedBoundarySpans(),gradedFeatures,surface.materialField()));
        surface=new CityLandUseSurfacePrintPlanCodec().withComputedHash(surface.withMaterials(TerraceWorkbenchAdapter.materials(input,terraceDefinitions,objects(terraces))));
        var featurePlans=TerraceWorkbenchAdapter.features(input,terraceDefinitions,objects(terraces),members,
                roadBands.stream().map(LandUseSourceResolver.RoadBand::bounds).toList(),assets,world::groundY);
        var compiler=new CityLandUseChunkCompiler();var prepared=compiler.prepare(areaPlan,surface);var executor=new CityLandUseChunkExecutor();JsonArray execution=new JsonArray();
        Set<BlockPoint> owners=new TreeSet<>(ORDER);
        for(var p:district.construction())owners.add(new BlockPoint(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));
        for(var f:surface.featureCells())owners.add(new BlockPoint(Math.floorDiv(f.x(),16),Math.floorDiv(f.z(),16)));
        for(var owner:owners) {
            var report=executor.execute(compiler.compilePrepared(prepared,owner.x(),owner.z()),world,CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES);
            execution.add(JSON.toJsonTree(report));
            if(report.status()!=CityLandUseChunkExecutor.Status.APPLIED)throw new IllegalArgumentException("Offline owner failed: "+owner+" "+report.reasonCode());
        }
        for(var m:members) {
            String owner=arrayDistrict.get(text(m,"arrayId"));BlockBounds f=footprint(m);
            Integer y=targetHeights.get(new BlockPoint((f.minX()+f.maxX())/2,(f.minZ()+f.maxZ())/2));
            if(y==null)y=world.sampleDesignColumn((f.minX()+f.maxX())/2,(f.minZ()+f.maxZ())/2).surfaceY();
            m.addProperty("surfaceY",y);m.addProperty("districtId",owner);
            if(arrayTerrace.containsKey(text(m,"arrayId")))m.addProperty("terraceId",arrayTerrace.get(text(m,"arrayId")));
            JsonObject asset=assets.get(text(m,"assetId"));
            if(!asset.get("naturalGround").getAsBoolean()) {
                int floor=y+1-asset.get("groundPlane").getAsInt();
                for(int z=f.minZ();z<=f.maxZ();z++)for(int x=f.minX();x<=f.maxX();x++) {
                    int ground=world.sampleDesignColumn(x,z).surfaceY();
                    for(var block:CityFoundationSupportSettings.current().column(ground,floor,x,z,x==f.minX()&&z==f.minZ()))
                        world.setBlock(x,block.y(),z,block.blackstonePier()?"minecraft:blackstone_wall":"minecraft:stone_bricks");
                }
            }
            for(var e:anchors)if(text(e,"anchorId").equals(text(m,"memberId")))m.add("roadEntrances",e.getAsJsonObject("templatePlacementPlan").getAsJsonObject("transformed").getAsJsonArray("roadEntrances"));
        }
        List<CityTerraceFeaturePlanner.Block> decorationBlocks=featurePlans.values().stream().flatMap(p->p.blocks().stream()).toList();
        Set<String> decorationCells=new HashSet<>();for(var b:decorationBlocks)decorationCells.add(b.x()+","+b.y()+","+b.z());
        for(var b:decorationBlocks) {
            if(!world.ensureCanWrite(b.x(),b.y(),b.z()))throw new IllegalArgumentException("Terrace feature outside build bounds");
            String block=b.blockId();if(b.kind().equals("EDGE_RAIL"))block=railState(block,b.x(),b.y(),b.z(),decorationCells);
            else if(block.endsWith("_slab"))block+="[type=bottom,waterlogged=false]";
            world.setBlock(b.x(),b.y(),b.z(),block);world.kinds.put(VirtualWorld.key(b.x(),b.y(),b.z()),"TERRACE_"+b.kind());
        }
        result.add("terraceFeaturePlans",JSON.toJsonTree(featurePlans));
        result.add("fixtures",JSON.toJsonTree(featurePlans.values().stream().flatMap(p->p.fixtures().stream()).toList()));
        result.addProperty("schema","city_campus_experiment_result.v1");result.addProperty("executionMode","offline_production_planners_and_chunk_executor");
        result.add("terraces",terraces);result.add("roadPlan",main.plan());result.add("streetReview",finalized.trace());result.add("roadBands",JSON.toJsonTree(bands));result.add("execution",execution);
        result.add("surfacePrintPlan",new CityLandUseSurfacePrintPlanCodec().toJson(surface));result.add("mutations",world.mutations());
        JsonArray ground=new JsonArray();
        for(int z=box[1]-24;z<=box[3]+24;z++)for(int x=box[0]-24;x<=box[2]+24;x++) { var c=world.sampleDesignColumn(x,z);JsonArray row=new JsonArray();row.add(x);row.add(z);row.add(c.surfaceY());row.add(c.surfaceBlockId());row.add(world.groundY(x,z));ground.add(row); }
        result.add("terrainColumns",ground);result.addProperty("terrainMode",world.dem==null?"D3_16_block_samples_bilinear_height_virtual_world":"DEM_STEP_1_GENERATOR_NATIVE_NO_INTERPOLATION");return result;
    }
    private static String railState(String block,int x,int y,int z,Set<String> cells) {
        boolean wall=block.endsWith("_wall"),thin=block.endsWith("_fence")||block.equals("minecraft:iron_bars");if(!wall&&!thin)return block;
        String[] names={"north","east","south","west"};int[][] dirs={{0,-1},{1,0},{0,1},{-1,0}};List<String> props=new ArrayList<>();
        for(int i=0;i<4;i++){boolean linked=cells.contains((x+dirs[i][0])+","+y+","+(z+dirs[i][1]));props.add(names[i]+"="+(wall?(linked?"low":"none"):Boolean.toString(linked)));}
        if(wall)props.add("up=true");props.add("waterlogged=false");return block+"["+String.join(",",props)+"]";
    }
    private static JsonObject anchor(JsonObject m,JsonObject asset,String group) {
        JsonObject a=new JsonObject();a.addProperty("anchorId",text(m,"memberId"));a.addProperty("placementGroupId",group);a.addProperty("blueprintPlacementPhase",text(m,"designRole"));a.add("originBlock",m.get("originBlock").deepCopy());
        for(String k:List.of("actualFootprint","collisionEnvelope","bodyEnvelope"))a.add(k,CityStructureCandidateEnvelope.boundsJson(footprint(m)));
        a.add("blueprintLayout",m.has("proposalTrace")?m.get("proposalTrace").deepCopy():new JsonObject());JsonArray entries=new JsonArray();
        int rotation=m.get("rotation").getAsInt(),w=asset.get("width").getAsInt(),d=asset.get("depth").getAsInt();var p=point(m.getAsJsonObject("originBlock"));
        for(var e:objects(asset.getAsJsonArray("entrances"))) {
            int x=e.get("x").getAsInt(),z=e.get("z").getAsInt(),rx=x,rz=z;
            if(rotation==90){rx=d-1-z;rz=x;}if(rotation==180){rx=w-1-x;rz=d-1-z;}if(rotation==270){rx=z;rz=w-1-x;}
            String[] dirs={"NORTH","EAST","SOUTH","WEST"};int direction=Arrays.asList(dirs).indexOf(text(e,"direction"));
            JsonObject entry=new JsonObject();entry.addProperty("entranceId",text(e,"id"));entry.add("worldPosition",new BlockPoint(p.x()+rx,p.z()+rz).asJson());entry.addProperty("direction",dirs[Math.floorMod(direction+rotation/90,4)]);entries.add(entry);
        }
        JsonObject transformed=new JsonObject(),placement=new JsonObject();transformed.add("roadEntrances",entries);placement.add("transformed",transformed);a.add("templatePlacementPlan",placement);return a;
    }
    private static List<LandUseAreaPlan.ScanlineSpan> spans(Set<BlockPoint> points) {
        List<BlockPoint> ordered=points.stream().sorted(ORDER).toList();List<LandUseAreaPlan.ScanlineSpan> out=new ArrayList<>();
        for(int i=0;i<ordered.size();) {var p=ordered.get(i++);int last=p.x();while(i<ordered.size()&&ordered.get(i).z()==p.z()&&ordered.get(i).x()==last+1)last=ordered.get(i++).x();out.add(new LandUseAreaPlan.ScanlineSpan(p.z(),p.x(),last));}return out;
    }
    private static int[] sceneBounds(List<JsonObject> members) {return new int[]{members.stream().mapToInt(m->footprint(m).minX()).min().orElseThrow(),members.stream().mapToInt(m->footprint(m).minZ()).min().orElseThrow(),members.stream().mapToInt(m->footprint(m).maxX()).max().orElseThrow(),members.stream().mapToInt(m->footprint(m).maxZ()).max().orElseThrow()};}
    private static BlockBounds footprint(JsonObject m) {var p=point(m.getAsJsonObject("originBlock"));return new BlockBounds(p.x(),p.z(),p.x()+m.get("width").getAsInt()-1,p.z()+m.get("depth").getAsInt()-1);}
    private static BlockBounds bounds(JsonObject b) {return new BlockBounds(b.get("minX").getAsInt(),b.get("minZ").getAsInt(),b.get("maxX").getAsInt(),b.get("maxZ").getAsInt());}
    private static BlockPoint point(JsonObject p) {return new BlockPoint(p.get("x").getAsInt(),p.get("z").getAsInt());}
    private static String text(JsonObject o,String k) {return o.get(k).getAsString();}
    private static List<JsonObject> objects(JsonArray a) {return a==null?List.of():a.asList().stream().map(JsonElement::getAsJsonObject).toList();}

    private static final class VirtualWorld implements CityLandUseChunkExecutor.ExecutionWorld {
        private final Map<BlockPoint,LandUseTerrainField.Cell> terrain=new HashMap<>();private final int step;
        private final JsonObject dem;private BlockBounds demBounds;private JsonArray demGround,demWater;
        private final Map<String,String> blocks=new LinkedHashMap<>();private final Map<String,String> kinds=new HashMap<>();
        VirtualWorld(LandUseTerrainField field,JsonObject input)throws Exception {
            step=field.cellStepBlocks();for(var c:field.cells())terrain.put(new BlockPoint(c.cellX(),c.cellZ()),c);
            dem=input.has("demPath")?JsonParser.parseString(Files.readString(Path.of(text(input,"demPath")))).getAsJsonObject():null;
            if(dem!=null){
                String hash="sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(text(input,"terrainPath")))));
                if(!text(dem,"schema").equals("city_terrain_dem.v1")||dem.get("step").getAsInt()!=1||!text(dem,"order").equals("z_then_x")||!text(dem,"cityId").equals(field.cityId())||!text(dem,"d3TerrainHash").equals(hash))throw new IllegalArgumentException("DEM identity or format does not match D3");
                demBounds=bounds(dem.getAsJsonObject("bounds"));demGround=dem.getAsJsonArray("groundY");demWater=dem.getAsJsonArray("waterSurfaceY");
                long size=(long)demBounds.widthBlocks()*demBounds.heightBlocks();
                if(size>1048576||demGround.size()!=size||demWater.size()!=size)throw new IllegalArgumentException("DEM must contain every column");
            }
        }
        void requireCoverage(int[] box){if(dem!=null&&(!demBounds.contains(box[0]-24,box[1]-24)||!demBounds.contains(box[2]+24,box[3]+24)))throw new IllegalArgumentException("DEM must cover the complete scene and its 24-block terrain margin");}
        int demIndex(int x,int z){if(!demBounds.contains(x,z))throw new IllegalArgumentException("DEM coverage absent: "+x+","+z);return (z-demBounds.minZ())*demBounds.widthBlocks()+x-demBounds.minX();}
        int groundY(int x,int z){return dem==null?sampleDesignColumn(x,z).surfaceY():demGround.get(demIndex(x,z)).getAsInt();}
        public CityLandUseChunkExecutor.ColumnSample sampleColumn(int x,int z){return sampleDesignColumn(x,z);}
        public CityLandUseChunkExecutor.ColumnSample sampleDesignColumn(int x,int z) {
            if(dem!=null){int i=demIndex(x,z);JsonElement water=demWater.get(i);return new CityLandUseChunkExecutor.ColumnSample(water.isJsonNull()?demGround.get(i).getAsInt():water.getAsInt(),water.isJsonNull()?"minecraft:grass_block":"minecraft:water",true);}
            int cx=Math.floorDiv(x,step),cz=Math.floorDiv(z,step);var c=terrain.get(new BlockPoint(cx,cz));if(c==null)throw new IllegalArgumentException("D3 coverage absent: "+x+","+z);
            double fx=Math.floorMod(x,step)/(double)step,fz=Math.floorMod(z,step)/(double)step;
            double a=c.elevation(),b=terrain.getOrDefault(new BlockPoint(cx+1,cz),c).elevation(),v=terrain.getOrDefault(new BlockPoint(cx,cz+1),c).elevation(),d=terrain.getOrDefault(new BlockPoint(cx+1,cz+1),c).elevation();
            int y=(int)Math.round((a*(1-fx)+b*fx)*(1-fz)+(v*(1-fx)+d*fx)*fz);
            return new CityLandUseChunkExecutor.ColumnSample(y,c.water()?"minecraft:water":"minecraft:grass_block",true);
        }
        public boolean isKnownBlock(String id){return id.startsWith("minecraft:");}
        public boolean ensureCanWrite(int x,int y,int z){return y>=-64&&y<320&&terrain.containsKey(new BlockPoint(Math.floorDiv(x,step),Math.floorDiv(z,step)));}
        public CityLandUseChunkExecutor.TargetState inspect(int x,int y,int z){return new CityLandUseChunkExecutor.TargetState(blocks.getOrDefault(key(x,y,z),"minecraft:air"),true);}
        public boolean setBlock(int x,int y,int z,String id){blocks.put(key(x,y,z),id);return true;}
        public boolean setFeatureBlock(int x,int y,int z,String id,CityLandUseSurfacePrintPlan.FeatureKind kind,CityLandUseSurfacePrintPlan.HorizontalFacing facing){
            String value=id;if((kind==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_SLAB||kind==CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_DECK)&&id.endsWith("_slab"))value+="[type=double,waterlogged=false]";
            if(kind==CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR&&id.endsWith("_stairs"))value+="[facing="+(facing==CityLandUseSurfacePrintPlan.HorizontalFacing.NONE?"north":facing.name().toLowerCase())+",half=bottom,shape=straight,waterlogged=false]";
            setBlock(x,y,z,value);kinds.put(key(x,y,z),kind.name());return true;
        }
        public boolean restoreBlock(int x,int y,int z,Object snapshot){blocks.put(key(x,y,z),snapshot.toString());return true;}
        private static String key(int x,int y,int z){return x+","+y+","+z;}
        JsonArray mutations(){JsonArray rows=new JsonArray();blocks.forEach((key,state)->{String[] p=key.split(",");JsonArray row=new JsonArray();for(var v:p)row.add(Integer.parseInt(v));row.add(state);row.add(kinds.getOrDefault(key,"SURFACE_OR_ENGINEERING"));rows.add(row);});return rows;}
    }
    public static void main(String[] args)throws Exception {JsonObject result;try{result=new CityCampusExperiment(JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject()).run();}catch(IllegalArgumentException e){result=new JsonObject();result.addProperty("ok",false);result.addProperty("error",e.getMessage());}Files.writeString(Path.of(args[1]),JSON.toJson(result));}
}
