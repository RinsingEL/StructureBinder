"""Northern shop reference sheet: eight separate plans, no universal shop shell."""
from functools import partial
from .model import Model
from .northern_reference import _hall, _door
from .components import shelf, bench, window

NAMES=['侧炉热食干粮铺','转角防寒衣物铺','窄铺侧棚钓具店','工具售修敞院','前低后高住家杂货铺','凸窗风斗海图文具铺','制桶作业院','错位双屋油灯杂具铺']
TERMS=[['热食零售','干粮零售','烹饪'],['防寒衣物零售','裁缝'],['钓具零售','钓具维修'],['工具零售','工具维修'],['杂货零售','家庭居住'],['海图零售','文具零售','抄写'],['桶器零售','制桶','木器维修'],['灯具零售','灯具维修']]
NOTES=['外凸石炉、矮备餐棚与主厅共同围合前售卖区。','折角双山墙连接衣物前店与侧裁缝间，双街面开门。','狭长主店与开敞结线侧棚并列，院内保留钓具清洗与备料。','店屋退后，砧台前院与侧面修理棚形成开敞工作场。','临街低铺与后部高屋分离，后屋有炊洗、就餐、床柜。','短风斗接入主店，侧凸玻璃阅图窗间与后抄写区分开。','小销售屋旁是制桶开放院、截料棚和干板堆，不以木桶替代实际工位。','两栋错位山墙房分别出售和修配油灯，低檐连廊连接交付路线。']
SIZES=[(28,24,28),(29,24,29),(29,22,29),(32,25,30),(29,27,31),(31,26,30),(32,24,30),(33,24,31)]


