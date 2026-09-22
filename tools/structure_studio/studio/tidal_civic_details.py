"""Civic-only marine silhouettes and furnishings; shared dwelling helpers stay stable."""
from math import sin, pi
from .model import state
from .components import crate_stack


def arch(m,a,b,y,rise,material='quartz_block'):
    dx,dz=b[0]-a[0],b[1]-a[1]
    length=max(abs(dx),abs(dz))
    heights=[y+round(rise*sin(pi*i/length)) for i in range(length+1)]
    for i,top in enumerate(heights):
        x=round(a[0]+dx*i/length);z=round(a[1]+dz*i/length)
        low=min(top,heights[max(0,i-1)],heights[min(length,i+1)])
        m.box((x,low,z),(x,top,z),material)


def vault(m,x,z,w,d,f=7,h=6,rise=5,glass=False):
    """Closed arched shell with transverse pale ribs and glazed gable ends."""
    bottom=f+h+1
    m.box((x-1,bottom,z-1),(x+w+1,bottom+max(rise,4)+2,z+d+1),'air')
    m.box((x+1,f+h,z+1),(x+w-1,f+h,z+d-1),'air')
    span=w+2
    heights=[bottom+round(rise*sin(pi*i/span)) for i in range(span+1)]
    for i,top in enumerate(heights):
        xx=x-1+i
        low=min(top,heights[max(0,i-1)],heights[min(span,i+1)])
        for zz in range(z-1,z+d+2):
            rib=zz in (z-1,z+d+1) or (zz-z-1)%5==0
            block='quartz_block' if rib else ('cyan_stained_glass' if glass else 'waxed_oxidized_cut_copper')
            m.box((xx,low,zz),(xx,top,zz),block)
        if x<=xx<=x+w:
            for zz in (z,z+d):
                if top>bottom:
                    m.box((xx,bottom,zz),(xx,top-1,zz),'cyan_stained_glass')
    for xx in range(x+1,x+w,4):
        for zz in (z,z+d):
            for yy in range(f+1,f+h+1):
                if m.blocks.get((xx,yy,zz),(None,))[0]=='minecraft:smooth_quartz':
                    m.set(xx,yy,zz,'quartz_pillar[axis=y]')
    for xx in range(x,x+w+1):
        for zz in (z,z+d):
            if m.blocks.get((xx,f+1,zz),(None,))[0]=='minecraft:smooth_quartz':
                m.set(xx,f+1,zz,'dark_prismarine')


def dark_furniture(m):
    """Separate chairs, shelves and work surfaces visually from the pale floor."""
    floor=min(f['y'] for f in m.meta['floors'])
    replacements={'minecraft:birch_planks':'minecraft:stripped_dark_oak_log',
                  'minecraft:birch_slab':'minecraft:dark_oak_slab',
                  'minecraft:birch_stairs':'minecraft:dark_oak_stairs'}
    for p,(name,properties) in list(m.blocks.items()):
        if p[1]>floor and name in replacements:
            m.blocks[p]=(replacements[name],(('axis','x'),) if name.endswith('planks') else properties)


def board(m,x,y,z,w=5):
    m.box((x,y,z),(x+w-1,y+2,z),'dark_prismarine')
    for i in range(1,w-1):
        m.set(x+i,y+1,z,'cyan_terracotta' if i%2 else 'white_terracotta')


def port(m):
    vault(m,5,5,15,16,rise=5)
    vault(m,32,5,17,16,rise=7)
    m.box((20,14,32),(33,14,32),'air')
    arch(m,(20,32),(33,32),14,4)
    for x in (8,39):
        crate_stack(m,x,8,32,4,3,3)
        m.point('sealed_cargo'+str(x),'storage',(x+1,9,33),'待运封箱与托放分区',approach=(x-1,8,33))
    # Dock crane bears on the seabed; its hanging tackle stops above headroom.
    m.box((49,0,35),(49,20,35),'dark_prismarine')
    m.box((42,20,35),(49,20,35),'waxed_oxidized_copper')
    m.box((42,13,35),(42,19,35),'chain[axis=y]')
    m.set(42,12,35,'tripwire_hook[facing=south]')
    m.set(49,9,34,'lever[face=wall,facing=north]')
    m.point('crane_control','work',(49,9,34),'手动吊运绞机静态控制位',approach=(48,8,34))
    board(m,8,10,6,8);board(m,36,10,6,9)
    m.meta['design_notes'].append('双贝壳拱顶区分候船与港务，白色拱形潮门、靠海床承重的吊机和封箱货垛形成港口识别。')


