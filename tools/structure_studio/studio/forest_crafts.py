"""Production layouts: cultivation, diagnosis, honey processing and everyday trade."""
from functools import partial
from .forest_symbiosis import base,ground,path,tree,lodge,pergola,entry,living,station,storage
from .components import shelf,bench
from .samples import railing


CRAFT_PLANS={
 1:((33,26,30),(4,6,27,25),None,'紧凑分区坊','单栋林下小型生产屋，后段独立歇班与生活空间'),
 2:((33,27,43),(5,5,27,38),None,'长条作业坊','纵深窄林隙里的长条作业屋，原料与后段生活区沿长轴依次布置'),
 3:((47,28,38),(4,6,24,31),(29,8,42,23),'晾场院坊','生产主屋与独立户外棚分列小院；适合有采光与排水的林缘'),
 4:((49,29,41),(4,7,21,31),(28,7,44,31),'双庭分院','生产与管理居住分成两座独立屋，中央通道到达树荫后庭'),
}


def staff_quarters(m,key,x0,z0,x1,z1,beds=1):
    """Private sleeping bay, household table, kitchen and workday storage."""
    y=3;screen=x0+7;mid=(x0+x1)//2
    m.box((screen,y,z0+2),(screen,5,z1-1),'spruce_planks')
    m.door(screen,y,z0+3,'spruce','east')
    for i in range(beds):
        bx=x0+2+i*3
        m.bed(bx,y,z1-2,'green','north')
        m.point(key+'bed'+str(i),'sleep',(bx,y,z1-2),'员工隐私床位',approach=(bx+1,y,z1-2))
    storage(m,key+'linen',x0+2,3,z0+1,4,'员工衣物与换洗布品')
    m.meta['points'][-1]['approach']=[x0+3,3,z0+2]
    m.box((x1-2,3,z0+2),(x1-2,3,z0+5),'spruce_planks')
    station(m,key+'cook',x1-2,3,z0+2,'smoker[facing=west]','员工厨房炉灶与备餐台',approach=(x1-3,3,z0+2))
    m.box((x1-2,4,z0+2),(x1-2,14,z0+2),'cobblestone')
    m.set(x1-2,3,z0+4,'water_cauldron[level=3]')
    tx=screen+3;tz=z0+3
    m.box((tx,3,tz),(x1-4,3,tz),'spruce_slab[type=top]')
    bench(m,tx,3,tz+2,max(2,x1-3-tx),'north')
    m.set(tx,4,tz,'flower_pot')
    m.point(key+'meal','work',(tx,3,tz),'员工餐叙与休息桌',approach=(tx,3,tz-1))
    storage(m,key+'pantry',screen+2,3,z1-1,max(3,x1-screen-4),'食品与个人用品分柜')
    m.meta['points'][-1]['approach']=[screen+2,3,z1-2]
    if z1-z0>13:
        shelf(m,x0+2,3,z0+7,4,'spruce','bookshelf')
        m.box((x0+2,3,z0+11),(x0+5,3,z0+11),'spruce_slab[type=top]')
        bench(m,x0+2,3,z0+13,3,'north')
        station(m,key+'office',x1-3,3,z0+10,'lectern[facing=west]','独立管理记录桌',approach=(x1-3,3,z0+9))
        m.box((screen+3,3,z0+10),(x1-4,3,z0+11),'spruce_planks')
        station(m,key+'laundry',screen+3,3,z1-5,'loom[facing=south]','员工衣物与工作围裙缝补')
        m.set(x1-3,3,z1-5,'water_cauldron[level=3]')
    m.room(key,'员工起居与管理',(x0+1,3,z0+1),(x1-1,7,z1-1),'屏风门后床位、衣物柜、厨房操作台、餐叙桌椅与家庭后勤；深院含管理和洗缝区')