def shop(v):
    sx,sy,sz=SIZES[v-1]
    m=Model(f'NS-F01-v{v:02d}',NAMES[v-1],(sx,sy,sz),family='NS-F01',civilization='北欧',role='fill',terrain={
        '选址':'稳定平缓且排水良好的街区地块，保留院落和屋檐范围，不要求临海',
        '供给':'商铺需相应货物及行业原料；有人居住的v05另需生活食水与燃料',
        '地面':'外街面脚底Y=3，入口和首层同高；基础连续落地',
        '边界':'原版方块静态表达行业工序，不模拟交易、风雪与专用设备行为'})
    m.meta.update(source=f'tools/structure_studio/studio/northern_fill_shops.py:shop({v})',function_terms=TERMS[v-1].copy(),
        roof_min_y=8,floors=[dict(name='营业与作业生活层',y=2,max_y=7)],
        ground_plane=dict(y=3,note='北侧外部街面与首层支撑方块顶面Y=3，不以屋顶或作业高台代替外部基准'),
        preview_context=dict(kind='flat',land_surface_y=3,bed_y=-1,padding=4,surface='snow'),
        design_notes=[NOTES[v-1]],differences=[NOTES[v-1]])
    m.box((2,0,2),(sx-3,2,sz-3),'cobblestone');m.box((2,2,2),(sx-3,2,sz-3),'gravel')
    m.box((2,3,2),(sx-3,sy-1,sz-3),'air')
    ex=[10,9,8,11,9,14,9,10][v-1]
    m.point('front','entrance',(ex,3,3),'北侧临街入口',facing='north')
    m.meta['connections'].append(dict(kind='pedestrian',pos=[ex,3,3],direction='north',clearance=[3,3],note='外街面脚底Y=3'))

    def hall(key,x0,z0,x1,z1,h=4,axis='z',door=None):
        _hall(m,x0,z0,x1,z1,wallh=h,axis=axis)
        if door is None:door=((x0+x1)//2,z0,'x')
        _door(m,door[0],door[1],axis=door[2])
        m.set(x0+1,5,z1-2,'wall_torch[facing=east]')
        m.room(key,key,(x0+1,3,z0+1),(x1-1,2+h,z1-1),'经营、行业工作或生活空间')
    def canopy(x0,z0,x1,z1,h=7):
        for x in (x0,x1):
            for z in (z0,z1):m.box((x,3,z),(x,h-1,z),'stripped_spruce_log[axis=y]')
        for z in (z0,z1):m.box((x0,h-1,z),(x1,h-1,z),'spruce_log[axis=x]')
        for x in range(x0,x1+1):
            for z in range(z0,z1+1):m.set(x,h+(z-z0)//4,z,'spruce_slab[type=bottom]')
        for x in (x0,x1):
            for z in range(z0,z1+1):m.box((x,h-1,z),(x,h+(z-z0)//4-1,z),'spruce_log[axis=z]')
        m.set(x0+1,h-2,z0,'lantern[hanging=true]')
    def use(key,x,z,b,ax=None,az=None):
        m.set(x,3,z,b);m.point(key,'work',(x,3,z),key,approach=(x if ax is None else ax,3,z-1 if az is None else az))
    def table(x,z,w=5,top='flower_pot'):
        m.box((x,3,z),(x+w-1,3,z),'spruce_planks')
        for xx in range(x,x+w,2):m.set(xx,4,z,top)
    def sales(key,x,z,w=5,top='flower_pot'):
        table(x,z,w,top);m.point(key,'work',(x+1,3,z),key,approach=(x+1,3,z-1))
    def stores(x,z,w=5,contents='barrel'):shelf(m,x,3,z,w,material='spruce',contents=contents)
    def fire(x,z,top=17):
        m.set(x,3,z,'smoker[facing=north]');m.box((x,4,z),(x,top,z),'cobblestone')
        m.set(x,top+1,z,'cobblestone_slab[type=bottom]')
        m.point('炉_'+str(x),'work',(x,3,z),'炉火与烹饪',approach=(x,3,z-1))
    def join(x0,z0,x1,z1):m.box((x0,3,z0),(x1,5,z1),'air')

    if v==1:
        hall('热食与干粮店',4,8,16,23,4,axis='x',door=(10,8,'x'))
        canopy(4,3,16,7,6);sales('热食交付',5,5,7,'cake[bites=0]')
        stores(5,21,7);table(5,15,6,'white_carpet');use('备餐',6,15,'crafting_table',az=14)
        m.box((17,2,13),(23,6,20),'stone_bricks');m.box((18,7,14),(22,7,19),'stone_brick_slab[type=bottom]')
        join(16,15,18,17);use('外凸烤炉',19,16,'smoker[facing=west]',ax=18,az=16)
        m.box((22,5,18),(23,17,19),'cobblestone');m.box((22,18,18),(23,18,19),'cobblestone_slab[type=bottom]')
        m.set(13,3,20,'water_cauldron[level=3]');bench(m,10,3,11,4,'north','spruce')
    elif v==2:
        hall('防寒衣物前铺',4,6,14,24,4,door=(9,6,'x'))
        hall('转角裁缝间',15,15,25,24,4,axis='x',door=(25,19,'z'))
        join(14,19,15,21);canopy(4,3,13,5,6)
        stores(5,22,6,'white_wool');sales('衣物量身',5,11,6,'white_carpet')
        for x,c in ((5,'brown'),(8,'gray'),(11,'white')):m.box((x,3,16),(x,5,17),c+'_wool')
        table(17,20,6,'white_carpet');use('裁缝',18,20,'loom[facing=north]',az=19)
        stores(17,23,6,'white_wool');m.point('试穿','circulation',(12,3,19),'防寒衣物试穿角')
        m.point('corner','entrance',(26,3,19),'转角取衣侧门',facing='east')
    elif v==3:
        hall('窄钓具店',4,5,12,24,4,door=(8,5,'x'))
        canopy(16,10,25,23,6);sales('钓具选配',5,10,5)
        stores(5,22,6);use('配线',6,17,'loom[facing=north]',az=16)
        join(12,17,12,19);table(18,16,6);use('结线维修',19,16,'crafting_table',az=15)
        use('旧具清洗',23,20,'water_cauldron[level=3]',ax=22,az=20)
        for x in (17,21,25):m.box((x,3,22),(x,6,22),'spruce_fence');m.set(x,7,22,'tripwire_hook[facing=north]')
        m.box((17,7,22),(25,7,22),'spruce_log[axis=x]');stores(17,11,6)
    elif v==4:
        hall('工具销售屋',4,12,15,25,5,door=(10,12,'x'))
        canopy(19,8,28,23,7);sales('工具柜台',5,16,7)
        stores(5,23,8);use('工具试磨',12,21,'grindstone[face=floor,facing=north]',az=20)
        use('砧前修理',13,7,'anvil[facing=east]',ax=14,az=7)
        table(20,17,6);use('拆修装配',21,17,'smithing_table',az=16)
        stores(20,21,6);m.set(26,3,12,'water_cauldron[level=3]');m.point('作业院','circulation',(16,3,11),'工具开放作业院')
        m.box((4,3,4),(10,3,4),'cobblestone_wall');m.box((20,3,4),(28,3,4),'cobblestone_wall')
    elif v==5:
        hall('低前杂货铺',4,5,14,13,3,axis='x',door=(9,5,'x'))
        hall('高后店主住宅',8,16,23,27,5,door=(17,16,'x'))
        canopy(16,8,23,12,6);sales('杂货配单',5,9,6)
        stores(5,12,7);table(17,10,5);m.point('侧摊','work',(18,3,10),'临街日杂陈列',approach=(18,3,9))
        fire(10,24,19);m.set(12,3,24,'water_cauldron[level=3]');m.set(14,3,24,'barrel')
        table(11,20,5);bench(m,11,3,22,5,'north','spruce')
        m.bed(20,3,23,'brown','north');m.point('owner_bed','bed',(20,3,23),'店主床位',approach=(21,3,23))
        stores(18,26,4);m.point('居家','circulation',(18,3,19),'后屋炊食生活区')
    elif v==6:
        hall('海图文具主屋',7,10,21,25,5,door=(14,10,'x'))
        hall('避风前室',10,5,17,9,3,axis='x',door=(14,5,'x'))
        hall('凸窗阅图间',22,14,26,20,3,axis='x',door=(22,17,'z'))
        join(13,9,15,10);join(21,16,22,18)
        window(m,(26,4,15),(26,6,19),'z','glass')
        stores(8,23,7,'bookshelf');sales('铺图校对',9,15,7)
        use('海图台',11,15,'cartography_table',az=14)
        table(9,21,7);use('抄写',10,21,'lectern[facing=north]',az=20)
        use('凸窗阅图',24,18,'cartography_table',az=17);stores(17,23,3,'flower_pot')
        bench(m,11,3,7,3,'north','spruce')
    elif v==7:
        hall('桶器小店',4,7,13,25,4,door=(9,7,'x'))
        canopy(18,15,28,25,7);sales('桶器验货',5,12,6,'barrel')
        stores(5,23,6);table(5,19,5);use('拼板修桶',6,19,'crafting_table',az=18)
        m.box((20,3,21),(26,4,23),'stripped_spruce_log[axis=z]')
        use('截料台',19,17,'stonecutter[facing=north]',az=16)
        for x,z in ((17,7),(21,8),(25,6),(16,12),(24,12)):
            m.set(x,3,z,'barrel');m.set(x,4,z,'barrel')
        table(17,11,4);use('桶箍装配',18,11,'smithing_table',az=10)
        m.point('木料','storage',(22,3,21),'风干木料',approach=(22,3,20))
        m.box((16,3,4),(28,3,4),'cobblestone_wall')
    else:
        hall('油灯售卖主屋',4,5,15,21,4,door=(10,5,'x'))
        hall('错位灯具修配屋',21,11,29,27,3,door=(25,11,'x'))
        canopy(16,16,20,20,6);join(15,17,15,19);join(21,17,21,19)
        sales('灯具交付',5,11,7,'lantern');stores(5,19,7,'lantern')
        bench(m,10,3,15,3,'north','spruce');stores(22,25,6)
        table(22,17,5);use('灯罩修配',23,17,'crafting_table',az=16)
        table(22,21,5,'lantern');use('灯芯分配',23,21,'loom[facing=north]',az=20)
        m.point('修配入口','entrance',(25,3,10),'错位副屋入口',facing='north')
    return m


BUILDERS={f'NS-F01-v{i:02d}':partial(shop,i) for i in range(1,9)}
