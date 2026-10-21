"""Four independently composed silhouettes after the user rejected batch styling.

Shared helpers are joinery and furniture, never a whole-house template.
"""
from functools import partial
import json,argparse
from .model import Model
from .server import TOOL,CATALOG
from .validate import validate
from .european_village import finalize,bed,station,seat,stair


def new(key,name,size,terms):
    m=Model(key,name,size,family=key.rsplit('-v',1)[0],civilization='欧洲中世纪新制',role='fill')
    m.meta.update(function_terms=list(terms),workplace_policy='authored',
        source='tools/structure_studio/studio/european_character.py:BUILDERS',
        ground_plane={'y':2,'note':'陆路入口脚底Y=2，抬高的使用层有实体台阶。'},
        preview_context={'kind':'flat','land_surface_y':2,'padding':3},
        terrain={'选址':'屋体基础需稳定承载；屋檐、外挂构件和门前使用面须保留。'},
        floors=[{'name':'首层','y':1,'max_y':5}],roof_min_y=6,
        design_notes=['针对用户“缺少特色”的反馈独立重设计；此前批量风格结论不继承。'],
        differences=[])
    return m


def enclosed(m,b,y,height,wall,wood='dark_oak'):
    x0,z0,x1,z1=b
    if y==1:m.box((x0,0,z0),(x1,0,z1),'cobblestone')
    m.box((x0,y,z0),(x1,y,z1),'spruce_planks')
    m.box((x0,y+1,z0),(x1,y+height,z1),wall)
    m.box((x0+1,y+1,z0+1),(x1-1,y+height,z1-1),'air')
    for x in (x0,x1):
        for z in (z0,z1):m.box((x,y+1,z),(x,y+height,z),wood+'_log')
    for z in (z0,z1):m.box((x0,y+height,z),(x1,y+height,z),wood+'_log[axis=x]')
    for x in (x0,x1):m.box((x,y+height,z0),(x,y+height,z1),wood+'_log[axis=z]')


def glazed(m,x,y,z,w=2,h=2,axis='x',shutters=True):
    for i in range(w):
        for dy in range(h):m.set(x+i if axis=='x' else x,y+dy,z if axis=='x' else z+i,'glass_pane')
        m.set(x+i if axis=='x' else x,y-1,z if axis=='x' else z+i,'stripped_oak_log[axis='+axis+']')
    if shutters:
        for i in (-1,w):
            for dy in range(h):
                m.set(x+i if axis=='x' else x,y+dy,z if axis=='x' else z+i,'spruce_trapdoor[open=true,facing='+('north' if axis=='x' else 'west')+']')


def lantern(m,x,y,z):
    m.set(x,y+1,z,'dark_oak_log[axis=x]');m.set(x,y,z,'lantern[hanging=true]')


def door(m,x,z,y=2):
    m.door(x,y,z,facing='south');m.set(x,y-1,z-1,'cobblestone')
    m.point('entry','entrance',(x,y,z-1),'陆路主入口',facing='north')


