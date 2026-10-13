from pathlib import Path
p=Path('tools/city_experiments/java/com/rinsing/geomantia/systems/city/application/DesignedStreetWorkbenchAdapter.java');s=p.read_text(encoding='utf-8').replace('        return CityStreetObstacleRouter.repair(bands,anchors,domain);', '''        Set<String> leafIds=new HashSet<>();for(var m:members)leafIds.add(m.get("arrayId").getAsString());
        for(var band:bands){String node=band.get("roadNetworkId").getAsString().replace("::public_space","");
            if(!leafIds.contains(node))band.addProperty("roadHierarchy","MAIN");}
        return CityStreetObstacleRouter.repair(bands,anchors,domain);''');p.write_text(s,encoding='utf-8')
