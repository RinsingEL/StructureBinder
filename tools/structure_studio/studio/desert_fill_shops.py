"""Eight separately planned desert shops, with industry shaping their massing."""
from .model import Model
from .components import shell, window, shelf, bench
from .samples import railing

NAMES=['侧炉低翼面包铺','窄巷货院香料铺','前售后裁阶台布铺','折角敞院工具铺','绿荫柱廊茶铺','双院独窑陶器铺','错进高仓杂货铺','晒皮侧院旅具铺']
TERMS=[['面包零售','烘焙'],['香料零售','香料配制'],['布匹零售','裁缝'],['工具零售','工具维修'],['茶饮','饮水供应'],['陶器零售','制陶'],['食品零售','杂货零售'],['皮具零售','皮具制作','皮具修补']]
SIZES=[(26,18,28),(28,15,29),(28,17,30),(28,15,30),(29,18,30),(29,19,30),(28,17,30),(29,18,30)]
NOTES=['紧凑主店向侧面凸出砖炉，后接低矮和面备料翼；取消空屋顶。','窄长营业铺靠一侧，另一侧留进货院与低木棚；无屋顶楼梯。','前段低铺、后段高裁剪间，独立侧台晾布，短阶从侧院进入。','L形货库与工具壁围合开放修理院，局部木檐留出砧前作业天空。','后厨高翼、低茶室与半围合柱廊围着茶院，上层小露台经后侧楼梯连接。','制坯间、前展示棚和独立大窑分离成双院，窑烟囱是最高点。','前低零售间与错位高仓通过侧巷连接，低果蔬棚补足临街面。','小前铺接宽后作坊，皮料侧院独立通风晾挂，楼体不占满地块。']