def craft(kind,v):
    family,title={'mushroom':('FS-02','菌菇育房'),'herb':('FS-07','草药馆'),'honey':('FS-08','蜂蜜工坊')}[kind]
    size,b,annex,variant,condition=CRAFT_PLANS[v]
    m=base(f'{family}-v{v:02d}',variant+' · '+title,size,condition)
    m.meta['source']='tools/structure_studio/studio/forest_crafts.py:craft'
    ground(m,2,2,size[0]-3,size[2]-3)
    x0,z0,x1,z1=b
    lodge(m,*b,kind='hip' if v in (1,4) else 'gable',door=(x1,14,'east') if v==4 else None)
    cut=z1-9
    if v!=4:
        m.box((x0+1,3,cut),(x1-1,7,cut),'spruce_planks');m.door((x0+x1)//2,3,cut,'spruce','south')
        staff_quarters(m,'rest',x0,cut,x1,z1)
        work_end=cut-2
    else:
        lodge(m,*annex,kind='hip',door=(annex[0],14,'west'))
        staff_quarters(m,'rest',*annex,beds=2)
        tree(m,24,34,23,3);work_end=z1-2
    # A central aisle connects reception, production and the private rear door.
    mid=(x0+x1)//2
    station(m,'record',mid-2,3,z0+3,'lectern[facing=south]','批次与来访登记')
    if kind=='mushroom':
        for x in (x0+2,x1-3):
            for z in range(z0+6,work_end+1,3):
                m.box((x,3,z),(x+1,3,z+1),'mycelium')
                m.set(x,4,z,'brown_mushroom');m.set(x+1,4,z+1,'red_mushroom')
        station(m,'harvest',mid+2,3,z0+6,'composter','培养基与采收分拣',approach=(mid+1,3,z0+6))
        storage(m,'stock',x0+2,3,z0+1,5,'培养器皿与包装物')
        m.meta['points'][-1]['approach']=[x0+3,3,z0+2]
        # Four cultivation lanes use the whole working depth, with cross aisles.
        for j,z in enumerate(range(z0+5,work_end,5)):
            for k,(a,bx) in enumerate(((x0+6,mid-3),(mid+4,x1-6))):
                if a<=bx:
                    m.box((a,3,z),(bx,3,min(z+2,work_end)),'mycelium')
                    for xx in range(a,bx+1,2):
                        m.set(xx,4,z,'brown_mushroom');m.set(xx,4,min(z+2,work_end),'red_mushroom')
                    m.point(f'culture{j}{k}','work',(a,3,z),'分批培养床与侧向采收道',approach=(a,3,z-1))
        if v==4:
            for j,z in enumerate(range(z0+7,work_end,7)):
                m.box((x0+6,3,z),(mid+1,3,min(z+3,work_end)),'mycelium')
                for xx in (x0+6,mid+1):
                    m.set(xx,4,z,'brown_mushroom');m.set(xx,4,min(z+2,work_end),'red_mushroom')
                m.point(f'batch{j}','work',(x0+6,3,z),'中央批次培养床与双侧采收通道',approach=(x0+5,3,z))
        m.box((x1-7,3,z0+1),(x1-2,3,z0+1),'spruce_planks')
        station(m,'package',x1-5,3,z0+1,'crafting_table','菌菇成品称分与包装',approach=(x1-5,3,z0+2))
        m.set(x1-2,4,z0+1,'barrel[facing=south]')
        m.set(x0+2,3,z0+3,'water_cauldron[level=3]')
        m.room('culture','分批培养与采收',(x0+2,3,z0+5),(x1-2,6,work_end),'外侧小菌床与内侧大培养床并用；中轴和横道分隔批次，前端清洗与包装')
        m.meta['design_notes']=['菌床使用菌丝体支撑，培养和采收站位位于干燥木质走道；窗与棚屋表达遮蔽，未模拟真实光照或菌菇增殖。']
    elif kind=='herb':
        m.bed(x0+3,3,work_end,'white','north');m.point('examine','work',(x0+3,3,work_end),'问诊检查床',approach=(x0+4,3,work_end))
        station(m,'mix',x1-3,3,z0+6,'brewing_stand','药材配制台',approach=(x1-4,3,z0+6))
        m.set(x1-2,3,z0+4,'water_cauldron[level=3]')
        shelf(m,x0+2,3,z0+1,5,'spruce');bench(m,mid+1,3,work_end,3,'north')
        storage(m,'remedy',x1-6,3,work_end,4,'分格干燥药材')
        if v in (2,4):
            for j,z in enumerate(range(z0+9,work_end-3,7)):
                m.box((x0+3,3,z),(x0+6,3,z),'spruce_slab[type=top]')
                m.set(x0+3,4,z,'flower_pot')
                bench(m,x0+3,3,z+2,3,'north')
                m.point(f'consult{j}','work',(x0+5,3,z),'分诊与药方记录桌',approach=(x0+5,3,z+1))
                storage(m,f'herbcase{j}',x1-6,3,z,4,'按用途分类的药材柜')
        # Waiting, diagnosis and dispensing are legible, separate destinations.
        bench(m,x0+3,3,z0+4,max(2,mid-x0-7),'south')
        m.box((x0+2,3,work_end-3),(mid-3,5,work_end-3),'spruce_planks')
        m.box((mid-3,3,work_end-2),(mid-3,5,work_end+1),'spruce_planks')
        m.door(mid-3,3,work_end-1,'spruce','east')
        m.box((x0+5,3,work_end-1),(max(x0+5,mid-5),3,work_end-1),'spruce_slab[type=top]')
        m.set(x0+5,4,work_end-1,'flower_pot')
        m.box((x1-3,3,z0+3),(x1-3,3,z0+6),'spruce_planks')
        m.set(x1-3,4,z0+6,'brewing_stand')
        m.set(x1-3,4,z0+5,'flower_pot')
        station(m,'dispense',mid+2,3,z0+2,'crafting_table','药方核对与分装取药',approach=(mid+2,3,z0+3))
        m.box((mid+3,3,z0+2),(x1-3,3,z0+2),'spruce_planks')
        m.set(x1-4,4,z0+2,'barrel[facing=south]')
        m.room('clinic','带门屏风检查间',(x0+1,3,work_end-3),(mid-3,7,work_end+1),'候诊与病床分离；床侧保留检查站位和记录台，侧门保护隐私')
        m.meta['design_notes']=['问诊、配制、清洗、干药储藏与管理生活分区；药材疗效和药剂行为不属于该静态资产。']
    else:
        station(m,'press',x0+3,3,z0+6,'smoker[facing=east]','蜜蜡温和处理炉外形',approach=(x0+4,3,z0+6))
        m.box((x0+3,4,z0+6),(x0+3,14,z0+6),'cobblestone')
        station(m,'pack',x1-3,3,z0+6,'crafting_table','分装包装台',approach=(x1-4,3,z0+6))
        for x in range(x0+2,x0+6):m.set(x,3,work_end,'barrel[facing=up]')
        storage(m,'honey',x1-6,3,work_end,4,'蜂具与封装成品')
        m.set(mid+2,3,z0+8,'water_cauldron[level=3]')
        if v in (2,4):
            for j,z in enumerate(range(z0+10,work_end-3,7)):
                m.box((x0+3,3,z),(x0+6,3,z+1),'spruce_planks')
                m.set(x0+3,4,z,'flower_pot');m.set(x0+6,4,z+1,'yellow_candle[candles=3,lit=false]')
                m.point(f'seal{j}','work',(x0+5,3,z),'封装与蜡件定型操作岛',approach=(x0+5,3,z-1))
                storage(m,f'gearcase{j}',x1-6,3,z,4,'待装配蜂具与周转封装材料')
        m.box((x0+2,3,z0+1),(mid-4,3,z0+1),'spruce_planks')
        for x in range(x0+2,mid-3,2):m.set(x,4,z0+1,'barrel[facing=south]')
        m.box((x1-6,3,z0+1),(x1-2,3,z0+1),'spruce_planks')
        m.set(x1-4,4,z0+1,'yellow_candle[candles=3,lit=false]')
        station(m,'dispatch',x1-3,3,z0+1,'barrel[facing=south]','封签蜂蜜成品与出货复核',approach=(x1-3,3,z0+2))
        # Preparation basin and short centre islands leave the axial route open.
        for j,z in enumerate(range(z0+5,work_end-1,6)):
            m.box((x0+6,3,z),(mid-2,3,z+1),'spruce_planks')
            m.set(x0+6,4,z,'flower_pot')
            m.point(f'filter{j}','work',(mid-2,3,z),'解盖过滤与批次周转台',approach=(mid-1,3,z))
        m.box((x1-2,3,z0+4),(x1-2,3,min(work_end-1,z0+8)),'spruce_planks')
        m.set(x1-2,4,z0+5,'yellow_candle[candles=2,lit=false]')
        m.room('honeyline','原料处理至封装出货',(x0+2,3,z0+1),(x1-2,7,work_end),'原蜜入库、解盖过滤台、处理炉、清洗盆、封装台与封签成品分段布置')
        m.meta['design_notes']=['处理、清洗、包装、成品和蜂具分开，蜂箱设在屋外避开顾客与歇班入口；没有生成蜜蜂实体或自动生产。']
    m.room('production',title+'作业间',(x0+1,3,z0+1),(x1-1,7,work_end+1),'接待记录、行业作业、原料成品与中央穿行走道')
    if v==3:
        pergola(m,*annex)
        ax,az,bx,bz=annex
        station(m,'yard',ax+2,3,az+3,'composter' if kind=='mushroom' else 'loom[facing=south]','院落分拣与晾晒')
        for x in range(ax+2,bx-1,3):
            m.set(x,3,bz-2,'barrel[facing=up]')
            m.set(x,5,bz-2,'spruce_trapdoor[half=top,open=false]')
            m.box((x,6,bz-2),(x,7,bz-2),'chain[axis=y]')
        m.room('yard','户外操作棚',(ax+1,3,az+1),(bx-1,7,bz-1),'原料整理、晾晒架与遮雨周转货物')
        tree(m,36,31,22,4)
    if kind=='honey':
        for x in range(7,min(size[0]-7,22),5):
            m.set(x,3,3,'beehive[facing=north,honey_level=3]')
        m.meta['terrain']['蜂场']='北侧蜂箱外缘需接有花林缘；入口绕行，蜂箱为原版静态块，不带实体与产蜜证据'
    elif kind=='herb':
        for x in range(5,min(size[0]-6,25),4):
            m.set(x,2,3,'moss_block');m.set(x,3,3,'cornflower')
    if v==4:entry(m,24,2)
    else:path(m,mid-1,2,mid+1,z0-1);entry(m,mid,2)
    m.meta['differences']=[condition,f'{title}的设备、储物、处理站位与管理生活空间已按本平面独立安排。']
    return m


SHOPS={
 1:('松麦食铺',(32,25,29),(4,6,26,23),None,'smoker[facing=south]','食品烹制与售卖','宽门前食铺，侧后灶台与干货柜形成短回路'),
 2:('药叶小铺',(29,25,38),(4,6,23,33),None,'brewing_stand','药材挑选与配制','窄长临路铺，前接待、后干药和店主歇班'),
 3:('藤织小店',(43,25,31),(4,6,24,26),(29,7,38,20),'loom[facing=south]','编织作业台','织屋与开敞展示棚分列院落，展示与织机作业互不阻挡'),
 4:('林具修理铺',(42,26,34),(4,6,23,28),(28,8,37,23),'grindstone[face=floor,facing=south]','工具修理与交接','主屋保护精细工具，东院棚承担脏活和收件'),
 5:('种荚杂货铺',(38,25,30),(9,6,32,24),(3,8,6,21),'composter','种子包装与交换','偏置长柜台面对左侧窄廊，适合一侧临树的林缘地块'),
 6:('木器作坊铺',(37,25,37),(5,6,30,31),None,'crafting_table','木器制作与修补','深进店面用隔墙形成前店后作，保留大件搬运中央线'),
 7:('蜂蜡灯具铺',(40,26,35),(4,6,22,29),(27,10,35,25),'smithing_table','蜡烛与灯具装配','灯具主店与独立包装棚组成折角院落，成品与加工作业分开'),
 8:('林路图记铺',(35,26,33),(5,6,29,27),None,'cartography_table','林路图册记录','横向阅图桌与后墙书柜组成公共咨询铺，适合林路节点'),
}


def shop(v):
    title,size,b,annex,block,label,note=SHOPS[v]
    m=base(f'FS-F01-v{v:02d}',title,size,note)
    m.meta['source']='tools/structure_studio/studio/forest_crafts.py:shop'
    ground(m,2,2,size[0]-3,size[2]-3)
    x0,z0,x1,z1=b;mid=(x0+x1)//2
    lodge(m,*b,kind='hip' if v in (1,3,5,8) else 'gable')
    cut=z1-7
    m.box((x0+1,3,cut),(x1-1,7,cut),'spruce_planks');m.door(mid,3,cut,'spruce','south')
    m.box((x0+3,3,z0+5),(x1-3,3,z0+5),'spruce_planks')
    # An intentional two-block gap lets the keeper move between counter and back room.
    m.box((mid,3,z0+5),(mid+1,3,z0+5),'air')
    station(m,'trade',x0+4,3,z0+5,block,label)
    if v in (1,4,6):m.set(x1-3,3,z0+3,'water_cauldron[level=3]')
    shelf(m,x0+2,3,z0+1,5,'spruce','bookshelf' if v==8 else 'barrel')
    storage(m,'stock',x0+2,3,z1-1,x1-x0-7,'后场分类商品与工具')
    m.bed(x1-3,3,z1-2,'green','north');m.point('rest','sleep',(x1-3,3,z1-2),'店主歇班床',approach=(x1-4,3,z1-2))
    m.set(x1-3,3,cut+2,'barrel[facing=up]')
    bench(m,x0+3,3,cut-1,4,'north')
    # Every shopkeeper has a real private sleeping bay and domestic back room.
    m.box((x1-5,3,cut+2),(x1-5,5,z1-1),'spruce_planks')
    m.door(x1-5,3,cut+3,'spruce','east')
    m.box((x0+2,3,cut+2),(x0+4,3,cut+2),'spruce_planks')
    station(m,'keepercook',x0+2,3,cut+2,'smoker[facing=south]','店主厨房炉灶与备餐台')
    m.box((x0+2,4,cut+2),(x0+2,14,cut+2),'cobblestone')
    m.set(x0+4,3,cut+2,'water_cauldron[level=3]')
    m.box((mid-2,3,cut+3),(mid+1,3,cut+3),'spruce_slab[type=top]')
    bench(m,mid-2,3,cut+4,3,'north')
    m.point('keepermeal','work',(mid,3,cut+3),'后场用餐与账本桌',approach=(mid,3,cut+2))
    # Front displays vary by trade and include a second staffed preparation bay.
    shelf(m,x1-6,3,z0+1,4,'spruce','bookshelf' if v==8 else 'barrel')
    for px in (x0+6,x1-4):
        m.set(px,4,z0+5,{1:'brown_mushroom',2:'flower_pot',3:'green_carpet',4:'lantern',5:'flower_pot',6:'flower_pot',7:'yellow_candle[candles=3,lit=false]',8:'lantern'}[v])
    if v in (2,3,4,6,7,8):
        zz=z0+8
        m.box((x0+2,3,zz),(x0+5,3,zz),'spruce_planks')
        station(m,'service',x0+3,4,zz,{2:'brewing_stand',3:'loom[facing=south]',4:'grindstone[face=floor,facing=south]',6:'crafting_table',7:'yellow_candle[candles=2,lit=false]',8:'lectern[facing=south]'}[v],{2:'按方配药与称分台',3:'成衣裁剪与修补台',4:'工具收件与初检台',6:'小件木器装配台',7:'烛芯裁切与蜡件装配台',8:'路线咨询与图册整理台'}[v],approach=(x0+3,3,zz-1))
    if v==1:
        m.box((x0+3,3,z0+8),(mid-2,3,z0+8),'spruce_slab[type=top]')
        bench(m,x0+3,3,z0+9,max(3,mid-x0-4),'north')
        m.set(x0+3,4,z0+8,'flower_pot')
    if v==5:
        m.box((x0+3,3,z0+8),(mid-2,3,z0+8),'spruce_planks')
        for j,xx in enumerate(range(x0+3,mid-1,2)):m.set(xx,4,z0+8,'melon' if j%2 else 'pumpkin')
        m.point('produce','work',(x0+4,3,z0+8),'林缘瓜果与种荚分装陈列',approach=(x0+4,3,z0+7))
    if v==1:
        m.box((x0+4,4,z0+5),(x0+4,14,z0+5),'cobblestone')
        m.box((x1-8,3,z0+8),(x1-5,3,z0+8),'spruce_slab[type=top]')
        bench(m,x1-8,3,z0+9,4,'north')
    elif v==2:
        for j,z in enumerate((z0+10,z0+15)):
            storage(m,f'herbs{j}',x1-7,3,z,5,'前店分格药材展示与挑选')
        m.set(x1-3,3,z0+7,'water_cauldron[level=3]')
    elif v==3:
        m.box((x1-7,3,z0+9),(x1-3,3,z0+9),'spruce_planks')
        for i,c in enumerate(('green','lime','white','brown','gray')):
            m.set(x1-7+i,4,z0+9,c+'_carpet')
        m.point('cloth','work',(x1-5,3,z0+9),'织品展示与整理台',approach=(x1-5,3,z0+8))
    elif v==4:
        station(m,'anvil',x1-5,3,cut-4,'anvil[facing=north]','精细林具校正',approach=(x1-5,3,cut-5))
        m.box((x1-7,3,cut-3),(x1-3,3,cut-3),'spruce_planks')
    elif v==5:
        for x in range(x1-8,x1-2,2):
            m.set(x,3,cut-3,'composter');m.set(x,4,cut-3,'spruce_trapdoor[half=bottom,open=false]')
        m.point('seeds','storage',(x1-6,3,cut-3),'种荚分选与密闭种子箱',approach=(x1-6,3,cut-4))
    elif v==6:
        m.box((x0+3,3,z0+10),(x0+8,3,z0+11),'spruce_planks')
        station(m,'cut',x0+5,4,z0+10,'stonecutter[facing=north]','木器台面加工外形',approach=(x0+5,3,z0+9))
        m.box((x1-5,3,z0+10),(x1-3,4,z0+15),'stripped_spruce_log[axis=z]')
    elif v==7:
        m.box((x1-7,3,z0+11),(x1-3,3,z0+11),'spruce_planks')
        for i,x in enumerate(range(x1-7,x1-2,2)):
            m.set(x,4,z0+11,f'yellow_candle[candles={i+1},lit=false]')
        m.point('lights','work',(x1-5,3,z0+11),'蜡灯成品陈列',approach=(x1-5,3,z0+10))
    else:
        m.box((x0+3,3,z0+9),(x0+8,3,z0+10),'spruce_planks')
        bench(m,x0+3,3,z0+12,5,'north')
        station(m,'atlas',x1-4,3,z0+10,'lectern[facing=north]','林路图册阅览',approach=(x1-4,3,z0+9))
    m.room('sales','接待与行业作业',(x0+1,3,z0+1),(x1-1,7,cut-1),'行业柜台、客户站位、物品展示与服务通道')
    m.room('back','储藏与歇班',(x0+1,3,cut+1),(x1-1,7,z1-1),'分类商品柜、店主床位与私人用品')
    if annex:
        ax,az,bx,bz=annex
        pergola(m,*annex)
        if bx-ax>5:
            station(m,'annex',ax+2,3,az+3,{3:'loom[facing=south]',4:'grindstone[face=floor,facing=south]',7:'smithing_table'}.get(v,'crafting_table'),'院棚行业作业与包装')
            storage(m,'outside',ax+1,3,bz-1,max(2,bx-ax-2),'短时周转物资')
        else:
            m.point('side','circulation',(ax+1,3,az+3),'侧向通行廊')
        m.room('annex','院落配套棚',(ax+1,3,az+1),(bx-1,7,bz-1),'有独立柱脚与遮雨顶的行业配套空间')
    path(m,mid-1,2,mid+1,z0-1);entry(m,mid,2)
    m.meta['differences']=[note,'行业设备及柜台、后室、附棚采用独立位置与作业用途。']
    m.meta['design_notes']=['成品包含行业柜台、后场储物、店主歇班与通路；物品交易、药剂、修理与生产行为未接入。']
    return m


BUILDERS={f'{family}-v{i:02d}':partial(craft,kind,i) for family,kind in [('FS-02','mushroom'),('FS-07','herb'),('FS-08','honey')] for i in range(1,5)}
BUILDERS.update({f'FS-F01-v{i:02d}':partial(shop,i) for i in range(1,9)})