def underwater(m):
    vault(m,4,20,17,20,f=3,rise=3,glass=True)
    vault(m,31,20,17,20,f=3,rise=3,glass=True)
    vault(m,21,28,10,12,f=3,rise=2,glass=True)
    for x in (23,29):
        m.set(x,20,2,'sea_lantern')
    board(m,7,6,21,9)
    for x in (8,11,14):
        m.set(x,5,28,'flower_pot');m.set(x,5,33,'dead_brain_coral_block')
    m.meta['terrain']['干湿区']='海床舱地板Y=3，双拱形透明舱顶最高Y=13；参考水面Y=14，低潮12/高潮15。北端Y=17平台经封闭楼梯接入干舱；低潮可露出部分舱顶，水密与呼吸玩法另验证。'
    m.meta['design_notes'].append('低矮透明拱舱以白肋分段，研究、装备和生活分舱；海洋标本置于实际操作台面，不与餐饮混放。')


def school(m):
    vault(m,5,6,21,14,rise=6)
    vault(m,5,25,13,16,rise=4)
    vault(m,32,6,16,25,h=8,rise=7)
    board(m,10,10,7,12)
    for x in (9,11,14):
        m.set(x,9,28,'flower_pot')
    # Multi-height display against the specimen wing's side wall.
    for z,block in ((28,'dead_fire_coral_block'),(32,'dead_horn_coral_block'),(36,'dead_bubble_coral_block')):
        m.set(17,8,z,'chiseled_quartz_block');m.set(17,9,z,block)
    m.point('coral_collection','work',(17,9,32),'分类珊瑚骨架展台',approach=(16,8,32))
    m.meta['design_notes'].append('横向教学拱厅、短标本翼与高拱图志厅形成高低轮廓，墙上海流图板与独立骨架展台提供教学识别。')


def council(m):
    vault(m,4,7,8,27,rise=4);vault(m,42,7,8,27,rise=4)
    for a,b in [((19,10),(35,10)),((14,16),(14,28)),((40,16),(40,28)),((19,34),(35,34))]:
        m.box((a[0],14,a[1]),(b[0],14,b[1]),'air')
        arch(m,a,b,14,4)
    for x,z in ((19,10),(35,10),(14,16),(40,16),(14,28),(40,28),(19,34),(35,34)):
        for y in (10,12):m.set(x,y,z,'quartz_pillar[axis=y]')
    m.meta['design_notes'].append('八角露天水庭以四组壳肋拱架围合，中央仍露天；两侧狭长拱廊分别收纳档案与公共生活。')


def drowned(m):
    vault(m,5,5,14,9,rise=4)
    for z in (23,38):arch(m,(4,z),(42,z),12,7,'prismarine_bricks')
    # Broken high ribs and patchy damp masonry distinguish the original archive.
    m.box((21,17,23),(27,21,23),'air')
    for p,(name,props) in list(m.blocks.items()):
        x,y,z=p
        if z>=18 and name=='minecraft:prismarine_bricks' and (x*3+y+z*7)%11==0:
            m.blocks[p]=state('mossy_stone_bricks')
    board(m,8,10,6,7)
    m.meta['design_notes'].append('淹没旧馆保留断裂壳肋、潮湿斑驳墙体和低位书柜；新建干燥登记屋与架空抢救桥区别于旧层。')


def tower(m):
    vault(m,5,5,12,13,rise=4);vault(m,5,23,12,14,rise=4)
    # Tiered lantern crown; existing stair enclosure and every landing stay intact.
    m.box((19,26,25),(32,34,37),'air')
    for i in range(4):
        m.box((19+i,26+i,25+i),(32-i,26+i,37-i),'waxed_oxidized_cut_copper')
    for x in (24,27):
        for z in (30,33):m.box((x,30,z),(x,34,z),'quartz_pillar[axis=y]')
    m.box((25,30,31),(26,33,32),'sea_lantern')
    m.box((23,35,29),(28,35,34),'waxed_oxidized_copper')
    m.box((24,36,30),(27,36,33),'prismarine_bricks')
    m.set(25,37,31,'lightning_rod[facing=up]')
    board(m,7,10,6,7)
    m.meta['design_notes'].append('阶梯封闭长塔升至透光灯笼冠，低处双拱生活屋与高标灯构成层次；塔冠为静态航标，不声明真实照明航行机制。')


DETAILS={'TC-01':port,'TC-08':underwater,'TC-09':school,'TC-10':council,'TC-11':drowned,'TC-12':tower}


def refine(builder):
    m=builder()
    dark_furniture(m)
    DETAILS[m.meta['family']](m)
    m.meta['source']='tools/structure_studio/studio/tidal_coral.py + tidal_civic_details.py'
    m.meta['terrain']['风格表达']='海洋幻想：贝壳弧顶、浅色壳肋、青绿耐候表面与独立水陆空间；不将风格名直接当作选址条件。'
    return m
