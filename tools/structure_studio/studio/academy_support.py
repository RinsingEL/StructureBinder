"""Small and medium academy structures for nested campus composition. Real, reproducible NBT."""
from pathlib import Path
import json
from .arcane_reborn_parts import rect, gable, arch
from .model import Model

CATALOG_DIR = 'R02_arcane_reborn'


def base(number, name, w, h, d, terms, *, landscape=False, consumer='array'):
    m = Model(f'MG-{number:02d}-v01', name, (w,h,d), family=f'MG-{number:02d}', civilization='魔法学院新制', role='fill')
    m.box((0,0,0),(w-1,0,d-1),'stone_bricks')
    m.box((0,1,0),(w-1,1,d-1),'grass_block')
    m.meta.update(function_terms=terms, asset_tags=['landscape'] if landscape else [],
        source='tools/structure_studio/studio/academy_support.py',
        ground_plane={'y':2,'note':'外部路面脚底为 Y=2。'},
        terrain={'选址':'完整承载面；农林地块保持自然地形，独立建筑使用核心台地。'},
        floors=[{'name':'首层','y':1,'max_y':8}], roof_min_y=10,
        preview_context={'kind':'flat','land_surface_y':2,'padding':3,'surface':'grass'},
        campus_consumer=consumer, design_revision=1,
        design_notes=['学院组团配套；尺寸独立于大型文化核心。实际生产、居民和魔法行为另行配置。'])
    return m


def house(number, name, w, d, terms, *, medium=False, furnishing='classroom'):
    m=base(number,name,w,32 if medium else 24,d,terms)
    rect(m,3,4,w-4,d-4,top=10 if medium else 8)
    gable(m,3,4,w-4,d-4,11 if medium else 9)
    cx=w//2
    for wall_z in (4,d-4):
        for window_x in (7,w-8):arch(m,window_x,wall_z,3,3,4)
        arch(m,cx,wall_z,11 if medium else 9,3,4)
    # Narrow facades share window jambs with the entrance. The doorway owns its final clearance.
    arch(m,cx,4,2,3,6,glass=False)
    m.box((cx-2,1,0),(cx+2,1,4),'polished_andesite')
    for z in range(7,d-5,5):
        arch(m,z,3,3,3,4,axis='z')
        arch(m,z,w-4,3,3,4,axis='z')
    m.point('front','entrance',(cx,2,0),'主入口',approach=(cx,2,2),direction='north')
    m.point('main_access','circulation',(cx,2,5),'室内通路',approach=(cx,2,5))
    m.room('main',name,(4,2,5),(w-5,7,d-5),'、'.join(terms))
    if furnishing=='classroom':
        m.box((5,2,d-6),(w-6,4,d-6),'bookshelf')
        for x in range(6,w-5,4):
            for z in range(8,d-7,4):
                m.set(x,2,z,'oak_fence');m.set(x,3,z,'dark_oak_pressure_plate')
                m.set(x,2,z-1,'dark_oak_stairs[facing=south]')
        m.point('teach','work',(cx,2,d-8),'教学讲台')
    elif furnishing=='dorm':
        for x in (5,7,w-8,w-6):
            for z in range(6,d-6,3):
                m.set(x,2,z,'purple_bed[facing=south,part=foot]');m.set(x,2,z+1,'purple_bed[facing=south,part=head]')
        m.box((cx,2,d-6),(cx+1,4,d-6),'bookshelf')
    elif furnishing=='dining':
        for z in range(8,d-6,5):
            m.box((7,2,z),(w-8,2,z),'oak_fence');m.box((7,3,z),(w-8,3,z),'dark_oak_slab[type=top]')
            for x in (6,w-7):m.point(f'dining_{x}_{z}','meeting',(x,2,z),'用餐席位',use='public')
        m.set(5,2,d-6,'furnace');m.set(6,2,d-6,'smoker');m.set(7,2,d-6,'barrel')
    elif furnishing=='shed':
        m.box((5,2,d-6),(w-6,3,d-6),'barrel');m.set(5,2,7,'crafting_table');m.set(w-6,2,7,'composter')
    return m


def small_garden():
    m=base(29,'星花阅读小庭 · 花坛与双侧坐席',17,8,17,['花草观赏','休憩','阅读'],landscape=True)
    m.box((7,1,0),(9,1,16),'polished_andesite')
    m.box((0,1,7),(16,1,9),'polished_andesite')
    for x,z in ((4,4),(12,4),(4,12),(12,12)):
        m.box((x-1,1,z-1),(x+1,1,z+1),'moss_block');m.set(x,2,z,'flowering_azalea')
    for z in (5,11):
        for x in range(5,12):m.set(x,2,z,'dark_oak_stairs[facing='+('south' if z==5 else 'north')+']')
    m.point('front','entrance',(8,2,0),'庭园入口',approach=(8,2,2),direction='north')
    m.point('garden_work','work',(8,2,4),'庭园养护',use='staff')
    m.point('garden_meeting','meeting',(8,2,12),'阅读会面',use='public')
    m.meta['roof_min_y']=None
    return m


