"""Dwarven revision: cut-stone vaults, battered piers and deeply recessed portals."""
import math
from .revision_geometry import base,line,curve,arch,steps,arrival,work,table,shelf,bed,tree,ring,disk

STONE='deepslate_bricks'
EDGE='polished_andesite'
METAL='waxed_cut_copper'


def dwarf(n,name,w,h,d,terms,role='key',tags=()):
    m=base(f'DV-{n:02d}',name,(w,h,d),'矮人新制',role=role,tags=tags,terms=terms)
    m.meta['source']='tools/structure_studio/studio/dwarven_revision2.py:BUILDERS'
    m.box((3,1,3),(w-4,1,d-4),'cobbled_deepslate')
    for z in range(4,d-3,8):m.box((4,1,z),(w-5,1,z),'polished_andesite')
    return m


def octagon(bounds,cut):
    x0,z0,x1,z1=bounds
    return {(x,z) for x in range(x0,x1+1) for z in range(z0,z1+1) if min(x-x0,x1-x)+min(z-z0,z1-z)>=cut}


def pier(m,x,z,bottom,top,r=2):
    # Battered base transitions into a faceted shaft and projecting stone capital.
    for y in range(bottom,top+1):
        rr=r+1 if y<bottom+3 else r
        for dx in range(-rr,rr+1):
            for dz in range(-rr,rr+1):
                if abs(dx)+abs(dz)<=rr*2-1:m.set(x+dx,y,z+dz,EDGE if abs(dx)==rr or abs(dz)==rr else STONE)
    for y in (bottom+2,top-2):
        for dx,dz in ((-r,0),(r,0),(0,-r),(0,r)):m.set(x+dx,y,z+dz,METAL)
    m.box((x-r-1,top,z-r-1),(x+r+1,top+1,z+r+1),'polished_andesite_slab[type=top]')


