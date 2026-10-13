from pathlib import Path
p=Path('tools/city_experiments/array_tools.py')
s=p.read_text(encoding='utf-8')
pos=s.index('\ndef schema(')
s=s[:pos]+'''\n# Explicit geometry is opt-in; legacy production proposals remain unchanged.
LAYOUT = {'type':'object','properties':{
 'gapBlocks':{'type':'integer','minimum':4,'maximum':40},
 'columns':{'type':'integer','minimum':1,'maximum':12},
 'courtWidth':{'type':'integer','minimum':12,'maximum':160},
 'courtDepth':{'type':'integer','minimum':12,'maximum':160},
 'faceInward':{'type':'boolean'},
 'purpose':{'type':'string'}},'required':['gapBlocks'],'additionalProperties':False}
ARRAY['properties']['layout']=LAYOUT
COMPOSITION['properties']['layout']=LAYOUT
''' +s[pos:]
p.write_text(s,encoding='utf-8')
p=Path('tools/city_experiments/java/com/rinsing/geomantia/systems/city/application/CityArrayExperiment.java')
s=p.read_text(encoding='utf-8')
s=s.replace('List<JsonObject> result=new ArrayList<>();\n        if (str(n,"algorithm")','''if(n.has("layout")) return packedComposition(n,children);
        List<JsonObject> result=new ArrayList<>();
        if (str(n,"algorithm")''',1)
s=s.replace('int span=1;List<CityContiguousLayoutPlanner.Size> sizes=', '''if(n.has("layout")) return packedLeaf(n,chosen);
        int span=1;List<CityContiguousLayoutPlanner.Size> sizes=''',1)
pos=s.index('    private CityBlueprintGroupLayoutPlanner.Proposal proposal')
s=s[:pos]+'''    /** Experimental rectangle packing: actual envelopes, explicit void and per-template fronts. */
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
            out.add(new BlockPoint(anchor.x()+(vertical?z:x),anchor.z()+(vertical?x:z)));}
        return out;
    }
''' +s[pos:]
s=s.replace('translatePoints(copy.get("proposalTrace"), origin.x()-b[0], origin.z()-b[1]);','translatePoints(copy.get("proposalTrace"), origin.x()-b[0], origin.z()-b[1]);\n                translatePoints(copy.get("reservedSpaces"), origin.x()-b[0], origin.z()-b[1]);')
s=s.replace('result.add("members",array);result.add("scene",input.deepCopy());', 'result.add("members",array);JsonArray spaces=new JsonArray();for(var m:members)for(var e:arr(m,"reservedSpaces"))spaces.add(e);result.add("layoutSpaces",spaces);result.add("scene",input.deepCopy());')
p.write_text(s,encoding='utf-8')