def shop(variant):
    if variant not in range(1,9):raise ValueError(variant)
    size=SIZES[variant-1];sx,sy,sz=size
    m=Model(f'DS-F01-v{variant:02d}',NAMES[variant-1],size,family='DS-F01',civilization='沙漠',role='fill',terrain={
        '选址':'稳定商业街地块，北侧顾客入口和侧后方进货作业面须接通',
        '供给':'按行业提供原料、货品和饮水；烘焙与陶窑需燃料、排烟空间',
        '接驳':'外部街面脚底Y=2，与首层同高；高台仅在明确外阶处接通'})
    m.meta.update(source=f'tools/structure_studio/studio/desert_fill_shops.py:shop({variant})',
        function_terms=TERMS[variant-1].copy(),roof_min_y=6,
        ground_plane=dict(y=2,note='北侧店外场坪支撑方块Y=1，外部步行街面上边界Y=2，与首层入口齐平'),
        preview_context=dict(kind='flat',land_surface_y=2,bed_y=-1,padding=4,surface='sand'),
        floors=[dict(name='售卖与行业作业',y=1,max_y=5),dict(name='完整上部体量',y=6,max_y=sy-1)],
        design_notes=[NOTES[variant-1],'日间营业店铺，不声明店主住宅；炉具、货品和工序为原版静态表达。'],differences=[NOTES[variant-1]])
    m.box((2,0,2),(sx-3,1,sz-3),'cut_sandstone');m.box((2,2,2),(sx-3,sy-1,sz-3),'air')
    m.box((3,1,3),(sx-4,1,sz-4),'smooth_sandstone')
    ex=[8,9,7,16,17,12,9,9][variant-1]
    m.point('front','entrance',(ex,2,3),'临街顾客入口',facing='north')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[ex,1,2],direction='north',clearance=[3,4],note='同层外街面脚底Y=2'))

    def room(key,x0,z0,x1,z1,h,door=None):
        shell(m,(x0,1,z0),(x1,h-1,z1),'smooth_sandstone','smooth_sandstone',ceiling='smooth_sandstone')
        for x in (x0,x1):
            for z in (z0,z1):m.box((x,2,z),(x,h,z),'cut_sandstone')
        # Only a restrained roof lip, not a complete repeated crenellation ring.
        m.box((x0,h+1,z1),(x1,h+1,z1),'smooth_sandstone_slab[type=bottom]')
        window(m,(x0,3,z0+3),(x0,4,z0+4),'z','yellow_stained_glass')
        window(m,(x1,3,z0+3),(x1,4,z0+4),'z','yellow_stained_glass')
        if x1-x0>6:window(m,(x0+3,3,z1),(x0+4,4,z1),color='yellow_stained_glass')
        m.set(x0+1,4,z1-2,'wall_torch[facing=east]')
        if door is None:door=((x0+x1)//2,z0,'north')
        x,z,side=door
        if side in ('north','south'):m.box((x-1,2,z),(x+1,4,z),'air')
        else:m.box((x,2,z-1),(x,4,z+1),'air')
        m.room(key,key,(x0+1,2,z0+1),(x1-1,h-1,z1-1),'本店售卖、作业或后勤空间')

    def canopy(x0,z0,x1,z1,h=6,f=1,cloth=False):
        for x in (x0,x1):
            for z in (z0,z1):m.box((x,f+1,z),(x,h-1,z),'stripped_spruce_log[axis=y]')
        for z in (z0,z1):m.box((x0,h-1,z),(x1,h-1,z),'stripped_spruce_log[axis=x]')
        for x in range(x0,x1+1):
            for z in range(z0,z1+1):
                b=('brown_wool' if x%4==0 else 'white_wool') if cloth else 'spruce_slab[type=bottom]'
                m.set(x,h if not cloth or z>z0 else h-1,z,b)
        m.set(x0+1,h-2,z0,'lantern[hanging=true]')

    def work(key,p,b,a):m.set(*p,b);m.point(key,'work',p,key,approach=a)
    def table(x,z,w=4,y=2,top='flower_pot'):
        m.box((x,y,z),(x+w-1,y,z),'spruce_planks')
        for xx in range(x,x+w,2):m.set(xx,y+1,z,top)
    def sale(key,x,z,w=5,top='flower_pot'):
        table(x,z,w,top=top);m.point(key,'work',(x+1,2,z),key,approach=(x+1,2,z-1))
    def chimney(x,z,y0,y1,material='stone_bricks'):
        m.box((x,y0,z),(x+1,y1,z+1),material)
        m.box((x,y1+1,z),(x+1,y1+1,z+1),'stone_brick_slab[type=bottom]')
    def stock(x,z,w=3):m.box((x,2,z),(x+w-1,3,z+1),'barrel')
    def stair(x,z,count):
        for i in range(count):
            y=2+i
            m.box((x,1,z+i),(x+1,y-1,z+i),'sandstone')
            for xx in (x,x+1):m.set(xx,y,z+i,'sandstone_stairs[facing=south]')
            for xx in (x-1,x+2):
                m.box((xx,1,z+i),(xx,y,z+i),'cut_sandstone')
                m.set(xx,y+1,z+i,'spruce_fence[north=true,south=true]')

    if variant==1:
        room('面包前店',4,7,14,16,8,(8,7,'north'))
        room('低翼和面备料',4,17,12,24,5,(10,17,'north'))
        m.box((9,2,16),(11,4,17),'air')
        canopy(4,3,13,6,h=6,cloth=True);sale('面包售卖',5,5,5,'cake[bites=0]')
        table(5,12,5,top='cake[bites=0]');shelf(m,5,2,15,4,material='spruce',contents='flower_pot')
        # The oven protrudes beyond the shop wall, with a lower firing throat.
        m.box((15,1,10),(21,5,16),'bricks');m.box((16,6,11),(20,6,15),'brick_slab[type=bottom]')
        m.box((20,2,12),(21,3,14),'air');m.set(19,2,13,'furnace[facing=east,lit=true]')
        m.box((14,2,11),(15,4,13),'air');m.set(16,2,12,'smoker[facing=west]')
        work('侧炉烘烤',(16,2,12),'smoker[facing=west]',(15,2,12));chimney(19,14,6,13)
        table(5,21,6,top='white_carpet');work('和面',(6,2,21),'crafting_table',(6,2,20))
        m.set(10,2,23,'water_cauldron[level=3]');m.box((5,2,23),(7,3,23),'hay_block')
        m.point('备料','circulation',(9,2,19),'低翼备料通道')
        canopy(16,19,21,23,h=5);stock(18,21,3)
    elif variant==2:
        room('窄面香料铺',5,7,12,24,8,(9,7,'north'))
        canopy(5,4,12,6,h=6,cloth=True);sale('分类香料',6,9,4)
        for x,c in ((6,'brown'),(8,'yellow')):m.set(x,3,9,c+'_carpet')
        shelf(m,6,2,22,5,material='spruce',contents='flower_pot')
        table(6,18,4);work('研配',(7,2,18),'crafting_table',(7,2,17))
        m.box((12,2,16),(12,4,18),'air')
        # A long open side court carries incoming sacks; it is not another roof plate.
        m.box((14,2,24),(24,3,24),'sandstone');m.box((24,2,9),(24,3,24),'sandstone')
        canopy(16,14,23,23,h=5);stock(18,21,5);stock(21,16,2)
        table(15,10,5);m.point('香料分拣','work',(16,2,10),'侧院分拣',approach=(16,2,9))
        for x in (16,18,20):m.set(x,3,10,'potted_fern')
        m.set(14,2,21,'water_cauldron[level=3]');m.point('进货院','circulation',(16,2,18),'侧院进货通道')
    elif variant==3:
        room('前段布匹店',4,5,11,13,6,(7,5,'north'))
        room('后段裁缝高间',4,14,14,25,10,(10,14,'north'))
        window(m,(14,7,19),(14,8,21),'z','yellow_stained_glass')
        m.box((8,2,13),(10,4,14),'air');canopy(4,3,10,4,h=5,cloth=True)
        sale('布匹接待',5,8,4,'white_carpet')
        for x,c in ((5,'blue'),(7,'red'),(9,'brown')):m.box((x,2,11),(x,3,12),c+'_wool')
        table(7,19,6,top='white_carpet');work('裁剪',(8,2,19),'crafting_table',(8,2,18))
        work('织机',(5,2,22),'loom[facing=east]',(6,2,22));m.set(8,2,23,'loom[facing=north]')
        m.box((11,2,23),(11,4,24),'white_wool');m.point('试衣','circulation',(13,2,23),'后部试衣隔帘')
        # Independent low side platform, accessed by a three-rise stair in the side yard.
        m.box((16,4,14),(24,4,24),'smooth_sandstone')
        for x in (16,24):
            for z in (14,24):m.box((x,2,z),(x,3,z),'cut_sandstone')
        stair(21,11,3)
        for z in (14,24):m.box((16,5,z),(24,5,z),'smooth_sandstone')
        for x in (16,24):m.box((x,5,14),(x,5,24),'smooth_sandstone')
        m.box((21,5,14),(22,7,14),'air')
        canopy(17,18,23,23,h=10,f=4)
        for x,c in ((18,'white'),(20,'brown'),(22,'blue')):m.box((x,6,22),(x,8,22),c+'_wool')
        m.point('晾布','work',(20,6,22),'侧台晾布',approach=(20,5,21))
        m.point('侧台','circulation',(21,5,16),'晾布平台')
    elif variant==4:
        room('工具后库',4,17,19,25,7,(15,17,'north'))
        room('折角工具铺',4,7,9,16,6,(9,11,'east'))
        m.box((6,2,16),(8,4,17),'air')
        # A low rear canopy stops short of the street and leaves the forge court open.
        canopy(11,11,22,16,h=6)
        sale('零件柜台',5,10,3);work('磨具',(5,2,14),'grindstone[face=floor,facing=east]',(6,2,14))
        work('修理砧',(16,2,7),'anvil[facing=east]',(17,2,7))
        table(12,14,6);work('装配',(14,2,14),'smithing_table',(14,2,13))
        shelf(m,5,2,23,10,material='spruce',contents='barrel');stock(16,22,2)
        m.set(21,2,14,'water_cauldron[level=3]');m.point('修理院','circulation',(13,2,9),'开放工具修理院')
        for x in (12,16,20):m.set(x,2,18,'barrel')
        m.box((5,3,8),(8,3,8),'spruce_slab[type=bottom]')
    elif variant==5:
        room('后翼茶水厨房',5,18,20,25,8,(16,18,'north'))
        room('低茶室',4,8,10,17,6,(10,12,'east'))
        canopy(12,8,20,15,h=6,cloth=True)
        for x,z in ((13,10),(17,13),(6,12)):
            m.set(x,2,z,'spruce_slab[type=top]');m.set(x,3,z,'flower_pot')
            m.set(x,2,z+2,'spruce_stairs[facing=south]')
        m.point('茶院','work',(13,2,10),'阴棚茶席',approach=(12,2,10))
        sale('供茶',12,21,6);table(6,23,7);work('烧水',(7,2,23),'smoker[facing=north]',(7,2,22))
        work('净水',(17,2,23),'water_cauldron[level=3]',(17,2,22));chimney(5,24,3,11)
        # Rear-side ascent, unlike the other shops; it serves a small kitchen roof terrace.
        stair(22,13,7);m.box((20,8,20),(24,8,23),'smooth_sandstone')
        for x in (20,24):m.box((x,2,23),(x,7,23),'cut_sandstone')
        for z in (18,25):m.box((5,9,z),(20,9,z),'smooth_sandstone')
        for x in (5,20):m.box((x,9,18),(x,9,25),'smooth_sandstone')
        m.box((20,9,20),(20,11,23),'air')
        railing(m,(24,9,20),(24,9,23),wood='spruce',axis='z')
        m.box((21,8,24),(24,8,24),'smooth_sandstone');railing(m,(21,9,24),(24,9,24),wood='spruce',axis='x')
        bench(m,11,9,23,4,'north','spruce');m.point('露台','circulation',(13,9,21),'后厨上方小茶台')
        for x,z in ((4,5),(22,6)):
            m.set(x,2,z,'flower_pot');m.set(x,2,z,'potted_azalea_bush')
    elif variant==6:
        room('制坯工作房',4,12,12,25,6,(12,16,'east'))
        canopy(4,5,20,10,h=6)
        sale('陶器陈列',5,6,7);sale('大陶器陈列',15,9,5)
        table(5,18,6);work('制坯',(6,2,18),'crafting_table',(6,2,17))
        shelf(m,5,2,23,6,material='spruce',contents='flower_pot');m.set(6,2,21,'water_cauldron[level=3]')
        m.box((9,2,21),(10,3,22),'clay')
        # Standalone kiln with a stepped brick body and a rear flue, not attached to the shop box.
        m.box((17,1,16),(23,4,23),'bricks');m.box((18,5,17),(22,7,22),'bricks')
        m.box((19,8,18),(21,9,21),'brick_slab[type=bottom]');chimney(21,21,8,15,'bricks')
        m.set(17,2,19,'furnace[facing=west]');m.set(17,3,19,'furnace[facing=west]')
        m.point('独窑','work',(17,2,19),'装窑烧成',approach=(16,2,19))
        m.box((14,1,15),(16,1,24),'stone_bricks');m.point('窑前院','circulation',(15,2,16),'窑前装卸院')
        table(14,12,7);m.point('晾坯','work',(16,2,12),'露天晾坯架',approach=(16,2,11))
    elif variant==7:
        room('低层食品前铺',4,6,14,15,6,(9,6,'north'))
        room('错位高货仓',10,17,23,25,10,(16,17,'north'))
        canopy(16,6,23,13,h=5,cloth=True);sale('食品杂货',5,9,7)
        for x,b in ((5,'melon'),(8,'pumpkin'),(11,'hay_block')):m.set(x,3,9,b)
        shelf(m,5,2,13,8,material='spruce',contents='flower_pot')
        m.box((14,2,11),(14,4,13),'air')
        table(17,10,5,top='melon');m.point('果蔬','work',(19,2,10),'侧棚果蔬',approach=(19,2,9))
        stock(11,23,5);stock(20,22,3);m.box((11,2,19),(13,3,20),'hay_block')
        table(17,20,4);work('分装',(18,2,20),'crafting_table',(18,2,19))
        m.set(21,2,18,'water_cauldron[level=3]');m.point('高仓','circulation',(16,2,22),'高货仓清点通道')
        # High warehouse eaves and slit windows articulate a useful tall storage volume.
        for x in (12,16,20):window(m,(x,6,25),(x,8,25),color='yellow_stained_glass')
        m.box((11,5,23),(22,5,24),'spruce_slab[type=bottom]')
        for x in (11,22):m.box((x,2,24),(x,4,24),'stripped_spruce_log[axis=y]')
    else:
        room('旅具小前铺',4,7,12,17,7,(9,7,'north'))
        room('宽后裁皮作坊',4,18,19,25,8,(15,18,'north'))
        m.box((9,2,17),(11,4,18),'air');canopy(4,4,12,6,h=6,cloth=True)
        sale('皮具售卖',5,10,5);shelf(m,5,2,15,5,material='spruce',contents='barrel')
        table(7,21,8,top='brown_carpet');work('裁皮',(9,2,21),'crafting_table',(9,2,20))
        work('修补',(17,2,22),'crafting_table',(16,2,22));stock(5,23,3)
        # Side hanging yard is tall, airy and detached from the low front sales room.
        canopy(16,9,24,16,h=10)
        for x in (17,20,23):m.box((x,5,14),(x,8,14),'brown_wool')
        for x in (17,20,23):m.box((x,9,14),(x,9,16),'stripped_spruce_log[axis=z]')
        m.point('晒皮','work',(20,5,14),'通风晾皮架',approach=(20,2,13))
        m.set(16,2,20,'water_cauldron[level=3]');m.set(18,2,20,'water_cauldron[level=3]')
        table(16,7,6,top='brown_carpet');m.point('分料','work',(17,2,7),'院侧皮料分拣',approach=(17,2,6))
    return m
