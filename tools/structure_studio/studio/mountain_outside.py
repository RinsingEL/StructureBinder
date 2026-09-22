"""Grounded, irrigated garden plots and complete small utility shelters."""
from functools import partial
from .mountain_forge import base,hall,front,room,use,FLOOR
from .mountain_trade import canopy,ore_stack
from .components import shelf,crate_stack,column,bench

def finish(m,note):
    m.meta.update(source='tools/structure_studio/studio/mountain_outside.py:BUILDERS',design_notes=[note],differences=[note])
    return m

def plot(m,x0,z0,x1,z1,y=1,crop='potatoes',channel=None):
    # A contained, level irrigation channel in each bed; no claim about fluid ticks.
    channel=channel if channel is not None else (x0+x1)//2
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            m.set(x,y-1,z,'dirt')
            if x==channel:m.set(x,y,z,'water')
            else:m.set(x,y,z,'farmland[moisture=7]');m.set(x,y+1,z,f'{crop}[age={3 if crop=="beetroots" else 7}]')
    # Raised frame and surrounding dry edge on all sides, bounded by foundation.
    for x in range(x0-1,x1+2):
        for z in (z0-1,z1+1):m.set(x,y,z,'spruce_log[axis=x]')
    for z in range(z0,z1+1):
        for x in (x0-1,x1+1):m.set(x,y,z,'spruce_log[axis=z]')

