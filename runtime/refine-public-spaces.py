from pathlib import Path
p=Path('tools/city_experiments/designed_landscape.py');s=p.read_text(encoding='utf-8').replace("    if not scene.get('greenery',True):return []", "    green=scene.get('greenery',True)")
s=s.replace("for xa,xb in ((x0+4,cx-5),(cx+5,x0+w-5)):", "for xa,xb in (((x0+4,cx-5),(cx+5,x0+w-5)) if green else ()):")
s=s.replace("for tx,tz in ((x0+5,z0+5),(x0+w-6,z0+5),(x0+5,z0+d-6),(x0+w-6,z0+d-6)):", "for tx,tz in (((x0+5,z0+5),(x0+w-6,z0+5),(x0+5,z0+d-6),(x0+w-6,z0+d-6)) if green else ()):")
s=s.replace('        amenities=[]', '''        for lx,lz in ((x0+2,z0+2),(x0+w-3,z0+2),(x0+2,z0+d-3),(x0+w-3,z0+d-3)):
            if safe(lx,lz,plant=True) and all((lx,yy,lz) not in cells for yy in range(y+2,y+6)):
                for yy in range(y+1,y+4):emit(lx,yy,lz,'minecraft:dark_oak_fence','LIGHT_POST')
                emit(lx,y+4,lz,'minecraft:lantern[hanging=false,waterlogged=false]','LAMP')
        amenities=[]''')
p.write_text(s,encoding='utf-8')
p=Path('tools/city_experiments/workbench.py');s=p.read_text(encoding='utf-8').replace("lamps=sum(1 for row in result['mutations'] if row[3]=='minecraft:lantern')", "lamps=sum(1 for b in world['blocks'] if world['palette'][b['state']]['name']=='minecraft:lantern')");p.write_text(s,encoding='utf-8')
