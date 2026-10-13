from pathlib import Path
p=Path('tools/city_experiments/java/com/rinsing/geomantia/systems/city/application/CityCampusExperiment.java')
s=p.read_text(encoding='utf-8')
s=s.replace('if(local.size()<2||algorithm.equals("CONTIGUOUS"))continue;', 'if(local.size()<2||algorithm.equals("CONTIGUOUS")||node.has("layout"))continue;')
s=s.replace('platforms.put(id,footprints);cores.put(id,footprint(core));','''List<BlockBounds> support=new ArrayList<>(footprints);
            for(var space:objects(result.getAsJsonArray("layoutSpaces")))if(id.equals(arrayTerrace.get(text(space,"ownerArrayId")))) {
                int sx=space.get("x").getAsInt(),sz=space.get("z").getAsInt();support.add(new BlockBounds(sx,sz,sx+space.get("width").getAsInt()-1,sz+space.get("depth").getAsInt()-1));
            }
            platforms.put(id,support);cores.put(id,footprint(core));''')
s=s.replace('if(!input.has("greenery")||input.get("greenery").getAsBoolean())surface=', 'if(!input.has("publicSpaces")&&(!input.has("greenery")||input.get("greenery").getAsBoolean()))surface=')
p.write_text(s,encoding='utf-8')
p=Path('tools/city_experiments/workbench.py')
s=p.read_text(encoding='utf-8')
s=s.replace("'seed':{'type':'integer'},'greenery':{'type':'boolean'},", """'seed':{'type':'integer'},'greenery':{'type':'boolean'},
    'publicSpaces':{'type':'array','maxItems':32,'items':schema({
        'nodeId':{'type':'string'},'kind':{'type':'string','enum':['GARDEN','PLAZA']},
        'flowerBlock':{'type':'string'},'hedgeBlock':{'type':'string'},'treeLeaves':{'type':'string'}},['nodeId','kind'])},""")
s=s.replace("    fixture_checks=[]", """    from designed_landscape import apply_public_spaces
    result['publicSpacePlans']=apply_public_spaces(result, cells, sources, put)
    fixture_checks=[]""")
s=s.replace("fixtures=result.get('fixtures',[]),fixtureChecks=", "publicSpacePlans=result.get('publicSpacePlans',[]),layoutSpaces=result.get('layoutSpaces',[]),fixtures=result.get('fixtures',[]),fixtureChecks=")
s=s.replace("{'id':'original','name':'原始学院示范'", "{'id':'campus','name':'学院 · 公共广场与嵌套组团','scene':read(Path(__file__).with_name('campus-groups.json'))},\n            {'id':'original','name':'原始学院示范'")
# Validate decoration block ids and unique node contracts.
s=s.replace("    for a in scene['arrays']:\n", """    nodes={n.get('arrayId',n.get('compositionId')) for n in scene['arrays']+scene['compositions']}
    public=scene.get('publicSpaces',[])
    if len({p['nodeId'] for p in public})!=len(public):raise ValueError('公共空间重复。')
    for p in public:
        if p['nodeId'] not in nodes:raise ValueError('公共空间引用未知组团。')
        for k in ('flowerBlock','hedgeBlock','treeLeaves'):
            if k in p and p[k] not in registry:raise ValueError('未知绿化材质：'+p[k])
    for a in scene['arrays']:
""")
p.write_text(s,encoding='utf-8')