def vault(m,bounds,floor,top,cut=5,ribbed=True):
    x0,z0,x1,z1=bounds;cells=octagon(bounds,cut)
    inner=octagon((x0+2,z0+2,x1-2,z1-2),max(0,cut-1))
    for x,z in cells:
        m.box((x,1,z),(x,floor,z),STONE)
        m.set(x,floor,z,'polished_andesite')
        if (x,z) not in inner:m.box((x,floor+1,z),(x,top,z),STONE)
    # A thick, faceted stone barrel has a high central crown and an exposed archivolt.
    for x,z in cells:
        rise=min(x-x0,x1-x)//2;y=top+1+rise
        m.box((x,y,z),(x,y+2,z),'deepslate_tiles')
        if (x,z) not in inner:m.box((x,top,z),(x,y,z),STONE)
    for z in range(z0+cut+3,z1-cut,10):
        for x in (x0+2,x1-2):pier(m,x,z,floor+1,top-5,1)
        arch(m,(x0+x1)//2,z,top-5,x1-x0-6,0,(x1-x0)//4+4,EDGE,depth=2,fill=None,kind='angular',rib=2)
    for y in (floor+2,top-2):
        for x,z in cells-inner:m.set(x,y,z,EDGE if y==floor+2 else 'polished_deepslate')
    if ribbed:
        for z in range(z0+cut+3,z1-cut,10):
            for x in (x0-1,x1+1):pier(m,x,z,floor+1,top,0)
            for x in range(x0,x1+1):
                yy=top+4+min(x-x0,x1-x)//2
                m.box((x,yy,z),(x,yy+1,z+1),EDGE)
    for z in range(z0+cut+7,z1-cut-2,10):
        for x in (x0,x1-1):arch(m,z,x,floor+3,3,2,3,EDGE,axis='z',depth=2,fill='orange_stained_glass',kind='angular')
    for x in (x0+cut+2,x1-cut-2):
        m.box((x,floor+1,z0-1),(x,top+1,z0),EDGE)
        m.set(x,top+2,z0,'chiseled_deepslate')


def portal(m,cx,z,bottom,width=7,height=12,depth=4):
    for i in range(depth):
        arch(m,cx,z+i,bottom,width+2*(depth-1-i),height//2,height-height//2,
             EDGE if i%2==0 else STONE,kind='angular',rib=2)
    for x in (cx-width//2-depth-1,cx+width//2+depth+1):
        m.box((x,bottom,z),(x,bottom+height-2,z),STONE)
        m.set(x,bottom+height-1,z,'chiseled_deepslate')


def rune(m,x,y,z,scale=1):
    for a,b in [((-3,0),(0,3)),((0,3),(3,0)),((3,0),(0,-3)),((0,-3),(-3,0)),((0,-3),(0,3))]:
        line(m,(x+a[0]*scale,y+a[1]*scale,z),(x+b[0]*scale,y+b[1]*scale,z),METAL)


def statue(m,cx,z,bottom=2,height=19):
    # Full-height door guardian: boots, body, crossed arms, brow, angular beard.
    m.box((cx-4,bottom,z),(cx+4,bottom+1,z+5),'polished_deepslate')
    for xx in (cx-2,cx+2):m.box((xx-1,bottom+2,z+1),(xx+1,bottom+6,z+4),'stone_bricks')
    m.box((cx-3,bottom+7,z+1),(cx+3,bottom+13,z+4),'stone_bricks')
    m.box((cx-3,bottom+12,z),(cx+3,bottom+13,z+5),METAL)
    m.box((cx-2,bottom+14,z+1),(cx+2,bottom+18,z+4),'polished_andesite')
    for xx in (cx-1,cx+1):m.set(xx,bottom+17,z,'ochre_froglight')
    m.box((cx-3,bottom+18,z),(cx+3,bottom+19,z+4),METAL)
    for i in range(4):m.box((cx-3+i,bottom+11-i,z),(cx+3-i,bottom+14-i,z),'cobbled_deepslate')
    line(m,(cx-5,bottom+13,z+2),(cx-2,bottom+9,z-1),EDGE,1)
    line(m,(cx+5,bottom+13,z+2),(cx+2,bottom+9,z-1),EDGE,1)
    m.box((cx-5,bottom+3,z-2),(cx-5,bottom+13,z-2),'dark_oak_log')
    m.box((cx-7,bottom+12,z-3),(cx-3,bottom+15,z-1),'polished_deepslate')


def clan_hall():
    m=dwarf(1,'山砧氏族殿 · 折角重拱与祖卫门庭',77,58,85,('议事','正式接见','礼仪'))
    arrival(m,38)
    m.box((12,1,21),(64,4,73),STONE)
    m.box((12,4,21),(64,4,73),'polished_andesite')
    steps(m,31,45,18,1,4,'polished_andesite')
    vault(m,(18,29,58,71),4,25,7,ribbed=False)
    # Massive external angled legs terminate on independent base blocks.
    for z in (39,53,65):
        for x,wall in ((12,18),(64,58)):
            pier(m,x,z,5,14,2)
            line(m,(x,15,z),(wall,27,z),STONE,2)
            line(m,(x,15,z-2),(wall,27,z-2),EDGE)
            line(m,(x,15,z+2),(wall,27,z+2),EDGE)
        # Broad transverse stone ribs continue over the crown; no decorative roof straps.
        for x in range(18,59):
            y=28+min(x-18,58-x)//2
            m.box((x,y,z-1),(x,y+2,z+1),EDGE)
    portal(m,38,25,5,9,16,6)
    rune(m,38,29,26,2)
    # The entrance is cut through the actual front wall behind all archivolts.
    m.box((34,5,29),(42,12,32),'air')
    statue(m,23,18,bottom=5);statue(m,53,18,bottom=5)
    for z in (39,49):table(m,28,5,z,7,'dark_oak');table(m,44,5,z,7,'dark_oak')
    # Rear council gallery has a real supporting arcade and a side stair.
    m.box((24,12,58),(52,12,65),'polished_andesite')
    for x in (25,38,51):pier(m,x,62,5,11,1)
    m.box((26,13,58),(52,13,58),'deepslate_brick_wall')
    steps(m,24,26,50,4,12,'polished_andesite')
    m.box((24,13,58),(26,16,60),'air')
    for x in (31,45):
        m.box((x,13,61),(x+1,15,62),'polished_deepslate');m.set(x,16,62,METAL)
    work(m,'speaker',38,13,61,'上层氏族议席')
    work(m,'assembly',38,5,44,'下层会众与中轴通道')
    for x in (28,48):
        m.box((x,18,43),(x,23,43),'chain');m.set(x,17,43,'lantern[hanging=true]')
    for z in (39,53):
        for x in (18,19,57,58):
            m.box((x,12,z-1),(x,18,z+1),'orange_stained_glass')
            m.box((x,11,z-1),(x,11,z+1),'polished_andesite_slab')
    m.room('assembly','折角氏族大殿',(24,5,34),(52,22,56),'双列议事长桌与中央礼仪通路')
    m.room('upper','氏族议席廊',(28,13,59),(50,18,64),'由真实侧梯连通的高层会商席')
    m.meta.update(floors=[{'name':'门庭与议事厅','y':4,'max_y':11},{'name':'上层议席廊','y':12,'max_y':20}],roof_min_y=27)
    m.meta['design_notes']+=['折角厚墙、重型斜撑、双层券廊和六进门拱共同定义结构；铜只用于节点与族徽。','自带稳定石台，独立摆放成立；不以外部山体遮挡未完成的背面。']
    return m


BUILDERS={'DV-01-v01':clan_hall}


def furnace(m,cx,cz,r,height):
    outer=octagon((cx-r,cz-r,cx+r,cz+r),max(2,r//2));inner=octagon((cx-r+2,cz-r+2,cx+r-2,cz+r-2),max(1,r//2-1))
    for x,z in outer:
        m.box((x,2,z),(x,5,z),'polished_blackstone_bricks')
        if (x,z) not in inner:
            m.box((x,6,z),(x,height,z),STONE)
            for y in (6,height-8,height-2):m.set(x,y,z,METAL)
    for x in range(cx-r+3,cx+r-2,3):
        m.box((x,8,cz-r),(x,18,cz-r),'orange_stained_glass');m.box((x,8,cz+r),(x,18,cz+r),'orange_stained_glass')
    m.box((cx-2,6,cz-r-1),(cx+2,9,cz-r),'blast_furnace[facing=north]')
    m.box((cx-2,6,cz-2),(cx+2,6,cz+2),'magma_block')
    for dx,dz in ((-r,0),(r,0),(0,-r),(0,r)):
        m.box((cx+dx,2,cz+dz),(cx+dx,height+2,cz+dz),EDGE)


def forge():
    m=dwarf(2,'高炉锻造院 · 露架吊运与八角炉塔',75,63,77,('金属冶炼','工具制造'))
    arrival(m,37);furnace(m,37,53,10,47)
    for x0,x1 in ((6,23),(51,68)):
        vault(m,(x0,27,x1,61),1,12,3);portal(m,(x0+x1)//2,24,2,5,8,6)
        for z in (35,45,55):m.set(x0+6,2,z,'anvil');shelf(m,x0+4,2,z+3,6,'dark_oak','barrel')
        work(m,f'smith_{x0}',x0+8,2,43,'侧翼锻打与工装台')
    for x in (26,48):
        for z in (27,45):pier(m,x,z,2,25,2)
    for z in (27,45):
        m.box((25,26,z-1),(49,28,z+1),'polished_deepslate')
        for x in (26,48):line(m,(x,19,z),(37,27,z),EDGE)
    m.box((36,27,26),(38,29,46),METAL)
    m.box((37,13,35),(37,26,35),'chain');m.box((36,11,34),(38,12,36),'iron_block')
    m.box((30,1,24),(44,1,57),'polished_andesite')
    for x in (29,45):m.box((x,2,58),(x+1,2,65),'water_cauldron[level=3]')
    work(m,'furnace',37,2,40,'炉前操作通道');work(m,'crane',34,2,32,'起重区外围通路')
    m.room('forge','高炉前吊运工场',(27,2,27),(47,25,41),'巨型炉塔、开放吊架与两侧独立锻打翼')
    m.meta['roof_min_y']=50
    return m


def mining_guild():
    m=dwarf(3,'竖井矿业会堂 · 围井调度与双层验矿廊',69,54,73,('矿业管理','矿料检验'))
    arrival(m,34)
    vault(m,(9,22,59,62),1,16,7)
    # A central sunken demonstration shaft dictates an annular working floor.
    m.box((28,1,34),(40,2,48),'polished_deepslate');m.box((30,1,36),(38,1,46),'black_concrete')
    for z in (34,48):m.box((28,3,z),(40,3,z),'deepslate_brick_wall')
    for x in (28,40):m.box((x,3,35),(x,3,47),'deepslate_brick_wall')
    for x in (24,44):pier(m,x,41,2,24,1)
    m.box((23,25,39),(45,27,43),EDGE)
    for x in (31,37):m.box((x,3,41),(x,24,41),'chain')
    # Cut a real roof lantern around the hoist frame.
    m.box((26,17,33),(42,40,49),'air')
    for x in (25,43):
        m.box((x,18,33),(x,38,49),EDGE)
        for z in (33,49):m.box((x,2,z),(x,38,z),EDGE)
    for z in (33,49):m.box((26,18,z),(42,38,z),'orange_stained_glass')
    m.box((25,39,33),(43,40,49),'polished_deepslate')
    portal(m,34,17,2,7,12,8)
    for x in (22,46):
        arch(m,x,21,15,5,2,4,EDGE,depth=3,fill='orange_stained_glass',kind='angular',rib=2)
    for x0,x1 in ((15,23),(45,53)):
        m.box((x0,10,29),(x1,10,56),EDGE)
        for z in (34,47):pier(m,(x0+x1)//2,z,2,9,0)
        steps(m,x0,x0+2,20,1,10,'polished_andesite')
        for z in (39,51):shelf(m,x0+2,11,z,4,'dark_oak','barrel')
        work(m,f'gallery_{x0}',x0+4,11,43,'验矿与矿具上廊')
    work(m,'dispatch',34,2,30,'井前调度')
    m.room('shaft','围井调度厅',(20,2,29),(48,15,57),'中央围井与左右上层验矿廊')
    m.meta.update(roof_min_y=24,floors=[{'name':'围井大厅','y':1,'max_y':9},{'name':'验矿上廊','y':10,'max_y':17}])
    return m


def memorial():
    m=dwarf(4,'祖脉纪念殿 · 八角祖厅与环列石龛',71,55,73,('祖先纪念','礼仪'))
    arrival(m,35)
    cells=octagon((13,21,57,65),12);inner=octagon((16,24,54,62),11)
    for x,z in cells:
        m.box((x,1,z),(x,3,z),STONE)
        if (x,z) not in inner:m.box((x,4,z),(x,23,z),STONE)
    # Concentric stepped stone courses form an angular domed ancestral chamber.
    for dy in range(15):
        r=23-dy;cur=octagon((35-r,43-r,35+r,43+r),max(3,r//2));nxt=octagon((36-r,44-r,34+r,42+r),max(2,(r-1)//2))
        for x,z in cur-nxt:m.box((x,24+dy,z),(x,25+dy,z),EDGE if dy%4==0 else STONE)
    m.box((28,39,36),(42,40,50),'polished_deepslate')
    for dx,dz in ((-1,-1),(1,-1),(-1,1),(1,1)):
        x,z=35+dx*17,43+dz*17
        pier(m,x,z,3,23,1)
        line(m,(x,24,z),(35+dx*8,39,43+dz*8),EDGE,1)
    arch(m,35,20,20,11,2,5,EDGE,depth=3,fill='orange_stained_glass',kind='angular',rib=2)
    steps(m,30,40,15,1,3,'polished_andesite');m.box((28,1,17),(42,3,23),EDGE)
    portal(m,35,17,4,7,13,7)
    for x,z in ((23,30),(47,30),(23,49),(47,49)):statue(m,x,z,bottom=4)
    for x in (12,58):
        for z in (35,51):pier(m,x,z,2,24,1)
    for z in (36,49):
        for x in (13,14,55,56,57):m.box((x,10,z),(x,18,z+2),'orange_stained_glass')
    m.box((32,4,38),(38,5,44),'polished_deepslate');m.set(35,6,41,'campfire')
    work(m,'ritual',35,4,33,'祖厅中央礼仪通道')
    m.room('ancestral','环列祖像厅',(21,4,29),(49,22,57),'厚壁石龛与中央祭火构成纪念空间')
    m.meta.update(roof_min_y=24,floors=[{'name':'祖厅','y':3,'max_y':22}])
    return m


def stone_home():
    m=dwarf(5,'厚墙石宅 · 下沉炉间与侧向寝室',39,30,45,('家庭居住',),role='self_contained')
    arrival(m,19);vault(m,(6,15,31,37),1,10,5);portal(m,19,11,2,5,7,6)
    m.box((20,2,24),(20,7,34),STONE);m.box((20,2,26),(20,4,28),'air')
    for z in (29,34):bed(m,f'bed_{z}',25,2,z,'brown')
    table(m,10,2,26,5,'dark_oak');m.set(10,2,33,'smoker');m.set(11,2,33,'water_cauldron[level=3]')
    m.box((8,10,32),(10,21,34),STONE)
    for z in (22,31):m.box((6,4,z),(7,6,z+1),'orange_stained_glass')
    work(m,'living',15,2,23,'炉边起居');m.room('home','炉间与寝居',(9,2,19),(28,9,34),'厚墙围合的独立住宅')
    return m


def tavern():
    m=dwarf(6,'沉炉酒馆 · 八角火厅与桶窖侧室',57,42,61,('餐饮','饮酒会聚'),role='self_contained')
    arrival(m,28);vault(m,(12,19,44,48),1,17,7)
    vault(m,(5,34,15,53),1,10,2);vault(m,(41,34,51,53),1,10,2)
    portal(m,28,14,2,7,11,7)
    for x in (14,42):m.box((x,2,38),(x+1,6,42),'air')
    m.box((25,2,30),(31,3,36),'polished_blackstone_bricks');m.set(28,4,33,'campfire')
    for x,z in ((18,26),(33,26),(18,41),(33,41)):table(m,x,2,z,5,'dark_oak')
    for x in (8,44):shelf(m,x,2,49,4,'dark_oak','barrel')
    for x in (21,35):pier(m,x,33,2,13,1)
    arch(m,28,33,13,11,0,7,EDGE,fill=None,kind='angular')
    work(m,'bar',28,2,43,'后部酒水服务');m.room('hearth','八角会饮厅',(16,2,23),(40,15,44),'中央炉火与四组会饮桌')
    return m


def warehouse():
    m=dwarf(7,'拱券货仓 · 三门石跨与卸货台',55,34,55,('货物仓储','装卸'),role='fill')
    arrival(m,27)
    for x0,x1 in ((6,19),(21,34),(36,49)):
        vault(m,(x0,20,x1,46),1,12,2);portal(m,(x0+x1)//2,17,2,5,8,5)
        for z in (27,36,43):
            m.box((x0+3,2,z),(x0+4,4,z+1),'barrel')
            m.box((x1-3,2,z),(x1-2,4,z+1),'barrel')
        work(m,f'aisle_{x0}',(x0+x1)//2,2,32,'分券货仓主通道')
    for x in (12,27,42):
        m.box((x-4,1,9),(x+4,1,15),EDGE)
        m.box((x,2,11),(x+1,3,12),'barrel')
    m.room('loading','三门卸货前台',(7,2,7),(48,7,16),'分别对应三间拱券货仓的装卸面')
    return m


BUILDERS.update({f'DV-{i:02d}-v01':f for i,f in ((2,forge),(3,mining_guild),(4,memorial),(5,stone_home),(6,tavern),(7,warehouse))})


def throne_gate():
    m=dwarf(13,'王座山门 · 三重阶台与双祖卫',81,63,77,('礼仪','正式接见','防御'))
    arrival(m,40)
    # Terraced stone masses, a tall recessed gate and rear throne chamber.
    for x0,z0,x1,z1,y in ((11,28,69,67,5),(17,34,63,63,10),(24,41,56,60,18)):
        m.box((x0,1,z0),(x1,y,z1),STONE)
        for x in (x0,x1):m.box((x,2,z0),(x,y,z1),EDGE)
        m.box((x0,y,z0),(x1,y,z1),'polished_deepslate')
    m.box((30,2,26),(50,15,62),'air');m.box((30,1,26),(50,1,62),EDGE)
    portal(m,40,23,2,11,23,8)
    for x in (18,62):statue(m,x,19,bottom=2)
    for x in (26,54):pier(m,x,32,2,30,2)
    for y,xa,xb in ((30,25,55),(33,29,51),(36,33,47)):
        m.box((xa,y,31),(xb,y+2,37),EDGE)
    # The ceremonial seat is raised by two actual steps inside the gate chamber.
    m.box((32,1,53),(48,3,60),STONE);steps(m,36,44,51,1,3,'polished_andesite')
    m.box((38,4,57),(42,8,59),'polished_blackstone_bricks');rune(m,40,11,60,1)
    work(m,'throne',40,4,55,'后部王座接见台')
    m.room('audience','山门接见长厅',(32,2,33),(48,14,52),'层层退台下的纵深礼仪通道')
    m.meta.update(roof_min_y=19,floors=[{'name':'山门与接见厅','y':1,'max_y':15}])
    return m


def mint():
    m=dwarf(14,'铸币鉴印厅 · 交叉压架与双券核验间',63,45,59,('铸币','鉴定','交易管理'))
    arrival(m,31)
    vault(m,(13,17,49,48),1,18,7);portal(m,31,12,2,7,12,8)
    for bounds in ((3,25,14,43),(48,25,59,43)):
        vault(m,bounds,1,9,3)
    for wall in (13,14,48,49):arch(m,34,wall,2,5,2,3,EDGE,axis='z',kind='angular')
    arch(m,31,16,18,11,2,5,EDGE,depth=3,fill='orange_stained_glass',kind='angular',rib=2)
    for x in (25,37):pier(m,x,33,2,17,1)
    m.box((24,18,31),(38,20,35),EDGE)
    m.box((30,8,32),(32,17,34),'iron_block');m.box((28,2,30),(34,4,36),'polished_blackstone_bricks')
    m.set(31,5,33,'gold_block')
    for x in (18,39):
        table(m,x,2,25,5,'dark_oak');m.set(x+2,4,25,'smithing_table')
        shelf(m,x,2,43,5,'dark_oak','barrel')
    work(m,'assay',31,2,23,'币坯与印模核验');work(m,'press',31,2,39,'静态压印机后侧操作位')
    m.room('mint','鉴印与铸币厅',(17,2,21),(45,16,44),'中央压印主架与左右核验工位')
    return m


def lift_tower():
    m=dwarf(15,'矿山升降塔 · 重墩井架与双层接驳',61,65,69,('矿运提升','矿业管理'))
    arrival(m,30)
    for x in (20,40):
        for z in (29,49):pier(m,x,z,2,40,2)
    for z in (29,49):
        m.box((18,41,z-2),(42,44,z+2),EDGE)
        line(m,(20,28,z),(30,41,z),STONE,1);line(m,(40,28,z),(30,41,z),STONE,1)
    m.box((18,1,27),(42,1,51),'polished_deepslate')
    for x in (24,36):m.box((x,3,39),(x,43,39),'chain')
    m.box((24,3,33),(36,4,45),'dark_oak_planks')
    for z in (32,46):m.box((23,2,z),(37,2,z),'deepslate_brick_wall')
    for x in (23,37):m.box((x,2,33),(x,2,45),'deepslate_brick_wall')
    # Free-standing landings are supported by masonry piers; stairs stay outside the cage.
    for y in (13,25):
        m.box((7,y,24),(16,y,53),EDGE)
        for x in (7,16):m.box((x,y+1,24),(x,y+1,53),'deepslate_brick_wall')
        m.box((14,y,46),(23,y,50),EDGE)
        for z in (46,50):m.box((17,y+1,z),(23,y+1,z),'deepslate_brick_wall')
    for z in (28,49):pier(m,11,z,2,24,1)
    steps(m,9,11,12,1,13,'polished_andesite');steps(m,12,14,42,13,25,'polished_andesite',south=False)
    m.box((9,14,24),(11,17,26),'air');m.box((12,26,30),(14,29,32),'air')
    work(m,'landing1',13,14,48,'第一接驳层');work(m,'landing2',13,26,48,'第二接驳层')
    vault(m,(44,35,55,56),1,12,2);portal(m,49,32,2,3,8,6)
    work(m,'dispatch',49,2,42,'提升值守调度间')
    m.meta.update(roof_min_y=46,floors=[{'name':'下部井场','y':1,'max_y':12},{'name':'一层接驳','y':13,'max_y':24},{'name':'二层接驳','y':25,'max_y':40}])
    return m


def baths():
    m=dwarf(16,'地热浴堂 · 三券温池与排汽石塔',65,44,63,('公共沐浴','休憩'))
    arrival(m,32)
    for x0,x1 in ((7,23),(24,40),(41,57)):
        vault(m,(x0,22,x1,52),1,13,3);portal(m,(x0+x1)//2,18,2,5,9,7)
        m.box((x0+4,1,33),(x1-4,1,43),'water')
        for x in (x0+3,x1-3):m.box((x,2,32),(x,2,44),'polished_andesite_slab')
        work(m,f'pool_{x0}',(x0+x1)//2,2,29,'温池前更衣通道')
        m.box((x0+5,14,46),(x1-5,28,50),STONE)
        m.box((x0+6,15,47),(x1-6,28,49),'air')
    for x in (15,32,49):
        m.set(x,2,12,'water_cauldron[level=3]');m.set(x+2,2,12,'barrel')
    m.room('bathhouse','三券公共温池',(10,2,25),(54,12,49),'不同温池分券设置、侧面留干地通路')
    return m


def gem_workshop():
    m=dwarf(17,'宝石切磨坊 · 斜采光顶与分列工台',47,35,49,('宝石加工','商品零售'),role='fill')
    arrival(m,23);vault(m,(8,16,38,40),1,13,6);portal(m,23,12,2,5,9,7)
    for x in range(18,29):
        for z in range(22,35):
            y=14+min(x-8,38-x)//2;m.set(x,y+2,z,'light_blue_stained_glass')
    for x in (14,28):
        for z in (23,32):
            table(m,x,2,z,4,'dark_oak');m.set(x+1,4,z,'grindstone[face=floor]');m.set(x+3,4,z,'amethyst_cluster')
    work(m,'cutting',23,2,28,'宝石检验与切磨通道');m.room('work','采光切磨间',(12,2,20),(34,12,36),'斜面采光与左右分列工台')
    return m


def armoury():
    m=dwarf(18,'武备工坊 · 双锻台与后部试装廊',55,39,57,('工具制造','武器制作'),role='fill')
    arrival(m,27);vault(m,(7,19,23,45),1,14,3);vault(m,(31,19,47,45),1,14,3)
    for cx in (15,39):portal(m,cx,15,2,5,10,7)
    for x in (12,36):
        for z in (27,37):m.set(x,2,z,'anvil');m.set(x+5,2,z,'smithing_table')
        shelf(m,x,2,41,7,'dark_oak','barrel');work(m,f'craft_{x}',x+3,2,32,'武备锻打与装配')
    for z in (24,36):
        for x in (24,30):pier(m,x,z,2,9,0)
        arch(m,27,z,8,5,0,4,EDGE,fill=None,kind='angular')
    m.room('trial','双翼间试装廊',(25,2,22),(29,10,43),'两座工间之间的试装与搬运通路')
    return m


def brewery():
    m=dwarf(19,'麦酒酿坊 · 铜罐庭与石券粮房',61,46,59,('酿酒','食品制作'),role='fill')
    arrival(m,30);vault(m,(7,24,24,49),1,12,3);portal(m,15,20,2,5,8,7)
    for cx,cz in ((39,28),(45,44)):
        for y in range(2,20):
            r=5 if 5<y<16 else 4
            for x in range(cx-r,cx+r+1):
                for z in range(cz-r,cz+r+1):
                    if (x-cx)**2+(z-cz)**2<=r*r:m.set(x,y,z,METAL if y%6 else EDGE)
        m.box((cx-1,20,cz-1),(cx+1,27,cz+1),METAL)
        m.set(cx,2,cz-6,'water_cauldron[level=3]')
    for z in (30,40):
        m.box((10,2,z),(12,4,z+3),'barrel');m.box((20,2,z),(21,4,z+3),'barrel')
    m.box((27,10,26),(32,11,28),METAL)
    for x in (29,55):pier(m,x,36,2,23,1)
    arch(m,42,36,22,23,0,7,EDGE,fill=None,kind='angular')
    work(m,'grain',16,2,34,'麦料库');work(m,'brewing',33,2,36,'铜罐间酿造通路')
    m.room('brewing','铜罐酿造庭',(28,2,20),(54,27,51),'双罐与独立粮房形成工序差异')
    return m


def miners_home():
    m=dwarf(20,'矿工宿舍 · 双层壁龛寝居与公共炉厅',57,41,59,('工人住宿','集体用餐'),role='self_contained')
    arrival(m,28);vault(m,(10,18,46,50),1,18,6);portal(m,28,14,2,7,12,7)
    m.box((13,9,22),(43,9,46),'dark_oak_planks')
    steps(m,15,17,27,1,9,'polished_andesite')
    for y in (2,10):
        for x in (23,30,37):bed(m,f'bed_{y}_{x}',x,y,41,'brown')
        m.box((21,y,36),(42,y+4,36),STONE);m.box((27,y,36),(29,y+2,36),'air')
        table(m,25,y,28,11,'dark_oak')
    work(m,'upper',28,10,33,'上层公共炉厅')
    m.room('living','公共炉厅',(20,2,23),(40,8,34),'下层餐厅与后方寝居分开')
    m.meta.update(roof_min_y=20,floors=[{'name':'食宿首层','y':1,'max_y':8},{'name':'食宿二层','y':9,'max_y':17}])
    return m


def ore_yard():
    m=dwarf(21,'选矿料场 · 分级料斗与重架筛台',63,43,57,('矿料检验','矿料分类'),role='fill')
    arrival(m,31)
    for cx,z,ore in ((14,37,'raw_iron_block'),(31,39,'raw_copper_block'),(48,37,'raw_gold_block')):
        for dy in range(7):
            r=3+dy//2;m.box((cx-r,8+dy,z-r),(cx+r,8+dy,z+r),STONE)
        m.box((cx-4,15,z-4),(cx+4,15,z+4),ore)
        for x in (cx-5,cx+5):pier(m,x,z,2,8,1)
        m.box((cx-1,3,z-1),(cx+1,8,z+1),'polished_deepslate')
        m.box((cx-3,1,z-8),(cx+3,1,z-3),'polished_andesite')
    for x in (16,46):pier(m,x,19,2,22,1)
    m.box((15,23,18),(47,25,20),EDGE)
    for x in (22,31,40):m.box((x,12,19),(x,22,19),'chain');m.set(x,11,19,'iron_block')
    work(m,'sort',31,2,28,'分级筛选主通道')
    m.room('sorting','露天分级场',(8,2,15),(54,22,47),'三座料斗与独立重架筛台')
    m.meta['roof_min_y']=28
    return m


BUILDERS.update({f'DV-{i:02d}-v01':f for i,f in ((13,throne_gate),(14,mint),(15,lift_tower),(16,baths),(17,gem_workshop),(18,armoury),(19,brewery),(20,miners_home),(21,ore_yard))})


def bridge():
    m=dwarf(8,'巨墩矿桥 · 三段折拱与桥头石卫',35,38,63,('步行过桥','矿料运输'),role='structure',tags=('infrastructure',))
    arrival(m,17);m.box((0,1,19),(34,1,43),'water')
    for x in (10,24):
        arch(m,31,x,2,31,0,8,EDGE,axis='z',fill=None,kind='angular',rib=3)
        for z in (17,45):m.box((x-2,0,z-2),(x+2,8,z+2),STONE)
    m.box((10,10,15),(24,10,47),STONE)
    steps(m,13,21,6,1,10,'polished_andesite');steps(m,13,21,56,1,10,'polished_andesite',south=False)
    for x in (10,24):
        m.box((x,11,15),(x,12,47),STONE)
        for z in (18,31,44):m.set(x,13,z,'chiseled_deepslate')
    for z in (18,44):arch(m,17,z,11,11,5,5,EDGE,fill=None,kind='angular',rib=2)
    work(m,'bridge',17,11,31,'宽矿运桥面');m.meta.update(roof_min_y=25,floors=[{'name':'矿桥','y':10,'max_y':23}])
    return m


def lamp():
    m=dwarf(9,'炉笼路灯 · 四爪铁篮与石墩',17,27,17,('道路照明',),role='structure',tags=('infrastructure',))
    arrival(m,8);pier(m,8,8,2,10,1)
    for y in range(11,18):
        r=2 if y<15 else 3
        for dx,dz in ((-r,0),(r,0),(0,-r),(0,r)):m.set(8+dx,y,8+dz,'iron_bars')
    m.box((6,14,6),(10,14,10),'polished_blackstone_bricks');m.set(8,15,8,'campfire')
    for x,z in ((6,6),(10,6),(6,10),(10,10)):m.box((x,15,z),(x,18,z),METAL)
    m.box((6,19,6),(10,19,10),'waxed_cut_copper_slab')
    m.meta['roof_min_y']=22
    return m


def statue_court():
    m=dwarf(10,'祖卫石庭 · 持斧全身像与阶台',41,36,43,('纪念','休憩'),role='structure',tags=('landscape',))
    arrival(m,20);m.box((10,1,16),(30,3,34),STONE);steps(m,16,24,14,1,3,'polished_andesite')
    statue(m,20,22,bottom=4)
    for x in (10,29):m.box((x,2,9),(x+1,2,13),'polished_andesite_stairs[facing=east]' if x==10 else 'polished_andesite_stairs[facing=west]')
    m.meta['roof_min_y']=29
    return m


def rock_pine():
    m=dwarf(11,'岩台苍松 · 倾干与三层横枝',39,39,39,('树木观赏',),role='structure',tags=('landscape',))
    arrival(m,19)
    for cx,cz,r,h in ((15,21,8,5),(23,25,6,8),(12,14,4,3)):
        for y in range(2,h+1):
            rr=max(2,r-y//2)
            for x,z in octagon((cx-rr,cz-rr,cx+rr,cz+rr),2):m.set(x,y,z,'andesite')
    line(m,(18,5,21),(23,24,20),'spruce_log',1)
    for yy,dx,dz,r in ((14,-7,-4,5),(20,7,2,5),(25,-2,0,6)):
        line(m,(20,yy-3,20),(20+dx,yy,20+dz),'spruce_log')
        for y in range(yy,yy+3):
            disk(m,20+dx,20+dz,y,r-(y-yy),'spruce_leaves[persistent=true]')
    m.meta['roof_min_y']=33
    return m


def crystal(m,cx,cz,y,r,h,material='amethyst_block'):
    for dy in range(h):
        rr=max(0,round(r*(1-max(0,dy-h//3)/max(1,h-h//3-1))))
        for dx in range(-rr,rr+1):
            for dz in range(-rr,rr+1):
                if abs(dx)+abs(dz)<=rr+max(0,rr-1):m.set(cx+dx+dy//6,y+dy,cz+dz,material)


def crystal_garden():
    m=dwarf(12,'晶簇花圃 · 断层石台与环游小径',37,32,39,('景观观赏',),role='structure',tags=('landscape',))
    arrival(m,18)
    for x,z,r,h in ((13,23,4,14),(23,26,3,10),(21,14,2,7)):
        m.box((x-r-2,1,z-r-2),(x+r+2,3,z+r+2),'cobbled_deepslate')
        crystal(m,x,z,4,r,h)
    for x,z in ((8,13),(28,19),(12,31)):
        m.set(x,1,z,'moss_block');m.set(x,2,z,'flowering_azalea')
    m.meta['roof_min_y']=24
    return m


def gate():
    m=dwarf(22,'重闸关门 · 三进折角券与侧堡',65,48,43,('出入通行','防御'),role='structure',tags=('infrastructure',))
    arrival(m,32)
    for x0,x1 in ((7,22),(42,57)):
        cells=octagon((x0,13,x1,32),4)
        for x,z in cells:m.box((x,2,z),(x,26,z),STONE)
        for y in (5,20,26):
            for x,z in cells:m.set(x,y,z,EDGE)
        for x in range(x0+2,x1,4):m.box((x,27,14),(x+1,29,16),STONE)
    for z,width in ((17,17),(19,13),(21,9)):portal(m,32,z,2,width,20,3)
    for x in (23,41):m.box((x,2,21),(x,18,21),'iron_bars')
    m.box((29,18,21),(35,20,23),'iron_bars')
    m.box((21,27,17),(43,29,26),STONE)
    rune(m,32,24,16,1)
    work(m,'passage',32,2,28,'重闸下方通行净空')
    m.meta['roof_min_y']=32
    return m


def harbour():
    m=dwarf(23,'矿运河港 · 重石岸台与吊臂塔',65,48,65,('水运接驳','货物装卸'),role='structure',tags=('infrastructure',))
    arrival(m,32);m.box((0,1,37),(64,1,64),'water')
    m.box((7,1,15),(57,3,42),STONE);steps(m,27,37,13,1,3,'polished_andesite')
    for x in (11,49):m.box((x,1,41),(x+4,3,58),STONE)
    for x in (14,50):pier(m,x,31,4,25,2)
    m.box((12,26,29),(52,28,33),EDGE)
    for x in (14,50):line(m,(x,16,31),(32,27,31),STONE,1)
    m.box((30,27,29),(34,29,51),METAL)
    m.box((32,11,50),(32,28,50),'chain');m.box((30,8,48),(34,10,52),'iron_block')
    for x in (19,39):
        for z in (23,37):m.box((x,4,z),(x+4,6,z+3),'barrel')
    work(m,'loading',32,4,37,'吊运岸台');work(m,'berth1',13,4,54,'西侧矿运泊位');work(m,'berth2',51,4,54,'东侧矿运泊位')
    m.meta.update(roof_min_y=32,floors=[{'name':'矿运岸台','y':3,'max_y':12}])
    return m


def rock_cypress():
    m=dwarf(24,'石隙矮柏 · 扭根与风削树冠',35,33,35,('树木观赏',),role='structure',tags=('landscape',))
    arrival(m,17)
    for x,z,r in ((15,21,7),(22,16,5),(10,13,4)):
        for y in range(2,7):
            for xx,zz in octagon((x-r+y//2,z-r+y//2,x+r-y//2,z+r-y//2),2):m.set(xx,y,zz,'tuff')
    line(m,(16,6,19),(12,14,16),'stripped_spruce_log',1)
    curve(m,((12,14,16),(22,24,16),(25,22,20)),'stripped_spruce_log')
    for x,z,y,r in ((10,14,15,5),(16,16,20,6),(24,20,23,5)):
        for dy in range(3):disk(m,x,z,y+dy,r-dy,'spruce_leaves[persistent=true]')
    m.meta['roof_min_y']=29
    return m


BUILDERS.update({f'DV-{i:02d}-v01':f for i,f in ((8,bridge),(9,lamp),(10,statue_court),(11,rock_pine),(12,crystal_garden),(22,gate),(23,harbour),(24,rock_cypress))})
