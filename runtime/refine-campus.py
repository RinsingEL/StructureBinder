from pathlib import Path
import json
p=Path('tools/city_experiments/array_tools.py');s=p.read_text(encoding='utf-8').replace("'faceInward':{'type':'boolean'},", "'faceInward':{'type':'boolean'},\n 'crossAlign':{'type':'string','enum':['START','CENTER','END']},");p.write_text(s,encoding='utf-8')
p=Path('tools/city_experiments/java/com/rinsing/geomantia/systems/city/application/CityArrayExperiment.java');s=p.read_text(encoding='utf-8');s=s.replace('out.add(new BlockPoint(anchor.x()+(vertical?z:x),anchor.z()+(vertical?x:z)));', '''if(algorithm.equals("LINEAR")){int extra=depths[0]-sizes.get(i)[vertical?0:1];String align=optional(l,"crossAlign","START");z+=align.equals("END")?extra:align.equals("CENTER")?extra/2:0;}
            int degrees=Math.floorMod(integer(n,"directionDegrees",0),360);
            int dx=vertical?z:x,dz=vertical?x:z;
            if(degrees==180){dx=-dx-sizes.get(i)[0];dz=-dz-sizes.get(i)[1];}
            if(degrees==270){dx=-dx-sizes.get(i)[0];dz=-dz-sizes.get(i)[1];}
            out.add(new BlockPoint(anchor.x()+dx,anchor.z()+dz));''');p.write_text(s,encoding='utf-8')
p=Path('tools/city_experiments/campus-groups.json');s=json.loads(p.read_text(encoding='utf-8'))
for a in s['arrays']:
 if a['arrayId']=='services':a['layout']['crossAlign']='END'
 if a['arrayId'] in ('theory-classrooms','practice-classrooms'):a['rotation']=270
p.write_text(json.dumps(s,ensure_ascii=False,indent=2),encoding='utf-8')
p=Path('tools/city_experiments/workbench.py');s=p.read_text(encoding='utf-8').replace("if k in p and p[k] not in registry:raise ValueError('未知绿化材质：'+p[k])", "if k in p and p[k] not in registry:raise ValueError('未知绿化材质：'+p[k])\n        for k in ('hedgeBlock','treeLeaves'):\n            if k in p and not p[k].endswith('_leaves'):raise ValueError('绿篱与树冠需要叶片材质。')");p.write_text(s,encoding='utf-8')
