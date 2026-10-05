"""Independent dwarven civic halls, industrial chambers and memorial masonry."""
from .dwarven_reborn_parts import (base,room_shell,portal,stairs,entrance,door,buttress,brazier,table,supplies,bed,wash,rune,face_statue)
CATALOG_DIR='R03_dwarven_reborn'


def hall():
    m=base(1,'矮人氏族大厅 · 石柱议政厅',61,35,65,terms=('议事','正式接见'))
    entrance(m,30)
    room_shell(m,12,17,48,56,floor=4,wall=14)
    stairs(m,26,34,10,3)
    m.box((21,1,13),(39,4,18),'stone_bricks')
    portal(m,30,16,floor=4,width=7,height=11,depth=3)
    for x in (12,46):
        for z in (21,34,47):buttress(m,x,z,2,height=16)
    for x in (20,40):
        for z in (28,42):
            m.box((x,5,z),(x+1,18,z+1),'polished_andesite')
            m.box((x-1,17,z-1),(x+2,18,z+2),'waxed_cut_copper')
    for z in (29,39):table(m,25,z,11,3,y=5)
    face_statue(m,30,51,base_y=5)
    m.point('council','work',(30,5,35),'议事桌间主通道')
    m.point('ancestral_seat','work',(30,5,48),'主持与祖徽前方')
    m.room('hall','氏族议政大厅',(15,5,20),(45,17,54),'长桌会商、族务接见')
    for x in (8,52):face_statue(m,x,10,base_y=2)
    for x in (23,37):brazier(m,x,5,21)
    rune(m,30,17,16)
    for x in (9,49):
        for z in (21,34,47):buttress(m,x,z,2,height=16)
    for x in (12,48):
        for z in (28,41,52):
            m.box((x,9,z),(x,13,z+1),'orange_stained_glass')
            m.box((x,14,z),(x,14,z+1),'waxed_cut_copper')
    m.meta.update(roof_min_y=19,floors=[{'name':'高台议政厅','y':4,'max_y':13}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def forge():
    m=base(2,'矮人大锻炉 · 双烟囱锻造院',61,42,61,terms=('金属冶炼','工具制造','货物仓储'))
    entrance(m,30)
    room_shell(m,17,14,43,49,floor=1,wall=15)
    portal(m,30,13,width=7,height=11,depth=3)
    # Two furnace towers penetrate the roof, supported by massive masonry bases.
    for cx in (22,38):
        m.box((cx-3,2,37),(cx+3,12,45),'polished_blackstone_bricks')
        m.box((cx-2,3,36),(cx+2,6,37),'blast_furnace[facing=north]')
        m.box((cx-2,13,39),(cx+2,34,43),'deepslate_bricks')
        m.box((cx-3,30,38),(cx+3,31,44),'waxed_cut_copper')
        m.box((cx-1,32,40),(cx+1,34,42),'air')
        m.set(cx,33,41,'campfire[lit=true,signal_fire=true]')
        m.point(f'furnace_{cx}','work',(cx,2,35),'熔炉操作面')
    # Side service wings are low and open into the forge through actual openings.
    for x0,x1,key in ((4,16,'stock'),(44,56,'tools')):
        room_shell(m,x0,24,x1,48,wall=8)
        wall=x1 if key=='stock' else x0
        m.box((wall,2,28),(wall,6,32),'air')
        adjacent=wall+1 if key=='stock' else wall-1
        m.box((adjacent,2,28),(adjacent,6,32),'air')
        for z in (28,39):supplies(m,x0+3,z)
        m.point(key,'storage',(x0+6,2,33),'侧翼工具与原料')
        m.room(key,'原料与成品侧库',(x0+1,2,25),(x1-1,8,47),'工业物资储放')
    for x in (24,36):
        for z in (23,29):m.set(x,2,z,'anvil');m.point(f'anvil_{x}_{z}','work',(x-1,2,z),'锻打工位')
    m.box((19,13,27),(41,14,28),'dark_oak_log[axis=x]')
    m.box((30,8,27),(30,12,27),'chain');m.set(30,7,27,'iron_block')
    m.room('forge','高跨锻造车间',(18,2,17),(42,14,35),'锻打、熔炉与吊运示意')
    for x in (11,49):brazier(m,x,2,12)
    m.meta.update(roof_min_y=16,floors=[{'name':'锻造及原料侧库','y':1,'max_y':10}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def mining_guild():
    m=base(3,'矮人矿业会堂 · 验矿与调度庭',59,31,57,terms=('矿业管理','矿料检验','货物仓储'))
    entrance(m,29)
    room_shell(m,15,25,43,49,floor=2,wall=11)
    portal(m,29,24,floor=2,width=5,height=8,depth=3)
    stairs(m,27,31,23,1)
    m.box((26,2,24),(32,2,24),'polished_andesite')
    for x0,x1,key in ((4,14,'assay'),(44,54,'store')):
        room_shell(m,x0,13,x1,40,wall=8)
        door(m,(x0+x1)//2,13,key=key+'_door')
        supplies(m,x0+3,30)
        m.set(x0+4,2,20,'smithing_table')
        m.point(key,'work',(x0+5,2,20),'验矿与物资登记')
        m.room(key,'验矿侧房' if key=='assay' else '矿具仓库',(x0+1,2,14),(x1-1,8,39),'验矿与矿具管理')
    table(m,23,35,13,3,y=3)
    m.box((18,3,46),(24,5,47),'bookshelf');m.box((34,3,46),(40,5,47),'barrel')
    m.point('dispatch','work',(29,3,33),'矿业调度议事')
    m.room('guild','矿业调度会堂',(17,3,28),(41,12,47),'矿场调度、记录与会商')
    for x,z in ((20,16),(36,16)):
        m.box((x,2,z),(x+2,2,z+2),'stone_bricks')
        m.set(x+1,3,z+1,'raw_iron_block' if x==20 else 'raw_copper_block')
    rune(m,29,13,24)
    for x in (20,38):
        m.box((x,5,25),(x+1,9,25),'orange_stained_glass')
        m.box((x-1,10,24),(x+2,10,24),'polished_andesite_slab')
    m.meta.update(roof_min_y=14,floors=[{'name':'调度与验矿','y':1,'max_y':10}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m


def memorial():
    m=base(4,'矮人祖先纪念厅 · 六像长廊',51,34,65,terms=('祖先纪念','礼仪'))
    entrance(m,25)
    room_shell(m,10,17,40,57,floor=3,wall=12)
    stairs(m,21,29,12,2)
    m.box((17,1,14),(33,3,18),'stone_bricks')
    portal(m,25,16,floor=3,width=7,height=10,depth=3)
    for x in (10,38):
        for z in (22,36,50):buttress(m,x,z,2,height=15)
    # Busts face the entry procession, with a clear central aisle.
    for x in (17,33):
        for z in (25,37,49):face_statue(m,x,z,base_y=4)
    m.box((24,3,18),(26,3,54),'waxed_cut_copper')
    m.point('procession','circulation',(25,4,40),'纪念长廊中央通道')
    m.point('offering','work',(25,4,54),'后方纪念站位')
    m.room('memorial','六像纪念长廊',(13,4,20),(37,14,55),'祖先纪念、陈列与祭仪')
    for x in (17,33):brazier(m,x,2,8)
    rune(m,25,15,16)
    for x in (7,41):
        for z in (22,36,50):buttress(m,x,z,2,height=15)
    for x in (10,40):
        for z in (30,44):m.box((x,8,z),(x,12,z+1),'orange_stained_glass')
    m.meta.update(roof_min_y=16,floors=[{'name':'祖像与纪念通道','y':3,'max_y':13}])
    for point in m.meta['points']:
        if point['kind'] in ('work','storage') and 'approach' not in point:
            point['approach']=list(point['pos'])
    return m

BUILDERS={f'DV-{i:02d}-v01':fn for i,fn in ((1,hall),(2,forge),(3,mining_guild),(4,memorial))}


def stone_home():
    m=base(5,'矮人石宅 · 炉边起居',31,24,33,role='self_contained',terms=('家庭居住',))
    entrance(m,15)
    room_shell(m,6,10,24,28,floor=2,wall=7)
    portal(m,15,9,floor=2,width=3,height=5,depth=3);stairs(m,14,16,8,1)
    m.box((13,2,9),(17,2,9),'polished_andesite')
    m.box((7,3,22),(23,7,22),'deepslate_bricks');door(m,15,22,floor=2)
    bed(m,10,26,y=3,key='bed1');bed(m,20,26,y=3,key='bed2')
    table(m,10,16,5,2,y=3)
    m.set(21,3,15,'smoker[facing=west]');wash(m,21,19,y=3)
    m.box((21,4,15),(21,18,15),'deepslate_bricks')
    m.box((20,18,14),(22,18,16),'waxed_cut_copper_slab')
    m.point('cook','work',(20,3,15),'炉灶前方',approach=(20,3,15))
    m.room('living','炉边起居',(7,3,11),(23,8,21),'家庭生活与备餐')
    m.room('sleep','后部寝居',(7,3,23),(23,8,27),'双床寝居')
    m.meta.update(roof_min_y=10,floors=[{'name':'起居与寝居','y':2,'max_y':8}])
    return m


def tavern():
    m=base(6,'矮人酒馆 · 双翼长桌厅',45,27,45,role='self_contained',terms=('餐饮','社交聚会'))
    entrance(m,22)
    room_shell(m,12,10,32,38,floor=1,wall=10)
    portal(m,22,9,width=5,height=7,depth=3)
    for xa,xb in ((4,11),(33,40)):
        room_shell(m,xa,20,xb,36,wall=6)
        wall=xb if xa==4 else xa
        adj=wall+1 if xa==4 else wall-1
        m.box((min(wall,adj),2,24),(max(wall,adj),5,28),'air')
        for z in (23,31):m.box((xa+2,2,z),(xb-2,3,z),'barrel')
    for z in (19,26):table(m,17,z,11,2)
    m.box((16,2,33),(28,2,33),'waxed_cut_copper')
    for x in (17,21,25,28):m.set(x,3,35,'barrel')
    m.set(30,2,33,'smoker[facing=north]')
    m.point('bar','work',(22,2,32),'酒馆服务台',approach=(22,2,32))
    m.point('cellar','storage',(7,2,27),'桶仓通路',approach=(7,2,27))
    m.room('dining','长桌聚会厅',(13,2,12),(31,10,32),'餐饮与交往')
    for x in (12,32):
        for z in (15,29):m.box((x,4,z),(x,7,z+1),'orange_stained_glass')
    m.meta['roof_min_y']=12
    return m


def warehouse():
    m=base(7,'矮人货仓 · 装卸石廊',43,26,43,role='fill',terms=('货物仓储','理货'))
    entrance(m,21)
    room_shell(m,7,19,35,37,wall=8)
    portal(m,21,18,width=7,height=6,depth=3)
    # Low loading canopy with stone piers and copper transverse ties.
    for x in (8,14,28,34):m.box((x,2,9),(x,8,9),'polished_andesite')
    m.box((7,9,8),(35,9,18),'deepslate_tiles')
    for x in (8,14,21,28,34):m.box((x,10,8),(x,10,19),'waxed_cut_copper_slab')
    for xa,xb in ((10,16),(26,32)):
        for z in (24,29,34):m.box((xa,2,z),(xb,3,z),'barrel')
    m.point('stock','storage',(21,2,29),'货仓中轴理货通路',approach=(21,2,29))
    m.point('loading','circulation',(21,2,12),'廊下装卸')
    m.room('storage','成排货架仓',(8,2,21),(34,8,36),'货物保管')
    m.room('loading','装卸门廊',(9,2,10),(33,8,17),'雨棚下装卸')
    m.meta['roof_min_y']=9
    return m


def stone_bridge():
    m=base(8,'矮人重墩石桥',25,23,45,role='structure',tags=('infrastructure',),terms=('步行过桥',))
    entrance(m,12)
    m.box((1,1,12),(23,1,32),'water')
    stairs(m,8,16,8,4)
    m.box((8,5,12),(16,5,32),'polished_andesite')
    for i in range(4):
        z=36-i;y=2+i
        m.box((8,1,z),(16,y-1,z),'stone_bricks')
        m.box((8,y,z),(16,y,z),'polished_andesite_stairs[facing=north]')
    for x in (7,17):
        m.box((x,4,11),(x,5,33),'deepslate_bricks')
        m.box((x,6,11),(x,6,33),'polished_deepslate_wall')
        for z in (12,22,32):
            m.box((x-1,1,z-1),(x+1,4,z+1),'stone_bricks')
            m.set(x,7,z,'waxed_cut_copper');m.set(x,8,z,'lantern')
    m.point('mid','circulation',(12,6,22),'石桥中央')
    m.point('far_bank','circulation',(12,2,44),'对岸出口')
    m.meta.update(roof_min_y=None,floors=[{'name':'桥面与重墩','y':1,'max_y':10}])
    return m


def furnace_lamp():
    m=base(9,'矮人炉灯 · 铜箍灯柱',13,21,13,role='structure',tags=('infrastructure',),terms=('道路照明',))
    entrance(m,6)
    m.box((4,2,4),(8,3,8),'deepslate_bricks')
    m.box((5,4,5),(7,11,7),'polished_andesite')
    for y in (4,9):m.box((4,y,4),(8,y,8),'waxed_cut_copper')
    m.box((4,12,4),(8,12,8),'deepslate_bricks')
    m.box((5,13,5),(7,15,7),'ochre_froglight')
    for x in (4,8):
        for z in (4,8):m.box((x,13,z),(x,15,z),'iron_bars')
    m.box((4,16,4),(8,16,8),'waxed_cut_copper_slab')
    m.meta['roof_min_y']=None
    return m


def statue_court():
    m=base(10,'矮人石雕庭 · 双祖像',31,24,33,role='structure',tags=('landscape',),terms=('雕塑观赏','休憩'))
    entrance(m,15)
    for x in (8,22):face_statue(m,x,19,base_y=3)
    m.box((13,1,2),(17,1,28),'waxed_cut_copper')
    for x in (5,25):brazier(m,x,2,9)
    for x in (7,21):m.box((x,2,28),(x+2,2,28),'polished_andesite_stairs[facing=north]')
    m.point('view','circulation',(15,2,16),'双祖像之间观赏道')
    m.meta.update(roof_min_y=None,floors=[])
    return m


def rock_pine():
    m=base(11,'矮人岩松 · 石台风折树',29,30,31,role='structure',tags=('landscape',),terms=('树木观赏',))
    entrance(m,14)
    for y in range(2,7):
        r=8-y
        for x in range(14-r,15+r):
            for z in range(18-r,19+r):
                if abs(x-14)+abs(z-18)<=r+2:m.set(x,y,z,'andesite' if (x+z+y)%3 else 'tuff')
    for y in range(6,24):m.set(14+(y-6)//7,y,18,'spruce_log')
    for y,r in ((13,6),(17,5),(21,3),(24,2)):
        cx=14+(min(y,23)-6)//7
        for x in range(cx-r,cx+r+1):
            for z in range(18-r,19+r):
                if abs(x-cx)+abs(z-18)<=r+1:
                    if m.blocks.get((x,y,z),('minecraft:air',))[0]=='minecraft:air':m.set(x,y,z,'spruce_leaves[persistent=true]')
        m.box((cx-r+1,y,18),(cx+r-1,y,18),'spruce_log[axis=x]')
    m.meta.update(roof_min_y=None,floors=[])
    return m


def crystal_garden():
    m=base(12,'矮人晶石小景 · 岩座花圃',25,24,27,role='structure',tags=('landscape',),terms=('矿物观赏','花草观赏'))
    entrance(m,12)
    for x,z,h in ((8,16,8),(12,19,13),(17,15,10)):
        m.box((x-2,2,z-2),(x+2,3,z+2),'tuff')
        m.box((x-1,4,z-1),(x+1,h,z+1),'amethyst_block')
        m.set(x,h+1,z,'amethyst_cluster[facing=up]')
        m.set(x,h+2,z,'end_rod')
    for xa,xb in ((3,7),(17,21)):
        m.box((xa,1,5),(xb,1,9),'moss_block')
        for x in range(xa,xb+1,2):m.set(x,2,7,'allium')
    m.point('view','circulation',(12,2,10),'晶石观赏前庭')
    m.meta.update(roof_min_y=None,floors=[])
    return m

BUILDERS.update({f'DV-{i:02d}-v01':fn for i,fn in ((5,stone_home),(6,tavern),(7,warehouse),(8,stone_bridge),(9,furnace_lamp),(10,statue_court),(11,rock_pine),(12,crystal_garden))})