def field(v):
    names=['顺坡窄条薯田','单阶双层小梯田','折角L形菜畦','院旁工具菜园','碎石分区混作田','背风采光小菜棚']
    w,d=[(17,29),(27,29),(27,27),(29,27),(29,29),(23,27)][v-1]
    m=base(f'MF-F02-v{v:02}',names[v-1],w,d,16,role='fill')
    m.box((1,0,1),(w-2,0,d-2),'dirt');m.box((1,1,1),(w-2,1,d-2),'coarse_dirt')
    m.point('entry','entrance',(w//2,2,2),'耕作通路入口',facing='north')
    if v==1:
        plot(m,5,5,11,23,crop='potatoes');m.set(3,2,5,'composter[level=3]');m.set(13,2,22,'barrel[facing=up,open=false]')
        use(m,'crop','work',6,2,10,'田侧采收',4,10);room(m,'bed','沿等高线窄条田',4,4,12,24,'一条分水沟、两条薯畦与四周田埂')
        note='窄长等高线地块，中央水沟将薯畦分成两带，侧边通路连续；单层地坪不假装追随任意坡。'
    elif v==2:
        m.box((2,0,15),(24,2,26),'dirt');m.box((12,2,15),(14,2,15),'stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]')
        plot(m,5,5,21,11,channel=13,crop='carrots')
        # Add secondary channels to keep each half within four blocks of water.
        for x in (8,18):
            for z in range(5,12):m.set(x,1,z,'water');m.set(x,2,z,'air')
        plot(m,5,18,21,23,y=2,channel=13,crop='potatoes')
        for x in (8,18):
            for z in range(18,24):m.set(x,2,z,'water');m.set(x,3,z,'air')
        m.point('upper','circulation',(13,3,16),'上层田埂',look_at=[13,3,21]);use(m,'water','work',13,2,20,'上层取水沟',13,17,ay=3)
        room(m,'lower','低层胡萝卜畦',4,4,22,12,'三条灌水沟与种植带');room(m,'upper','上层薯畦',4,17,22,24,'高一格的独立灌水田',3,6)
        m.meta.update(preview_context=dict(kind='slope',land_surface_y=2,padding=5,run=15,rise=1,slope_origin_z=15),floors=[dict(name='双层田畦',y=1,max_y=5)])
        note='单阶小梯田，前胡萝卜后薯畦各有独立水沟，中部宽阶及田埂连接一格坡差。'
    elif v==3:
        plot(m,5,5,11,20,crop='beetroots');plot(m,15,15,21,20,crop='carrots')
        m.set(19,2,6,'composter[level=3]');m.set(21,2,6,'barrel[facing=up,open=false]');bench(m,16,2,10,4)
        use(m,'crop','work',16,2,17,'折角采收',14,17);room(m,'garden','L形两畦',4,4,22,21,'长甜菜畦与短胡萝卜畦围出空角，空角作工具和歇脚')
        note='两块不同长宽的田畦组成 L 形，内角保留取水、堆肥与歇脚，不用作物填满转角。'
    elif v==4:
        hall(m,3,3,12,12,height=5,roof='hip');front(m,8);shelf(m,4,2,10,5);m.set(10,2,6,'crafting_table');use(m,'tools','work',10,2,6,'农具整理',9,6)
        plot(m,17,5,23,20,crop='potatoes');plot(m,5,17,11,21,crop='carrots')
        m.set(4,2,15,'composter[level=3]');use(m,'crop','work',18,2,12,'菜畦采收',16,12)
        room(m,'shed','农具种子屋',4,4,11,11,'农具、种子和整理工作台');room(m,'beds','院旁两块菜畦',4,14,24,22,'较长薯畦与短胡萝卜畦，院路可达')
        note='小农具屋旁配长薯畦，屋后再置短胡萝卜畦；院路联系种子储存、堆肥和采收。'
    elif v==5:
        for x,z,crop in ((5,5,'potatoes'),(17,5,'beetroots'),(5,18,'carrots'),(17,18,'wheat')):plot(m,x,z,x+6,z+5,crop=crop)
        m.box((13,1,3),(15,1,25),'gravel');m.box((3,1,13),(25,1,15),'gravel')
        m.set(14,2,14,'water_cauldron[level=3]');m.set(14,2,24,'composter[level=3]')
        use(m,'water','work',14,2,14,'中央洗菜水盆',14,12);room(m,'beds','四块碎石间小田',4,4,24,24,'四种原版作物独立小畦，十字碎石通道分区')
        note='碎石台地留十字硬路，四块独立窄畦混作；每畦自有水沟，中央水盆供洗菜。'
    else:
        plot(m,6,7,16,21,crop='potatoes',channel=11)
        for x in (8,14):
            for z in range(7,22):m.set(x,1,z,'water');m.set(x,2,z,'air')
        for z in range(4,24):
            for x in (3,19):m.box((x,2,z),(x,4,z),'stone_bricks');m.set(x,5,z,'glass')
        m.box((3,2,24),(19,5,24),'stone_bricks')
        for x in range(3,20):
            y=6+min(x-3,19-x)//3
            for z in range(3,25):m.set(x,y,z,'glass' if z%5 else 'spruce_planks')
        for z in (4,9,14,19,24):
            for x in (3,19):column(m,x,z,2,6,'stripped_spruce_log','spruce_planks')
        m.set(4,2,4,'composter[level=3]');m.set(18,2,4,'barrel[facing=up,open=false]')
        use(m,'crop','work',6,2,12,'棚内采收',5,12);room(m,'shelter','采光背风菜棚',4,4,18,23,'玻璃采光盖、三侧矮挡风墙及可绕行田埂')
        m.meta['roof_min_y']=6
        note='玻璃弧折盖与三面低石墙挡风，正面开放；棚内集中薯畦及四周通路用于受控的小型菜园。'
    m.meta['agriculture']=dict(crops='原版马铃薯、胡萝卜、甜菜和小麦依变体设置',layout=note,irrigation='每畦固定水源，最终NBT另核对4格水源距离和耕地支撑。')
    m.meta['terrain']={'选址':'仅限具备足够土壤、光照、温度和稳定水分的地表地块，不放入无光山腹或永久冻土。','高程':'默认田埂脚底 Y=2；MF-F02-v02 后半单阶脚底 Y=3。','生产':'作物为原版外观与几何表达；未模拟生长tick、光照、温度或整合包产量。','边界':'独立模板田块有田埂和封边，不代表Landscape自然生成的大田。'}
    return finish(m,note)

def shed(v):
    names=['贴山矿具靠壁棚','独立四柱材料棚','窄货道长料棚','带封闭工具间的修缮棚']
    w,d=[(23,19),(25,23),(17,31),(31,23)][v-1]
    m=base(f'MF-F03-v{v:02}',names[v-1],w,d,16,role='fill')
    if v==1:
        m.box((3,2,14),(19,7,15),'stone_bricks');canopy(m,4,4,18,13,roofy=8)
        shelf(m,5,2,12,5);m.set(16,2,11,'smithing_table');use(m,'work','work',16,2,11,'矿具检修',16,10);crate_stack(m,5,2,5,4,3,2)
        room(m,'shed','背墙矿具棚',5,5,17,13,'背墙挂具、前货堆和单侧检修台');note='高背墙贴山挡土、前面开放，材料堆在左侧，右側矿具检修留完整站位。'
        site='仅用于稳定山脚石坎，+Z 背墙需有真实山体或挡土依托；不得让背墙悬空。'
    elif v==2:
        canopy(m,4,4,20,18,roofy=8);crate_stack(m,6,2,6,4,4,3);ore_stack(m,15,2,6,'raw_iron_block',3,3)
        m.set(7,2,16,'crafting_table');use(m,'tools','work',7,2,16,'材料整理',8,16);shelf(m,13,2,16,6)
        room(m,'shed','四面独立材料棚',5,5,19,17,'木料货箱、少量原矿、整理台和矿具架');note='四面可接近的独立高棚，木料货箱与原矿分角，中间周转净空用于住宅维修。'
        site='开阔稳定维修地块，至少保留两侧材料搬运面。'
    elif v==3:
        canopy(m,4,4,12,26,roofy=7)
        m.box((4,2,4),(4,5,26),'spruce_planks')
        for z in (6,14,22):
            m.box((5,2,z),(7,3,z+3),'spruce_log[axis=z]')
        shelf(m,9,2,23,3);m.set(10,2,6,'stonecutter[facing=west]');use(m,'cut','work',10,2,6,'长料端切',9,6)
        room(m,'shed','单侧长料货棚',5,5,11,25,'长料靠背墙叠放，三格以上直线货道与端部切割台');note='窄长货棚的一侧封木墙叠放长料，另一侧连续纵向装卸道；用途和比例区别于方形棚。'
        site='狭长材料边地，沿 Z 方向保留通行与搬运净空，长料不能堵住短端出入口。'
    else:
        hall(m,3,3,12,19,height=5,roof='hip');front(m,8);m.door(12,2,11,facing='east')
        shelf(m,4,2,17,6);m.set(5,2,6,'smithing_table');use(m,'repair','work',5,2,6,'封闭工具修理',6,6);m.set(10,2,10,'water_cauldron[level=3]')
        canopy(m,16,4,27,18);crate_stack(m,18,2,6,5,3,2);m.set(24,2,14,'anvil[facing=north]');use(m,'anvil','work',24,2,14,'棚下重物锻修',24,13)
        room(m,'tools','封闭工具间',4,4,11,18,'工具架、精细修补和饮水');room(m,'shed','开放材料与锻修棚',17,5,26,17,'货箱、重物锻修与搬运面');note='封闭精修工具间配宽材料棚，内外门相通；棚下重物作业与可锁工具储存分开。'
        site='矿业或住宅实际修缮节点，街门和棚下卸料面分别落位。'
    m.point('shed_entry','entrance',(w//2,2,2),'棚前搬运入口')
    m.meta['terrain']={'选址':site,'高程':'干地与基础顶脚底 Y=2，四角支柱落在完整石基上。','用途':'少量材料周转与手工修缮造型；不代表自动仓储、搬运或矿业系统接入。'}
    return finish(m,note)

BUILDERS={**{f'MF-F02-v{i:02}':partial(field,i) for i in range(1,7)},**{f'MF-F03-v{i:02}':partial(shed,i) for i in range(1,5)}}