def gable(m,x0,x1,z0,z1,eave,material='dark_oak',infill='white_terracotta'):
    mid=(x0+x1)//2
    for x in range(x0,x1+1):
        y=eave+min(x-x0,x1-x)
        m.box((x,y,z0),(x,y,z1),material+'_stairs[facing='+('east' if x<=mid else 'west')+']')
        for z in (z0+1,z1-1):
            if y>eave:m.box((x,eave,z),(x,y-1,z),infill)
    for z in (z0+1,z1-1):
        m.box((mid,eave,z),(mid,eave+(x1-x0)//2-1,z),'dark_oak_log')
    m.box((mid,eave+(x1-x0)//2+1,z0),(mid,eave+(x1-x0)//2+1,z1),material+'_slab')


def cottage():
    m=new('EU-27-v05','灶屋小宅 · 偏心烟囱与低檐侧屋',(14,19,15),('家庭居住',))
    enclosed(m,(2,3,9,12),1,4,'white_terracotta','oak')
    # A low working wing actually changes the plan and the roof cross section.
    enclosed(m,(9,7,12,12),1,2,'spruce_planks','oak')
    m.box((9,2,8),(9,3,10),'air')
    profile=[6,7,8,9,10,10,9,8,7,6,5,4,4]
    for x,y in enumerate(profile):
        m.box((x,y,2),(x,y,13),'spruce_stairs[facing='+('east' if x<5 else 'west')+']')
        for z in (3,12):
            if 2<=x<=9 and y>5:m.box((x,6,z),(x,y-1,z),'white_terracotta') if y>6 else None
            if y>=7:m.set(x,y-1,z,'oak_log[axis=x]')
    m.box((5,11,2),(5,11,13),'spruce_slab')
    for z in (3,12):
        m.box((3,6,z),(8,6,z),'oak_log[axis=x]')
        for x,y in ((3,7),(4,8),(6,8),(7,7)):m.set(x,y,z,'oak_wood')
    # Short dormer gives the long roof a worked, asymmetric profile.
    m.box((6,7,5),(8,8,7),'oak_planks');m.box((7,7,5),(7,8,5),'glass_pane')
    m.box((5,9,4),(9,9,8),'spruce_slab[type=top]')
    door(m,6,3);glazed(m,3,3,3,2,2,shutters=False);glazed(m,2,3,6,2,2,axis='z')
    m.box((3,5,3),(4,5,3),'oak_log[axis=x]')
    for x in (3,5):bed(m,x,2,10,f'bed_{x}',color='brown')
    # A massive exterior chimney has a real fireplace at its base.
    m.box((1,0,9),(2,13,10),'cobblestone')
    m.box((1,8,9),(2,10,10),'mossy_cobblestone')
    m.box((0,14,8),(3,14,11),'stone_brick_slab')
    m.set(2,2,9,'smoker[facing=east]');m.set(3,2,5,'barrel[facing=east]')
    m.set(11,2,9,'crafting_table');m.set(11,2,11,'barrel[facing=west]')
    seat(m,7,2,6,'west',use='resident');m.set(5,2,6,'oak_slab[type=top]')
    lantern(m,6,5,8)
    m.box((10,1,4),(12,1,5),'dirt');m.set(11,2,4,'azalea')
    m.room('home','床与灶边起居',(3,2,4),(8,5,11),'小户住宅，灶台与起居相连')
    m.room('lean_to','低檐储物侧屋',(10,2,8),(11,3,11),'生活工具与储藏，侧屋低于主屋')
    m.meta['differences']=['不对称长短坡屋顶、低檐侧屋、偏心石烟囱、短老虎窗；实际房间从侧屋连接。']
    return m


def tavern():
    m=new('EU-32-v02','铜壶酒馆 · 挑楼客房与侧炉厨房',(20,27,23),('餐饮','饮酒会聚','旅宿'))
    enclosed(m,(3,4,13,19),1,4,'stone_bricks')
    enclosed(m,(2,3,14,20),6,4,'white_terracotta')
    # Jettied upper storey supported by visible transverse beams and corbels.
    for x in (3,6,10,13):
        m.box((x,5,3),(x,5,20),'dark_oak_log[axis=z]')
        m.set(x,5,2,'dark_oak_stairs[facing=south,half=top]')
    for z in (4,8,12,16,19):
        m.set(2,5,z,'dark_oak_stairs[facing=east,half=top]');m.set(14,5,z,'dark_oak_stairs[facing=west,half=top]')
    gable(m,1,15,2,21,11)
    for z in (3,20):
        for x in (2,5,9,14):m.box((x,7,z),(x,10,z),'dark_oak_log')
        for x,y in ((3,8),(4,9),(10,9),(11,8),(12,7)):m.set(x,y,z,'dark_oak_wood')
        for x,y in ((4,12),(5,13),(6,14),(10,14),(11,13),(12,12)):m.set(x,y,z,'dark_oak_wood')
        m.box((7,12,z),(9,13,z),'glass_pane')
        m.box((7,11,z),(9,11,z),'dark_oak_log[axis=x]')
    for x in (3,10):glazed(m,x,8,3,3,2,shutters=False)
    # Bay dining window, awning and suspended copper sign address the street.
    m.box((4,1,2),(7,2,3),'stone_bricks');m.box((4,3,2),(7,4,2),'glass_pane')
    m.box((3,5,1),(8,5,3),'dark_oak_slab[type=top]')
    m.box((12,6,0),(12,6,3),'dark_oak_log[axis=z]');m.set(12,5,0,'chain');m.set(12,4,0,'cut_copper')
    door(m,10,4)
    stair(m,12,8)
    for x in (4,7):
        for z in (7,16):bed(m,x,7,z,f'guest_{x}_{z}',use='public',color='green')
    m.box((9,7,4),(9,9,19),'spruce_planks');m.box((9,7,14),(9,8,14),'air')
    # Low rear kitchen wing with a separate oven mass and staff bed.
    enclosed(m,(13,11,18,19),1,4,'bricks')
    for x in range(13,20):
        yy=8-(x-13)//2;m.box((x,yy,10),(x,yy,20),'spruce_planks')
    m.box((13,2,13),(13,3,15),'air')
    m.box((17,2,17),(18,11,18),'bricks');m.box((16,12,16),(19,12,19),'brick_slab')
    station(m,17,2,15,'smoker[facing=west]','厨师',(16,2,15));bed(m,15,2,17,'staff',use='staff',color='brown')
    station(m,4,2,13,'barrel[facing=east]','酒保')
    m.box((4,2,11),(6,2,11),'dark_oak_slab[type=top]')
    for z in (7,16):
        seat(m,7,2,z,'east');m.set(9,2,z,'oak_slab[type=top]')
    for y in (5,10):
        for z in (7,15):lantern(m,7,y,z)
    lantern(m,15,5,13)
    m.room('taproom','酒馆大厅与吧台',(4,2,5),(12,5,18),'街面凸窗、餐桌、酒桶与后勤通路')
    m.room('guest','挑楼客房',(3,7,4),(13,10,19),'客床与公共楼梯分开')
    m.room('kitchen','低厨房翼',(14,2,12),(17,5,18),'烹饪、备料及员工休息')
    m.meta.update(floors=[{'name':'酒馆与厨房','y':1,'max_y':5},{'name':'挑楼客房','y':6,'max_y':10}],roof_min_y=11,
        differences=['挑楼、街面凸窗与铜壶招牌、独立低厨房和砖烟囱；酒馆体量按经营空间组织。'])
    return m


def smithy():
    m=new('EU-07-v02','山口铁匠铺 · 石炉背墙与锯齿敞棚',(18,23,17),('锻造','修理'))
    # A fireproof stone furnace block, not a plaster dwelling with a forge inside.
    m.box((2,0,2),(9,1,14),'stone_bricks')
    m.box((2,2,2),(9,5,3),'stone_bricks')
    m.box((4,2,2),(9,5,3),'air')
    m.box((2,2,3),(3,5,14),'stone_bricks')
    m.box((2,2,13),(9,5,14),'stone_bricks')
    m.box((4,2,11),(8,4,13),'bricks');m.box((5,2,10),(7,3,11),'air')
    m.set(6,2,11,'blast_furnace[facing=north]')
    # Hood narrows into a squat industrial chimney; side buttresses read in silhouette.
    for y,x0,x1 in ((5,4,8),(6,4,8),(7,5,7),(8,5,7),(9,5,7),(10,6,6),(11,6,6)):
        m.box((x0,y,11),(x1,y,13),'bricks')
    m.box((5,12,10),(7,12,14),'stone_brick_slab')
    # Wide work apron is roofed by a lower open timber extension.
    m.box((9,0,3),(16,1,14),'cobblestone')
    for x in (9,16):
        for z in (3,8,14):m.box((x,2,z),(x,6,z),'stripped_spruce_log')
    for z in (3,8,14):
        m.box((2,6,z),(16,6,z),'spruce_log[axis=x]')
        for x,y in ((14,5),(15,4)):m.set(x,y,z,'spruce_wood')
    for x in range(1,18):
        yy=9-(x//5)
        m.box((x,yy,1),(x,yy,15),'dark_oak_planks')
    # Re-open the chimney through the roof; roof meets brick, no arbitrary attic.
    m.box((5,7,11),(7,10,13),'bricks')
    station(m,6,2,11,'blast_furnace[facing=north]','锻炉操作',(6,2,9))
    station(m,11,2,7,'anvil[facing=north]','砧台锻造',(12,2,7))
    m.set(14,2,11,'water_cauldron[level=3]');m.set(14,2,5,'grindstone[face=floor,facing=north]')
    m.box((3,2,5),(3,3,8),'barrel[facing=east]')
    m.box((10,2,13),(12,2,14),'iron_block')
    m.box((4,1,0),(13,1,2),'gravel');m.point('entry','entrance',(11,2,1),'开放修理与牵马面',facing='north')
    lantern(m,11,5,4);lantern(m,5,5,6)
    m.room('forge','石炉与储料',(4,2,4),(8,5,12),'砖砌锻炉和耐火背墙')
    m.room('apron','敞开锻修棚',(10,2,4),(15,6,13),'砧台、淬水与搬运面')
    m.meta.update(roof_min_y=7,differences=['开放生产棚取代住宅外壳；砖炉罩、侧撑木架和宽作业口主导轮廓。'])
    return m


def warehouse():
    m=new('EU-55-v02','河岸仓房 · 高台吊货楼与装卸廊',(16,25,22),('货物仓储','水陆转运'))
    # Raised masonry store supports a timber upper granary, not a small cottage.
    m.box((3,0,4),(12,3,18),'stone_bricks')
    enclosed(m,(3,4,12,18),3,5,'stripped_spruce_log','spruce')
    enclosed(m,(3,4,12,18),9,4,'spruce_planks','spruce')
    gable(m,2,13,3,19,14,'stone_brick','spruce_planks')
    for z in (4,18):
        for x in (3,6,9,12):m.box((x,4,z),(x,13,z),'spruce_log')
        for x,y in ((4,5),(5,6),(10,6),(11,5),(4,11),(5,12),(10,12),(11,11)):m.set(x,y,z,'dark_oak_wood')
    # Front stone stairs raise land entry by two blocks.
    for x in (6,7,8):
        for z,y in ((0,1),(1,2),(2,3)):m.set(x,y,z,'stone_brick_stairs[facing=south]')
        m.set(x,3,3,'stone_bricks')
    m.box((6,4,4),(8,6,4),'air');m.point('entry','entrance',(7,2,0),'岸上低街入口')
    # Upper loading hatch and projecting hoist with diagonal braces.
    m.box((6,10,4),(8,12,4),'air')
    m.box((7,14,0),(7,14,6),'dark_oak_log[axis=z]')
    m.set(7,13,3,'dark_oak_wood');m.set(7,12,4,'dark_oak_wood')
    m.box((7,8,1),(7,13,1),'chain');m.set(7,7,1,'tripwire_hook[facing=south]')
    # Side dock is a narrow working extension with real piles and a single height.
    m.box((13,3,5),(15,3,20),'spruce_planks')
    for z in (5,12,20):m.box((15,0,z),(15,5,z),'spruce_log')
    for z in range(6,20):m.set(15,4,z,'spruce_fence')
    m.box((12,4,9),(12,6,11),'air')
    stair(m,11,6,3)
    # Six rises, the utility above intentionally lands one level above the basic flight.
    m.set(11,9,11,'spruce_stairs[facing=south]');m.set(11,9,12,'spruce_planks')
    m.box((11,10,11),(11,12,12),'air')
    m.meta['points']=[p for p in m.meta['points'] if p['kind']!='circulation']
    m.point('upper','circulation',(11,10,12),'上层装卸平台')
    for y in (4,10):
        for z in (7,13,16):m.box((4,y,z),(5,y+1,z),'barrel[facing=east]')
    station(m,8,4,16,'lectern[facing=north]','仓管登记',(8,4,15))
    station(m,14,4,17,'barrel[facing=north]','装卸理货',(14,4,16))
    for z in (7,15):lantern(m,7,7,z);lantern(m,7,12,z)
    for z in (8,15):glazed(m,3,6,z,2,1,axis='z',shutters=False)
    m.room('store','下层仓储',(4,4,5),(11,8,17),'高台仓储与侧向装卸口')
    m.room('loft','上层轻货仓',(4,10,5),(11,13,17),'货架、吊货窗与实体楼梯')
    m.meta.update(floors=[{'name':'高台仓储','y':3,'max_y':8},{'name':'上层货仓','y':9,'max_y':13}],roof_min_y=14,
        terrain={'选址':'正面街脚底Y=2，经台阶到仓内及侧廊Y=4；侧廊向+X临水，桩底Y=0须承载。吊架前方净空保留。'},
        differences=['高台仓体、上层吊货窗和悬臂吊架、窄侧装卸廊；屋体功能由岸上到水侧的货物流线决定。'])
    return m


BUILDERS={key:partial(finalize,fn) for key,fn in {
    'EU-27-v05':cottage,'EU-32-v02':tavern,'EU-07-v02':smithy,'EU-55-v02':warehouse}.items()}


def main():
    p=argparse.ArgumentParser();p.add_argument('ids',nargs='*');args=p.parse_args()
    registry=json.loads((TOOL/'.cache/registry.json').read_text(encoding='utf-8'))
    failed=False
    for key in args.ids or BUILDERS:
        m=BUILDERS[key]();dest=CATALOG/'R04_european_reborn/models'/key;m.export(dest,registry)
        r=validate(dest,registry);print(json.dumps({'id':key,'size':m.size,'errors':r['errors'],'warnings':r['warnings'],'navigation':r['navigation']},ensure_ascii=False),flush=True)
        failed|=not r['passed'] or bool(r['warnings']) or not r['navigation']['passed']
    raise SystemExit(int(failed))


if __name__=='__main__':main()