def herb_plot():
    m=base(30,'学院试种田 · 四块作物与灌溉沟',19,5,19,['植物教学','种植','药草栽培'],landscape=True)
    m.meta['natural_ground']=True;m.meta['roof_min_y']=None
    m.meta['agriculture']={'irrigated':True,'note':'两侧与中央水沟，所有耕地在 4 格水源内。'}
    for x in (1,9,17):m.box((x,1,2),(x,1,16),'water[level=0]')
    for x in list(range(2,8))+list(range(11,17)):
        for z in range(2,17):
            m.set(x,1,z,'farmland[moisture=7]');m.set(x,2,z,'wheat[age=7]' if x<9 else 'carrots[age=7]')
    m.box((7,1,8),(11,1,10),'oak_slab[type=top]')
    m.box((7,2,8),(11,2,10),'air')
    m.box((8,1,0),(10,1,1),'dirt_path')
    m.point('front','entrance',(9,2,0),'田块入口',approach=(9,2,1),direction='north')
    m.point('field_work','work',(9,2,9),'植物试种管理',use='staff')
    return m


def greenhouse():
    m=base(31,'小型实验温室 · 双跨玻璃肋架',23,16,27,['植物教学','园艺养护','药草栽培'])
    rect(m,3,4,19,23,top=7,material='glass')
    for x in (3,7,11,15,19):
        for z in range(4,24):
            y=8+min(z-4,23-z)//2;m.set(x,y,z,'polished_andesite');
    for x in range(3,20):
        for z in range(4,24):m.set(x,8+min(z-4,23-z)//2,z,'glass')
    m.box((10,2,4),(12,5,4),'air');m.box((10,1,0),(12,1,25),'polished_andesite')
    for x in (6,16):
        for z in range(7,21):m.set(x,2,z,'dirt');m.set(x,3,z,'flowering_azalea' if z%3==0 else 'fern')
    m.point('front','entrance',(11,2,0),'温室入口',approach=(11,2,2),direction='north')
    m.point('greenhouse_work','work',(11,2,6),'温室教学与养护',use='staff')
    return m


def orchard():
    m=base(33,'试验苗圃 · 四株小树与林间通道',23,13,23,['林业教学','苗木培育'],landscape=True)
    m.meta['natural_ground']=True;m.meta['roof_min_y']=None
    m.box((10,1,0),(12,1,22),'dirt_path')
    for x,z in ((5,5),(17,5),(5,17),(17,17)):
        m.box((x,2,z),(x,6,z),'oak_log')
        for y,r in ((5,2),(6,2),(7,1)):
            for dx in range(-r,r+1):
                for dz in range(-r,r+1):
                    if abs(dx)+abs(dz)<=r+1:m.set(x+dx,y,z+dz,'oak_leaves[persistent=true]')
    m.point('front','entrance',(11,2,0),'苗圃入口',approach=(11,2,2),direction='north')
    m.point('nursery_work','work',(11,2,6),'林业教学与养护',use='staff')
    return m


BUILDERS={
 'MG-25':lambda:house(25,'中等教学楼 · 双排课堂与藏书壁',35,31,['教学','阅读','研讨'],medium=True),
 'MG-26':lambda:house(26,'小课堂 · 尖拱教室与门廊',21,21,['教学','研讨']),
 'MG-27':lambda:house(27,'学院宿舍 · 十六床与共享书架',19,23,['学生住宿','自习'],furnishing='dorm'),
 'MG-28':lambda:house(28,'小食堂 · 长桌与备餐间',25,27,['餐饮','集体用餐'],furnishing='dining'),
 'MG-29':small_garden,
 'MG-30':herb_plot,
 'MG-31':greenhouse,
 'MG-32':lambda:house(32,'农林工具房 · 储藏与修整台',17,21,['工具保管','园艺养护','林业教学'],furnishing='shed'),
 'MG-33':orchard,
}


def build_all():
    from .server import CATALOG, TOOL
    registry=json.loads((TOOL/'.cache/registry.json').read_text(encoding='utf-8'))
    for builder in BUILDERS.values():
        model=builder();model.export(CATALOG/CATALOG_DIR/'models'/model.meta['id'],registry)
        print(model.meta['id'],model.size)


if __name__=='__main__':build_all()
