package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.nio.file.*;
import java.util.*;

/** Offline adapter. Legacy geometry uses production planners; opt-in rectangle packing is experimental. No Forge lifecycle is started. */
public final class CityArrayExperiment {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> ALGORITHMS = Set.of("GRID", "LINEAR", "COURTYARD", "COMPACT", "ORGANIC_COMPACT", "CENTER_SYMMETRIC", "CONTIGUOUS");
    private final CityBlueprintGroupLayoutPlanner planner = new CityBlueprintGroupLayoutPlanner();
    private final Map<String, JsonObject> assets = new LinkedHashMap<>(), nodes = new LinkedHashMap<>();
    private final Set<String> composed = new HashSet<>();
    private final JsonObject input;
    private final long seed;

    CityArrayExperiment(JsonObject input) {
        this.input = input;
        seed = input.has("seed") ? input.get("seed").getAsLong() : 20261008L;
        for (JsonElement e : input.getAsJsonArray("assets")) {
            JsonObject a = e.getAsJsonObject();
            if (assets.putIfAbsent(str(a,"assetId"), a) != null) throw new IllegalArgumentException("Duplicate assetId");
            for (String k : List.of("width","depth","height")) positive(a,k,1024);
        }
        for (JsonElement e : input.getAsJsonArray("arrays")) addNode(e.getAsJsonObject(), "arrayId");
        for (JsonElement e : arr(input,"compositions")) addNode(e.getAsJsonObject(), "compositionId");
        for (JsonObject n : nodes.values()) if (n.has("compositionId")) {
            JsonArray children = n.getAsJsonArray("memberIds");
            if (children == null || children.isEmpty()) throw new IllegalArgumentException("Composition needs memberIds");
            if (n.has("coreNodeId") && children.asList().stream().noneMatch(e -> e.getAsString().equals(str(n,"coreNodeId"))))
                throw new IllegalArgumentException("coreNodeId must name a direct child");
            for (JsonElement e : children) {
                String id=e.getAsString();
                if (!nodes.containsKey(id) || !composed.add(id)) throw new IllegalArgumentException("Unknown member or multiple parents: "+id);
            }
        }
        // Audit every node, including disconnected cycles.
        for (String id : nodes.keySet()) validateTree(id,new HashSet<>());
    }
    private void addNode(JsonObject node,String key) {
        String id=str(node,key);
        if (nodes.putIfAbsent(id,node)!=null) throw new IllegalArgumentException("Duplicate node ID: "+id);
        String algorithm=str(node,"algorithm");
        if (!ALGORITHMS.contains(algorithm)) throw new IllegalArgumentException("Unsupported algorithm: "+algorithm);
        point(node.getAsJsonObject("anchorBlock"));
        integer(node,"directionDegrees",0); // Multiples of 90 keep integer footprints and unambiguous axes.
        if (Math.floorMod(integer(node,"directionDegrees",0),90)!=0) throw new IllegalArgumentException("directionDegrees must be a multiple of 90");
        CityBlueprint.DensityClass.valueOf(optional(node,"densityClass","BALANCED"));
    }
    private void validateTree(String id,Set<String> visiting) {
        if (!visiting.add(id)) throw new IllegalArgumentException("Composition cycle: "+id);
        JsonObject n=nodes.get(id);
        for (JsonElement child:arr(n,"memberIds")) validateTree(child.getAsString(),visiting);
        visiting.remove(id);
    }
    private List<JsonObject> render(String id) {
        JsonObject n=nodes.get(id);
        if (!n.has("compositionId")) return leaf(n);
        List<List<JsonObject>> children=new ArrayList<>();int span=1;
        for (JsonElement e:arr(n,"memberIds")) {
            List<JsonObject> child=render(e.getAsString());children.add(child);
            int[] b=bounds(child);span=Math.max(span,Math.max(b[2]-b[0]+1,b[3]-b[1]+1));
        }
        if(n.has("layout")) return packedComposition(n,children);
        List<JsonObject> result=new ArrayList<>();
        if (str(n,"algorithm").equals("CONTIGUOUS")) throw new IllegalArgumentException("CONTIGUOUS is a leaf algorithm; do not join complete child envelopes without space");
        int coreIndex=-1;
        if(n.has("coreNodeId"))for(int i=0;i<children.size();i++)
            if(str(n,"coreNodeId").equals(n.getAsJsonArray("memberIds").get(i).getAsString()))coreIndex=i;
        for (int i=0;i<children.size();i++) {
            // Reserve the first layout slot for the declared core even if listed after support nodes.
            int placementIndex=coreIndex>=0 && i<coreIndex ? i+1 : i;
            BlockPoint origin=i==coreIndex ? point(n.getAsJsonObject("anchorBlock"))
                    : proposal(n,placementIndex,span).guides().get(0);int[] b=bounds(children.get(i));
            for (JsonObject member:children.get(i)) {
                JsonObject copy=member.deepCopy();JsonObject p=copy.getAsJsonObject("originBlock");
                p.addProperty("x",p.get("x").getAsInt()+origin.x()-b[0]);p.addProperty("z",p.get("z").getAsInt()+origin.z()-b[1]);
                translatePoints(copy.get("proposalTrace"), origin.x()-b[0], origin.z()-b[1]);
                translatePoints(copy.get("reservedSpaces"), origin.x()-b[0], origin.z()-b[1]);
                JsonArray chain=arr(copy,"compositionPath").deepCopy();chain.add(str(n,"compositionId"));copy.add("compositionPath",chain);result.add(copy);
            }
        }
        return result;
    }
    private static void translatePoints(JsonElement value, int dx, int dz) {
        if (value == null) return;
        if (value.isJsonArray()) { for (JsonElement child : value.getAsJsonArray()) translatePoints(child, dx, dz); }
        else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("x") && object.has("z")) {
                object.addProperty("x", object.get("x").getAsInt()+dx);
                object.addProperty("z", object.get("z").getAsInt()+dz);
            } else for (var entry : object.entrySet()) translatePoints(entry.getValue(), dx, dz);
        }
    }
    private List<JsonObject> leaf(JsonObject n) {
        int count=positive(n,"count",1024);List<String> chosen=new ArrayList<>();
        if (n.has("coreAssetId")) chosen.add(str(n,"coreAssetId"));
        for (JsonElement e:arr(n,"requiredAssetIds")) chosen.add(e.getAsString());
        if (count<chosen.size()) throw new IllegalArgumentException("count is smaller than core + required members");
        JsonArray fill=arr(n,"fill");
        for (JsonElement e:fill) {
            JsonObject candidate=e.getAsJsonObject();
            if (!Double.isFinite(candidate.get("weight").getAsDouble()) || candidate.get("weight").getAsDouble()<=0) throw new IllegalArgumentException("fill weight must be positive and finite");
            if (!assets.containsKey(str(candidate,"assetId"))) throw new IllegalArgumentException("Unknown fill asset: "+str(candidate,"assetId"));
        }
        if (chosen.size()<count && fill.isEmpty()) throw new IllegalArgumentException("Additional members need fill assets");
        Random random=new Random(seed ^ str(n,"arrayId").hashCode());double sum=0;
        for(JsonElement e:fill)sum+=e.getAsJsonObject().get("weight").getAsDouble();
        if(!Double.isFinite(sum))throw new IllegalArgumentException("fill weight sum must be finite");
        while(chosen.size()<count) {
            double v=random.nextDouble()*sum;String selected=str(fill.get(fill.size()-1).getAsJsonObject(),"assetId");
            for(JsonElement e:fill) {JsonObject f=e.getAsJsonObject();v-=f.get("weight").getAsDouble();if(v<=0){selected=str(f,"assetId");break;}}
            chosen.add(selected);
        }
        int rotation=Math.floorMod(integer(n,"rotation",0),360);
        if(rotation%90!=0)throw new IllegalArgumentException("rotation must be a multiple of 90");
        if(n.has("layout")) return packedLeaf(n,chosen);
        int span=1;List<CityContiguousLayoutPlanner.Size> sizes=new ArrayList<>();
        for(String assetId:chosen) {
            JsonObject a=assets.get(assetId);if(a==null)throw new IllegalArgumentException("Unknown asset: "+assetId);
            int w=a.get("width").getAsInt(),d=a.get("depth").getAsInt();if(rotation%180!=0){int t=w;w=d;d=t;}
            sizes.add(new CityContiguousLayoutPlanner.Size(w,d));span=Math.max(span,Math.max(w,d));
        }
        boolean contiguous=str(n,"algorithm").equals("CONTIGUOUS");
        if(contiguous && Math.floorMod(integer(n,"directionDegrees",0),360)!=0)throw new IllegalArgumentException("CONTIGUOUS currently uses world axes; use rotation for template dimensions");
        List<CityContiguousLayoutPlanner.Size> reservedSizes=new ArrayList<>();
        for(int i=0;i<sizes.size();i++) {int gap=integer(assets.get(chosen.get(i)),"layoutClearance",0);reservedSizes.add(new CityContiguousLayoutPlanner.Size(sizes.get(i).width()+gap,sizes.get(i).depth()+gap));}
        List<BlockPoint> packed=contiguous?CityContiguousLayoutPlanner.plan(reservedSizes,seed):List.of();
        List<JsonObject> result=new ArrayList<>();BlockPoint anchor=point(n.getAsJsonObject("anchorBlock"));
        for(int i=0;i<count;i++) {
            var proposed=contiguous?null:proposal(n,i,span);
            BlockPoint origin=contiguous?new BlockPoint(anchor.x()+packed.get(i).x(),anchor.z()+packed.get(i).z()):proposed.guides().get(0);
            if(i==0 && n.has("coreAssetId")) origin=anchor;
            JsonObject a=assets.get(chosen.get(i)),m=new JsonObject();
            m.addProperty("memberId",str(n,"arrayId")+":"+(i+1));m.addProperty("arrayId",str(n,"arrayId"));m.addProperty("assetId",chosen.get(i));m.addProperty("name",str(a,"name"));m.addProperty("placeholder",a.get("placeholder").getAsBoolean());
            m.addProperty("function",optional(n,"function",""));m.addProperty("designRole",i==0&&n.has("coreAssetId")?"CORE":i<(n.has("coreAssetId")?1:0)+arr(n,"requiredAssetIds").size()?"REQUIRED":"FILL");
            m.add("originBlock",origin.asJson());m.addProperty("width",sizes.get(i).width());m.addProperty("depth",sizes.get(i).depth());m.addProperty("height",a.get("height").getAsInt());m.addProperty("rotation",rotation);
            m.addProperty("geometryEngine",contiguous?"CityContiguousLayoutPlanner.plan":"CityBlueprintGroupLayoutPlanner.propose");
            if(proposed!=null)m.add("proposalTrace",proposed.traceJson());result.add(m);
        }
        return result;
    }
    /** Experimental rectangle packing: actual envelopes, explicit void and per-template fronts. */
    private List<JsonObject> packedLeaf(JsonObject n,List<String> chosen) {
        List<JsonObject> result=new ArrayList<>();String algorithm=str(n,"algorithm");
        boolean inward=n.getAsJsonObject("layout").has("faceInward")&&n.getAsJsonObject("layout").get("faceInward").getAsBoolean();
        for(int i=0;i<chosen.size();i++) {
            JsonObject a=assets.get(chosen.get(i));if(a==null)throw new IllegalArgumentException("Unknown asset: "+chosen.get(i));
            int rotation=Math.floorMod(integer(n,"rotation",0),360);
            if(inward&&algorithm.equals("COURTYARD")) {
                // Perimeter order N,W,E,S; match the actual author's first road entrance.
                String[] desired={"SOUTH","EAST","WEST","NORTH"},dirs={"NORTH","EAST","SOUTH","WEST"};
                JsonArray entrances=arr(a,"entrances");if(entrances.isEmpty())throw new IllegalArgumentException("Inward layout needs authored entrance: "+chosen.get(i));
                int from=Arrays.asList(dirs).indexOf(str(entrances.get(0).getAsJsonObject(),"direction"));
                rotation=Math.floorMod(Arrays.asList(dirs).indexOf(desired[i%4])-from,4)*90;
            }
            int w=a.get("width").getAsInt(),d=a.get("depth").getAsInt();if(rotation%180!=0){int t=w;w=d;d=t;}
            JsonObject m=new JsonObject();m.addProperty("memberId",str(n,"arrayId")+":"+(i+1));m.addProperty("arrayId",str(n,"arrayId"));
            m.addProperty("assetId",chosen.get(i));m.addProperty("name",str(a,"name"));m.addProperty("placeholder",a.get("placeholder").getAsBoolean());
            m.addProperty("function",optional(n,"function",""));m.addProperty("designRole",i==0&&n.has("coreAssetId")?"CORE":i<(n.has("coreAssetId")?1:0)+arr(n,"requiredAssetIds").size()?"REQUIRED":"FILL");
            m.addProperty("width",w);m.addProperty("depth",d);m.addProperty("height",a.get("height").getAsInt());m.addProperty("rotation",rotation);
            m.addProperty("geometryEngine","WorkbenchRectangleLayout.actualEnvelopes");result.add(m);
        }
        List<int[]> sizes=result.stream().map(m->new int[]{m.get("width").getAsInt(),m.get("depth").getAsInt()}).toList();
        List<BlockPoint> origins=rectangleOrigins(n,sizes);
        for(int i=0;i<result.size();i++)result.get(i).add("originBlock",origins.get(i).asJson());
        reserveCourt(n,result);return result;
    }
    private List<JsonObject> packedComposition(JsonObject n,List<List<JsonObject>> children) {
        List<int[]> sizes=children.stream().map(c->{int[] b=bounds(c);return new int[]{b[2]-b[0]+1,b[3]-b[1]+1};}).toList();
        var origins=rectangleOrigins(n,sizes);List<JsonObject> result=new ArrayList<>();
        for(int i=0;i<children.size();i++) {
            int[] b=bounds(children.get(i));int dx=origins.get(i).x()-b[0],dz=origins.get(i).z()-b[1];
            for(JsonObject member:children.get(i)) {
                JsonObject copy=member.deepCopy();translatePoints(copy.get("originBlock"),dx,dz);translatePoints(copy.get("proposalTrace"),dx,dz);translatePoints(copy.get("reservedSpaces"),dx,dz);
                JsonArray chain=arr(copy,"compositionPath").deepCopy();chain.add(str(n,"compositionId"));copy.add("compositionPath",chain);result.add(copy);
            }
        }
        reserveCourt(n,result);return result;
    }
    private void reserveCourt(JsonObject n,List<JsonObject> members) {
        if(!str(n,"algorithm").equals("COURTYARD"))return;
        JsonObject l=n.getAsJsonObject("layout"),space=new JsonObject();BlockPoint c=point(n.getAsJsonObject("anchorBlock"));
        space.addProperty("nodeId",optional(n,"arrayId",optional(n,"compositionId","")));space.addProperty("purpose",optional(l,"purpose","public-space"));
        space.addProperty("x",c.x()-integer(l,"courtWidth",36)/2);space.addProperty("z",c.z()-integer(l,"courtDepth",28)/2);
        space.addProperty("width",integer(l,"courtWidth",36));space.addProperty("depth",integer(l,"courtDepth",28));
        space.addProperty("ownerArrayId",str(members.get(0),"arrayId"));
        JsonArray reserved=arr(members.get(0),"reservedSpaces").deepCopy();reserved.add(space);members.get(0).add("reservedSpaces",reserved);
    }
    private List<BlockPoint> rectangleOrigins(JsonObject n,List<int[]> sizes) {
        JsonObject l=n.getAsJsonObject("layout");int gap=integer(l,"gapBlocks",8);BlockPoint anchor=point(n.getAsJsonObject("anchorBlock"));
        if(gap<4||gap>40)throw new IllegalArgumentException("Rectangle gap must be 4..40");
        String algorithm=str(n,"algorithm");List<BlockPoint> out=new ArrayList<>();
        if(algorithm.equals("COURTYARD")) {
            if(sizes.size()>4)throw new IllegalArgumentException("Rectangle COURTYARD supports up to four sides; nest groups for additional buildings");
            int w=integer(l,"courtWidth",36),d=integer(l,"courtDepth",28),left=anchor.x()-w/2,top=anchor.z()-d/2;
            // Open corners provide access; each envelope is entirely outside the reserved central rectangle.
            for(int i=0;i<sizes.size();i++){int[] s=sizes.get(i);int x,z;
                if(i==0){x=anchor.x()-s[0]/2;z=top-gap-s[1];}
                else if(i==1){x=left-gap-s[0];z=anchor.z()-s[1]/2;}
                else if(i==2){x=left+w+gap;z=anchor.z()-s[1]/2;}
                else{x=anchor.x()-s[0]/2;z=top+d+gap;}
                out.add(new BlockPoint(x,z));
            }
            // Child envelopes can exceed the void length. Expand side gaps until corner overlap is gone.
            for(int attempt=0;attempt<512;attempt++) {
                boolean collision=false;
                for(int i=0;i<out.size();i++)for(int j=0;j<i;j++){
                    BlockPoint a=out.get(i),b=out.get(j);int[] sa=sizes.get(i),sb=sizes.get(j);
                    if(a.x()<b.x()+sb[0]+gap&&a.x()+sa[0]+gap>b.x()&&a.z()<b.z()+sb[1]+gap&&a.z()+sa[1]+gap>b.z()){
                        collision=true;
                        if(i==1)out.set(i,new BlockPoint(a.x()-1,a.z()));else if(i==2)out.set(i,new BlockPoint(a.x()+1,a.z()));else out.set(i,new BlockPoint(a.x(),a.z()+1));
                    }
                }
                if(!collision)return out;
            }
            throw new IllegalArgumentException("Courtyard envelopes exceed packing budget");
        }
        if(!algorithm.equals("GRID")&&!algorithm.equals("LINEAR"))throw new IllegalArgumentException("Explicit rectangle layout requires GRID, LINEAR or COURTYARD");
        int columns=algorithm.equals("LINEAR")?sizes.size():integer(l,"columns",2),rows=(sizes.size()+columns-1)/columns;
        if(columns<1||columns>180)throw new IllegalArgumentException("Invalid packing columns");
        boolean vertical=integer(n,"directionDegrees",0)%180!=0;
        int[] widths=new int[columns],depths=new int[rows];
        for(int i=0;i<sizes.size();i++){int[] s=sizes.get(i);widths[i%columns]=Math.max(widths[i%columns],s[vertical?1:0]);depths[i/columns]=Math.max(depths[i/columns],s[vertical?0:1]);}
        for(int i=0;i<sizes.size();i++){int x=0,z=0;for(int c=0;c<i%columns;c++)x+=widths[c]+gap;for(int r=0;r<i/columns;r++)z+=depths[r]+gap;
            if(algorithm.equals("LINEAR")){int extra=depths[0]-sizes.get(i)[vertical?0:1];String align=optional(l,"crossAlign","START");z+=align.equals("END")?extra:align.equals("CENTER")?extra/2:0;}
            int degrees=Math.floorMod(integer(n,"directionDegrees",0),360);
            int dx=vertical?z:x,dz=vertical?x:z;
            if(degrees==180){dx=-dx-sizes.get(i)[0];dz=-dz-sizes.get(i)[1];}
            if(degrees==270){dx=-dx-sizes.get(i)[0];dz=-dz-sizes.get(i)[1];}
            out.add(new BlockPoint(anchor.x()+dx,anchor.z()+dz));}
        return out;
    }
    private CityBlueprintGroupLayoutPlanner.Proposal proposal(JsonObject n,int index,int span) {
        BlockPoint origin=point(n.getAsJsonObject("anchorBlock"));int degrees=Math.floorMod(integer(n,"directionDegrees",0),360);
        double x=degrees==0?1:degrees==180?-1:0,z=degrees==90?1:degrees==270?-1:0;
        var frame=new CityBlueprintGroupLayoutPlanner.Frame(origin,x,z);
        return planner.propose(str(n,"algorithm"),CityBlueprint.DensityClass.valueOf(optional(n,"densityClass","BALANCED")),seed,optional(n,"arrayId",optional(n,"compositionId","")),index,frame,origin,null,false,span);
    }
    JsonObject run() throws Exception {
        List<JsonObject> members=new ArrayList<>();for(String id:nodes.keySet())if(!composed.contains(id))members.addAll(render(id));
        JsonObject terrain=JsonParser.parseString(Files.readString(Path.of(str(input,"terrainPath")))).getAsJsonObject();JsonObject limit=terrain.getAsJsonObject("planningBounds");
        Map<Long,JsonObject> cells=new HashMap<>();int step=terrain.get("cellStepBlocks").getAsInt();
        for(JsonElement e:terrain.getAsJsonArray("cells")){JsonObject c=e.getAsJsonObject();cells.put(key(c.get("cellX").getAsInt(),c.get("cellZ").getAsInt()),c);}
        // Scene-wide evaluation is independent of array ordering; both sides of a collision are visible.
        for(JsonObject m:members) {
            int[] b=bounds(List.of(m));JsonArray issues=new JsonArray(),collisions=new JsonArray();
            if(b[0]<limit.get("minX").getAsInt()||b[1]<limit.get("minZ").getAsInt()||b[2]>limit.get("maxX").getAsInt()||b[3]>limit.get("maxZ").getAsInt())issues.add("OUTSIDE_D3");
            for(JsonObject other:members)if(other!=m&&overlaps(b,bounds(List.of(other))))collisions.add(str(other,"memberId"));
            if(!collisions.isEmpty())issues.add("COLLISION");
            long water=0,covered=0;double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY,relief=0,slope=0;
            for(int x=Math.floorDiv(b[0],step);x<=Math.floorDiv(b[2],step);x++)for(int z=Math.floorDiv(b[1],step);z<=Math.floorDiv(b[3],step);z++) {
                JsonObject c=cells.get(key(x,z));if(c==null)continue;
                long area=(long)(Math.min(b[2],(x+1)*step-1)-Math.max(b[0],x*step)+1)*(Math.min(b[3],(z+1)*step-1)-Math.max(b[1],z*step)+1);covered+=area;
                if(c.get("water").getAsBoolean())water+=area;
                low=Math.min(low,c.get("elevation").getAsDouble());high=Math.max(high,c.get("elevation").getAsDouble());relief=Math.max(relief,c.get("localRelief").getAsDouble());slope=Math.max(slope,c.get("slope").getAsDouble());
            }
            if(covered<(long)m.get("width").getAsInt()*m.get("depth").getAsInt())issues.add("TERRAIN_COVERAGE_INCOMPLETE");
            JsonObject facts=new JsonObject();facts.addProperty("waterAreaBlocks",water);facts.addProperty("sampledAreaBlocks",covered);facts.addProperty("waterAreaRatio",covered==0?0:(double)water/covered);
            if(covered>0){facts.addProperty("minimumElevation",low);facts.addProperty("maximumElevation",high);facts.addProperty("maximumLocalRelief",relief);facts.addProperty("maximumSlope",slope);}
            JsonArray needs=new JsonArray();if(water>0)needs.add("WATER_INTERFACE_REVIEW");if(covered>0&&(high-low>6||relief>8||slope>6))needs.add("TERRAIN_ENGINEERING_REVIEW");
            m.add("terrainEvidence",facts);m.add("engineeringNeeds",needs);m.add("issues",issues);m.add("collisionsWith",collisions);m.addProperty("status",issues.isEmpty()?(needs.isEmpty()?"planned":"engineering_review"):"conflict");
        }
        JsonArray array=new JsonArray();members.forEach(array::add);JsonObject result=new JsonObject();result.addProperty("schema","city_array_experiment_result.v1");result.addProperty("ok",true);result.addProperty("executionMode","offline_real_java_geometry_d3_diagnostics");result.addProperty("generationReady",false);result.add("members",array);JsonArray spaces=new JsonArray();for(var m:members)for(var e:arr(m,"reservedSpaces"))spaces.add(e);result.add("layoutSpaces",spaces);result.add("scene",input.deepCopy());return result;
    }
    private static int[] bounds(List<JsonObject> members){int[] b={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};for(JsonObject m:members){JsonObject p=m.getAsJsonObject("originBlock");int x=p.get("x").getAsInt(),z=p.get("z").getAsInt();b[0]=Math.min(b[0],x);b[1]=Math.min(b[1],z);b[2]=Math.max(b[2],x+m.get("width").getAsInt()-1);b[3]=Math.max(b[3],z+m.get("depth").getAsInt()-1);}return b;}
    private static boolean overlaps(int[] a,int[] b){return a[0]<=b[2]&&a[2]>=b[0]&&a[1]<=b[3]&&a[3]>=b[1];}
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private static JsonArray arr(JsonObject o,String k){return o.has(k)?o.getAsJsonArray(k):new JsonArray();}
    private static String str(JsonObject o,String k){if(o==null||!o.has(k)||!o.get(k).isJsonPrimitive()||o.get(k).getAsString().isBlank())throw new IllegalArgumentException("Required string: "+k);return o.get(k).getAsString();}
    private static String optional(JsonObject o,String k,String d){return o.has(k)?str(o,k):d;}
    private static int integer(JsonObject o,String k,int d){if(!o.has(k))return d;double v=o.get(k).getAsDouble();if(!Double.isFinite(v)||v!=Math.rint(v)||v<Integer.MIN_VALUE||v>Integer.MAX_VALUE)throw new IllegalArgumentException("Integer required: "+k);return (int)v;}
    private static int positive(JsonObject o,String k,int max){int v=integer(o,k,0);if(v<1||v>max)throw new IllegalArgumentException("Invalid positive value: "+k);return v;}
    private static BlockPoint point(JsonObject o){if(o==null||!o.has("x")||!o.has("z"))throw new IllegalArgumentException("anchorBlock{x,z} required");return new BlockPoint(integer(o,"x",0),integer(o,"z",0));}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("request.json output.json required");
        JsonObject result;
        try {result=new CityArrayExperiment(JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject()).run();}
        catch(IllegalArgumentException ex){result=new JsonObject();result.addProperty("ok",false);result.addProperty("error",ex.getMessage());}
        Path out=Path.of(args[1]);Files.createDirectories(out.toAbsolutePath().getParent());Files.writeString(out,JSON.toJson(result));
    }
}
